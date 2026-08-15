package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.analysis.model.AgentRun;
import com.example.relationshipagent.analysis.service.AgentRunAuditService;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.memory.agent.MemoryMergeAgentClient;
import com.example.relationshipagent.memory.job.MemoryJobExecutor;
import com.example.relationshipagent.memory.model.MemoryAggregationBatch;
import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.memory.snapshot.MemorySnapshotService;
import com.example.relationshipagent.memory.validation.MemoryMergeDraftValidator;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Explicit, resumable M5 entry point. Five independent semantic groups share one remote request, never a merge decision.
 */
@Service
public class MemoryAggregationOrchestrator {
    private static final int CANDIDATES_PER_REQUEST = 5;
    private final RelationshipAgentProperties props;
    private final ChatFileRepository files;
    private final MemorySnapshotService snapshots;
    private final MemoryAggregationCandidateService candidates;
    private final ProcessingJobService jobs;
    private final MemoryJobExecutor executor;
    private final ObjectProvider<MemoryMergeAgentClient> agent;
    private final MemoryMergeDraftValidator validator;
    private final MemoryAggregationService writer;
    private final MemoryAggregationBatchService batches;
    private final AgentRunAuditService audits;

    public MemoryAggregationOrchestrator(RelationshipAgentProperties props, ChatFileRepository files, MemorySnapshotService snapshots, MemoryAggregationCandidateService candidates, ProcessingJobService jobs, MemoryJobExecutor executor, ObjectProvider<MemoryMergeAgentClient> agent, MemoryMergeDraftValidator validator, MemoryAggregationService writer, MemoryAggregationBatchService batches, AgentRunAuditService audits) {
        this.props = props;
        this.files = files;
        this.snapshots = snapshots;
        this.candidates = candidates;
        this.jobs = jobs;
        this.executor = executor;
        this.agent = agent;
        this.validator = validator;
        this.writer = writer;
        this.batches = batches;
        this.audits = audits;
    }

    public Accepted request(String chatFileId, String targetPerson) {
        return request(chatFileId, targetPerson, null, null);
    }

    public Accepted request(String chatFileId, String targetPerson, Integer requestedCandidateLimit) {
        return request(chatFileId, targetPerson, requestedCandidateLimit, null);
    }

    public Accepted request(String chatFileId, String targetPerson, Integer requestedCandidateLimit, String requestedCandidateKey) {
        // Merge 只处理稳定的 VALID observation；候选筛选和 inputHash 固定本次批次边界，支持断点续跑。
        if (!props.memory().enabled() || agent.getIfAvailable() == null)
            throw new BizException(ErrorCode.MEMORY_DISABLED);
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus()))
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING);
        String target = target(targetPerson);
        int limit = limit(requestedCandidateLimit);
        String candidateKey = candidateKey(requestedCandidateKey);
        List<MemoryAggregationCandidateService.Candidate> source = candidates.find(chatFileId, target);
        if (candidateKey != null) source = source.stream().filter(c -> candidateKey.equals(c.memoryKey())).toList();
        if (limit > 0 && source.size() > limit) source = List.copyOf(source.subList(0, limit));
        if (source.isEmpty())
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING, "no stable VALID observations available for target person");
        var snapshot = snapshots.create(chatFileId, target);
        String hash = ProcessingJobService.hashInput(snapshots.aggregationHash(snapshot, source), "candidateLimit=" + limit, "candidateKey=" + (candidateKey == null ? "" : candidateKey));
        ProcessingJob job = jobs.createOrGet(chatFileId, ProcessingJob.TYPE_MEMORY_AGGREGATE, hash);
        if (job == null) return new Accepted(null, hash, ProcessingJob.STATUS_SUCCESS, true);
        String jobLease = jobs.takeLease(job.getId());
        if (jobLease != null) {
            String leaseToken = UUID.randomUUID().toString();
            batches.requeueRunning(chatFileId, target, hash);
            executor.submit(job.getId(), () -> run(job.getId(), chatFileId, target, hash, limit, candidateKey, jobLease, leaseToken));
        }
        return new Accepted(job.getId(), hash, job.getStatus(), false);
    }

    private void run(String jobId, String file, String target, String hash, int limit, String candidateKey, String jobLease, String leaseToken) {
        try {
            if (!jobs.isLeaseActive(jobId, jobLease)) return;
            List<MemoryAggregationCandidateService.Candidate> work = candidates.find(file, target);
            if (candidateKey != null) work = work.stream().filter(c -> candidateKey.equals(c.memoryKey())).toList();
            if (limit > 0 && work.size() > limit) work = List.copyOf(work.subList(0, limit));
            jobs.updateProgress(jobId, jobLease, 0, work.size());
            int completed = 0;
            for (int start = 0; start < work.size(); start += CANDIDATES_PER_REQUEST) {
                if (!jobs.isLeaseActive(jobId, jobLease)) return;
                List<Claimed> claimed = new ArrayList<>();
                for (var candidate : work.subList(start, Math.min(start + CANDIDATES_PER_REQUEST, work.size()))) {
                    MemoryAggregationBatch checkpoint = batches.claim(file, target, hash, candidate, leaseToken);
                    if (checkpoint != null) claimed.add(new Claimed(candidate, checkpoint));
                }
                if (!claimed.isEmpty()) runBatch(jobId, jobLease, file, target, hash, claimed, leaseToken);
                completed += Math.min(CANDIDATES_PER_REQUEST, work.size() - start);
                jobs.heartbeat(jobId, jobLease);
                jobs.updateProgress(jobId, jobLease, completed, work.size());
            }
            jobs.markSuccess(jobId, jobLease);
        } catch (Exception e) {
            jobs.markFailed(jobId, jobLease, safe(e));
        }
    }

    private void runBatch(String jobId, String jobLease, String file, String target, String hash, List<Claimed> claimed, String leaseToken) {
        AgentRun audit = null;
        try {
            List<MemoryObservation> source = claimed.stream().flatMap(c -> c.candidate().observations().stream()).toList();
            audit = audits.start(file, props.analysis().provider(), props.analysis().model(), "MEMORY_AGGREGATE", source.size());
            // 一次请求可携带多个语义组，但模型输出仍须逐条通过候选 key/type/polarity 校验。
            var generated = agent.getObject().generate(source, target);
            if (!jobs.isLeaseActive(jobId, jobLease) || claimed.stream().anyMatch(value -> !batches.isClaimActive(value.checkpoint().getId(), leaseToken))) {
                audits.failed(audit, new IllegalStateException("aggregation worker lease lost"));
                return;
            }
            var validation = validator.validate(generated.draft(), source, target);
            var result = writer.write(file, target, hash, props.memory().aggregationVersion(), props.memory().mergePromptVersion(), audit.getId(), validation);
            audits.success(audit, generated.response(), result.created());
            for (var value : claimed)
                batches.success(value.checkpoint().getId(), leaseToken, audit.getId(), produced(validation, value.candidate()));
        } catch (Exception e) {
            audits.failed(audit, e);
            for (var value : claimed) batches.fail(value.checkpoint().getId(), leaseToken, safe(e));
            throw e;
        }
    }

    private int produced(MemoryMergeDraftValidator.ValidationResult validation, MemoryAggregationCandidateService.Candidate candidate) {
        return (int) validation.memories().stream().filter(item -> !"REJECTED".equals(item.status())).filter(item -> candidate.memoryKey().equals(item.draft().memoryKey()) && candidate.memoryType().equals(item.draft().memoryType()) && candidate.polarity().equals(item.draft().polarity())).count();
    }

    private String target(String requested) {
        String result = requested == null || requested.isBlank() ? props.memory().defaultTargetPerson() : requested;
        if (result == null || result.isBlank())
            throw new BizException(ErrorCode.MEMORY_TARGET_INVALID, "targetPerson is required");
        return result;
    }

    private static int limit(Integer requested) {
        if (requested == null) return 0;
        if (requested < 1 || requested > 50)
            throw new BizException(ErrorCode.PARAM_INVALID, "candidateLimit must be between 1 and 50");
        return requested;
    }

    private static String candidateKey(String requested) {
        if (requested == null || requested.isBlank()) return null;
        if (!requested.matches("[a-z0-9_]{1,100}"))
            throw new BizException(ErrorCode.PARAM_INVALID, "candidateKey must be a lowercase snake_case observation key");
        return requested;
    }

    private static String safe(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + e.getMessage();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private record Claimed(MemoryAggregationCandidateService.Candidate candidate, MemoryAggregationBatch checkpoint) {
    }

    public record Accepted(String jobId, String inputHash, String status, boolean reused) {
    }
}
