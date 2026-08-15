package com.example.relationshipagent.processing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcessingJobService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobService.class);

    private final ProcessingJobRepository jobRepository;
    private final RelationshipAgentProperties properties;

    public ProcessingJobService(ProcessingJobRepository jobRepository,
                                RelationshipAgentProperties properties) {
        this.jobRepository = jobRepository;
        this.properties = properties;
    }

    public boolean hasCompleted(String chatFileId, String jobType, String inputHash) {
        ProcessingJob existing = jobRepository.selectOne(new LambdaQueryWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId)
                .eq(ProcessingJob::getJobType, jobType)
                .eq(ProcessingJob::getInputHash, inputHash)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_SUCCESS));
        return existing != null;
    }

    public boolean isRunning(String chatFileId, String jobType, String inputHash) {
        // RUNNING 任务需要检查租约是否过期；过期时允许 CAS 接管，避免单个崩溃 worker 永久阻塞流水线。
        ProcessingJob existing = jobRepository.selectOne(new LambdaQueryWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId)
                .eq(ProcessingJob::getJobType, jobType)
                .eq(ProcessingJob::getInputHash, inputHash)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING));
        if (existing == null) return false;

        if (existing.getStartedAt() != null) {
            long runningMs = Instant.now().toEpochMilli() - existing.getStartedAt().toEpochMilli();
            if (runningMs > properties.job().staleRunningMs()) {
                log.warn("Job appears stale: id={}", existing.getId());
                return !tryRetakeStale(existing.getId());
            }
        }
        return true;
    }

    @Transactional
    public ProcessingJob createOrGet(String chatFileId, String jobType, String inputHash) {
        ProcessingJob existing = jobRepository.selectOne(new LambdaQueryWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId)
                .eq(ProcessingJob::getJobType, jobType)
                .eq(ProcessingJob::getInputHash, inputHash));

        if (existing != null) {
            return switch (existing.getStatus()) {
                case ProcessingJob.STATUS_SUCCESS -> null;
                case ProcessingJob.STATUS_RUNNING -> {
                    if (isStale(existing)) yield existing;
                    throw new BizException(ErrorCode.JOB_ALREADY_RUNNING);
                }
                default -> {
                    // 整清重跑:FAILED/CANCELLED → 重置为 PENDING,使其可被重新接管(满足 M2.5 重跑验收)
                    // PENDING 原样返回。不在此处校验 maxRetry,本地单用户工具允许无限重跑。
                    if (ProcessingJob.STATUS_FAILED.equals(existing.getStatus())
                            || ProcessingJob.STATUS_CANCELLED.equals(existing.getStatus())) {
                        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                                .eq(ProcessingJob::getId, existing.getId())
                                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_PENDING)
                                .set(ProcessingJob::getStartedAt, null)
                                .set(ProcessingJob::getFinishedAt, null)
                                .set(ProcessingJob::getErrorMessage, null));
                        existing.setStatus(ProcessingJob.STATUS_PENDING);
                        existing.setStartedAt(null);
                        existing.setFinishedAt(null);
                        existing.setErrorMessage(null);
                    }
                    yield existing;
                }
            };
        }

        ProcessingJob job = new ProcessingJob();
        job.setId(UUID.randomUUID().toString());
        job.setChatFileId(chatFileId);
        job.setJobType(jobType);
        job.setInputHash(inputHash);
        job.setStatus(ProcessingJob.STATUS_PENDING);
        job.setProgressCurrent(0);
        job.setRetryCount(0);
        job.setCreatedAt(Instant.now());
        jobRepository.insert(job);
        return job;
    }

    public boolean tryTakeover(String jobId) {
        int affected = jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_PENDING)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStartedAt, Instant.now()));
        if (affected > 0) return true;
        // createOrGet 对 stale RUNNING 返回原 job；这里必须走同一入口完成真正接管。
        return tryRetakeStale(jobId);
    }

    /**
     * Acquires a unique worker lease.  Unlike {@link #tryTakeover(String)}, the
     * returned token lets a caller prove it still owns the job after a stale
     * takeover.  Existing phase-1--3 callers retain their compatibility API.
     */
    public String takeLease(String jobId) {
        // leaseToken 随每次接管变化，后续心跳、进度和完成操作都必须证明仍持有当前租约。
        String token = UUID.randomUUID().toString();
        int affected = jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getId, jobId).eq(ProcessingJob::getStatus, ProcessingJob.STATUS_PENDING)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).set(ProcessingJob::getStartedAt, Instant.now())
                .set(ProcessingJob::getLeaseToken, token));
        if (affected > 0) return token;
        Instant staleBefore = Instant.now().minusMillis(properties.job().staleRunningMs());
        affected = jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getId, jobId).eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .lt(ProcessingJob::getStartedAt, staleBefore).set(ProcessingJob::getStartedAt, Instant.now())
                .set(ProcessingJob::getLeaseToken, token).setSql("retry_count = retry_count + 1"));
        return affected > 0 ? token : null;
    }

    public boolean isLeaseActive(String jobId, String leaseToken) {
        return jobRepository.selectCount(new LambdaQueryWrapper<ProcessingJob>().eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).eq(ProcessingJob::getLeaseToken, leaseToken)) == 1;
    }

    public void heartbeat(String jobId, String leaseToken) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>().eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).eq(ProcessingJob::getLeaseToken, leaseToken)
                .set(ProcessingJob::getStartedAt, Instant.now()));
    }

    public void updateProgress(String jobId, String leaseToken, int current, int total) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>().eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).eq(ProcessingJob::getLeaseToken, leaseToken)
                .set(ProcessingJob::getProgressCurrent, current).set(ProcessingJob::getProgressTotal, total));
    }

    public void markSuccess(String jobId, String leaseToken) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>().eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).eq(ProcessingJob::getLeaseToken, leaseToken)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_SUCCESS).set(ProcessingJob::getFinishedAt, Instant.now()));
    }

    public void markFailed(String jobId, String leaseToken, String errorMessage) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>().eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING).eq(ProcessingJob::getLeaseToken, leaseToken)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_FAILED).set(ProcessingJob::getErrorMessage, errorMessage)
                .set(ProcessingJob::getFinishedAt, Instant.now()));
    }

    /**
     * 抢占僵死的 RUNNING 任务(M3.1):started_at 超过 staleRunningMs 时,本方刷新 started_at 并续期。
     * CAS 条件 status=RUNNING AND started_at < staleBefore;命中即视为本方接管 → 返回 true。
     * retry_count 递增(setSql 避免 NULL 求值问题)。
     */
    public boolean tryRetakeStale(String jobId) {
        Instant staleBefore = Instant.now().minusMillis(properties.job().staleRunningMs());
        int affected = jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .lt(ProcessingJob::getStartedAt, staleBefore)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStartedAt, Instant.now())
                .setSql("retry_count = retry_count + 1"));
        return affected > 0;
    }

    public void markSuccess(String jobId) {
        ProcessingJob job = new ProcessingJob();
        job.setId(jobId);
        job.setStatus(ProcessingJob.STATUS_SUCCESS);
        job.setFinishedAt(Instant.now());
        jobRepository.updateById(job);
    }

    public void markFailed(String jobId, String errorMessage) {
        ProcessingJob job = new ProcessingJob();
        job.setId(jobId);
        job.setStatus(ProcessingJob.STATUS_FAILED);
        job.setErrorMessage(errorMessage);
        job.setFinishedAt(Instant.now());
        jobRepository.updateById(job);
    }

    /**
     * 批量重置下游 job 为 PENDING（阶段 2 M2.5：级联重置规则）。
     * <p>重跑上游阶段删除了下游产物 → 下游 job 的 inputHash 不变但产物已不存在，
     * 若不重置为 PENDING 则下游被 IDEMPOTENT_SKIP 永久跳过。故在上游重跑时批量重置下游。
     */
    public void resetToPending(String chatFileId, String... jobTypes) {
        // 先撤销仍在运行的下游 worker，再重置非运行任务；旧 worker 会在租约检查处停止。
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId)
                .in(ProcessingJob::getJobType, java.util.List.of(jobTypes))
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_CANCELLED)
                .set(ProcessingJob::getFinishedAt, Instant.now())
                .set(ProcessingJob::getErrorMessage, "superseded by upstream rebuild"));
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId)
                .in(ProcessingJob::getJobType, java.util.List.of(jobTypes))
                .ne(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_PENDING)
                .set(ProcessingJob::getStartedAt, null)
                .set(ProcessingJob::getFinishedAt, null)
                .set(ProcessingJob::getErrorMessage, null)
                .set(ProcessingJob::getProgressCurrent, 0));
    }

    /**
     * Cancels analysis workers without resetting them to PENDING; used when upstream evidence is rebuilt.
     */
    public void cancelRunning(String chatFileId, String jobType, String reason) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId).eq(ProcessingJob::getJobType, jobType)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStatus, ProcessingJob.STATUS_CANCELLED)
                .set(ProcessingJob::getFinishedAt, Instant.now()).set(ProcessingJob::getErrorMessage, reason));
    }

    public boolean isLeaseActive(String jobId) {
        ProcessingJob job = jobRepository.selectById(jobId);
        return job != null && ProcessingJob.STATUS_RUNNING.equals(job.getStatus());
    }

    /**
     * EMBED 心跳：每批次更新 started_at（防止长任务被 staleRunningMs 误判僵死）。
     * 带 status='RUNNING' 条件防止完成后误刷。
     */
    public void heartbeat(String jobId) {
        jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                .eq(ProcessingJob::getId, jobId)
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .set(ProcessingJob::getStartedAt, java.time.Instant.now()));
    }

    public void updateProgress(String jobId, int current, int total) {
        ProcessingJob job = new ProcessingJob();
        job.setId(jobId);
        job.setProgressCurrent(current);
        job.setProgressTotal(total);
        jobRepository.updateById(job);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void updateCursor(String jobId, Map<String, Object> cursor) {
        ProcessingJob job = new ProcessingJob();
        job.setId(jobId);
        try {
            job.setCursorJson(MAPPER.writeValueAsString(cursor));
        } catch (JsonProcessingException e) {
            job.setCursorJson("{}");
        }
        jobRepository.updateById(job);
    }

    public java.util.List<ProcessingJob> listByChatFile(String chatFileId) {
        return jobRepository.selectList(new LambdaQueryWrapper<ProcessingJob>()
                .eq(ProcessingJob::getChatFileId, chatFileId));
    }

    private boolean isStale(ProcessingJob job) {
        if (job.getStartedAt() == null) return true;
        long runningMs = Instant.now().toEpochMilli() - job.getStartedAt().toEpochMilli();
        return runningMs > properties.job().staleRunningMs();
    }

    public static String hashInput(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) md.update(p.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
