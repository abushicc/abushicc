package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.service.AgentRunAuditService;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.memory.agent.MemoryAgentClient;
import com.example.relationshipagent.memory.evidence.ObservationEvidencePacketBuilder;
import com.example.relationshipagent.memory.job.MemoryJobExecutor;
import com.example.relationshipagent.memory.model.MemoryExtractionBatch;
import com.example.relationshipagent.memory.snapshot.MemorySnapshotService;
import com.example.relationshipagent.memory.validation.ObservationDraftValidator;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.processing.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Coordinates manually-triggered, idempotent Observation extraction. Generated chat is not part of its read path.
 */
@Service
public class MemoryOrchestrator {
    private final RelationshipAgentProperties props;
    private final ChatFileRepository files;
    private final MessageRepository messages;
    private final MemorySnapshotService snapshots;
    private final ObservationEvidencePacketBuilder packets;
    private final ProcessingJobService jobs;
    private final MemoryJobExecutor executor;
    private final ObjectProvider<MemoryAgentClient> agent;
    private final ObservationDraftValidator validator;
    private final MemoryObservationWriter writer;
    private final MemoryExtractionBatchService batches;
    private final AgentRunAuditService audits;

    public MemoryOrchestrator(RelationshipAgentProperties props, ChatFileRepository files, MessageRepository messages, MemorySnapshotService snapshots, ObservationEvidencePacketBuilder packets, ProcessingJobService jobs, MemoryJobExecutor executor, ObjectProvider<MemoryAgentClient> agent, ObservationDraftValidator validator, MemoryObservationWriter writer, MemoryExtractionBatchService batches, AgentRunAuditService audits) {
        this.props = props;
        this.files = files;
        this.messages = messages;
        this.snapshots = snapshots;
        this.packets = packets;
        this.jobs = jobs;
        this.executor = executor;
        this.agent = agent;
        this.validator = validator;
        this.writer = writer;
        this.batches = batches;
        this.audits = audits;
    }

    public Accepted request(String chatFileId, String requestedTarget) {
        return request(chatFileId, requestedTarget, null);
    }

    /**
     * A deterministic, time-distributed sample keeps real-model acceptance runs bounded before a full extraction.
     */
    public Accepted request(String chatFileId, String requestedTarget, Integer requestedSessionLimit) {
        return request(chatFileId, requestedTarget, requestedSessionLimit, List.of());
    }

    /**
     * Explicit session selection is for bounded benchmark repair only; it cannot replace unrelated active observations.
     */
    public Accepted request(String chatFileId, String requestedTarget, Integer requestedSessionLimit, Collection<String> requestedSessionIds) {
        // Observation 提取只读取原始消息和分析证据，不读取 generated chat，避免把模型自己的话写回长期记忆。
        if (!props.memory().enabled() || agent.getIfAvailable() == null)
            throw new BizException(ErrorCode.MEMORY_DISABLED);
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus()))
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING);
        String target = target(requestedTarget);
        long count = messages.selectCount(new LambdaQueryWrapper<Message>().eq(Message::getChatFileId, chatFileId).eq(Message::getSpeaker, target).isNotNull(Message::getCleanedContent));
        if (count < 100)
            throw new BizException(ErrorCode.MEMORY_TARGET_INVALID, "target person has fewer than 100 text messages");
        List<String> selected = selected(requestedSessionIds);
        if (!selected.isEmpty() && requestedSessionLimit != null)
            throw new BizException(ErrorCode.PARAM_INVALID, "sessionLimit and sessionId cannot be combined");
        int limit = selected.isEmpty() ? sampleLimit(requestedSessionLimit) : 0;
        var snapshot = snapshots.create(chatFileId, target);
        String scope = selected.isEmpty() ? "sessionLimit=" + limit : "sessionIds=" + String.join(",", selected);
        String hash = ProcessingJobService.hashInput(snapshots.extractionHash(snapshot), scope);
        ProcessingJob job = jobs.createOrGet(chatFileId, ProcessingJob.TYPE_MEMORY_EXTRACT, hash);
        if (job == null) return new Accepted(null, null, "SUCCESS", true);
        if (jobs.tryTakeover(job.getId()))
            executor.submit(job.getId(), () -> run(job.getId(), chatFileId, target, hash, limit, selected));
        return new Accepted(job.getId(), hash, job.getStatus(), false);
    }

    private void run(String jobId, String chatFileId, String target, String hash, int limit, List<String> selected) {
        try {
            // 采样和分批让真实模型验证可控且可恢复；每批均有 checkpoint，租约丢失时立即停止写入。
            if (!jobs.isLeaseActive(jobId)) return;
            var source = selected.isEmpty() ? packets.build(chatFileId, target, props.memory().maxInputChars()) : packets.buildSelected(chatFileId, target, props.memory().maxInputChars(), selected);
            var all = sample(source, limit);
            var work = packets.batch(all, props.memory().maxSessionsPerBatch(), props.memory().maxInputChars(), hash, target);
            jobs.updateProgress(jobId, 0, work.size());
            for (int i = 0; i < work.size(); i++) {
                if (!jobs.isLeaseActive(jobId)) return;
                var batch = batches.claim(chatFileId, target, hash, work.get(i));
                if (batch == null) {
                    jobs.updateProgress(jobId, i + 1, work.size());
                    continue;
                }
                com.example.relationshipagent.analysis.model.AgentRun audit = null;
                try {
                    audit = audits.start(chatFileId, props.analysis().provider(), props.analysis().model(), "MEMORY_EXTRACT", work.get(i).packets().size());
                    // 远程调用在事务外执行，结果先经过白名单校验，再由 writer 以 observation 状态落库。
                    var generated = agent.getObject().generate(work.get(i), target);
                    var validation = validator.validate(generated.draft(), work.get(i).packets());
                    var result = writer.write(chatFileId, hash, props.memory().extractorVersion(), props.memory().observationPromptVersion(), audit.getId(), validation);
                    audits.success(audit, generated.response(), result.valid());
                    batches.success(batch.getId(), audit.getId(), result.valid() + result.reviewRequired());
                } catch (Exception e) {
                    audits.failed(audit, e);
                    batches.fail(batch.getId(), safe(e));
                    throw e;
                }
                jobs.heartbeat(jobId);
                jobs.updateProgress(jobId, i + 1, work.size());
            }
            if (selected.isEmpty()) writer.supersedePreviousInputs(chatFileId, target, hash);
            else writer.supersedePreviousInputsForSessions(chatFileId, target, hash, selected);
            jobs.markSuccess(jobId);
        } catch (Exception e) {
            jobs.markFailed(jobId, safe(e));
        }
    }

    private static int sampleLimit(Integer requested) {
        if (requested == null) return 0;
        if (requested < 1 || requested > 30)
            throw new BizException(ErrorCode.PARAM_INVALID, "sessionLimit must be between 1 and 30");
        return requested;
    }

    private static <T> List<T> sample(List<T> source, int limit) {
        if (limit == 0 || source.size() <= limit) return source;
        if (limit == 1) return List.of(source.get(0));
        List<T> result = new ArrayList<>();
        for (int i = 0; i < limit; i++)
            result.add(source.get((int) Math.floor((double) i * (source.size() - 1) / (limit - 1))));
        return List.copyOf(result);
    }

    private static List<String> selected(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> result = ids.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().sorted().toList();
        if (result.isEmpty() || result.size() > 30)
            throw new BizException(ErrorCode.PARAM_INVALID, "sessionId must contain 1 to 30 distinct values");
        return result;
    }

    private String target(String requested) {
        String target = requested == null || requested.isBlank() ? props.memory().defaultTargetPerson() : requested;
        if (target == null || target.isBlank())
            throw new BizException(ErrorCode.MEMORY_TARGET_INVALID, "targetPerson is required");
        return target;
    }

    private static String safe(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + e.getMessage();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public record Accepted(String jobId, String inputHash, String status, boolean reused) {
    }
}
