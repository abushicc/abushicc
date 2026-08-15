package com.example.relationshipagent.processing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 启动恢复扫描(M3.2):应用启动时把僵死(stale)的 RUNNING 任务批量重置为 PENDING。
 * <p>
 * 与设计文档 4.4"每阶段独立触发,不自动级联"一致——只重置、不自动入队执行,
 * 由用户重新触发对应 API。中间态 chat_file 仅记 warn 日志,不自动改状态(避免状态机复杂化)。
 */
@Component
@Order(0)
public class StaleJobRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StaleJobRecovery.class);

    private final ProcessingJobRepository jobRepository;
    private final ChatFileRepository chatFileRepository;
    private final RelationshipAgentProperties properties;

    public StaleJobRecovery(ProcessingJobRepository jobRepository,
                            ChatFileRepository chatFileRepository,
                            RelationshipAgentProperties properties) {
        this.jobRepository = jobRepository;
        this.chatFileRepository = chatFileRepository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant staleBefore = Instant.now().minusMillis(properties.job().staleRunningMs());

        List<ProcessingJob> staleJobs = jobRepository.selectList(new LambdaQueryWrapper<ProcessingJob>()
                .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                .lt(ProcessingJob::getStartedAt, staleBefore));

        if (staleJobs.isEmpty()) {
            log.info("Stale job recovery: no stale RUNNING jobs found");
        } else {
            // 批量重置为 PENDING(清 started_at),不自动入队执行
            int affected = jobRepository.update(null, new LambdaUpdateWrapper<ProcessingJob>()
                    .eq(ProcessingJob::getStatus, ProcessingJob.STATUS_RUNNING)
                    .lt(ProcessingJob::getStartedAt, staleBefore)
                    .set(ProcessingJob::getStatus, ProcessingJob.STATUS_PENDING)
                    .set(ProcessingJob::getStartedAt, null));
            log.warn("Stale job recovery: reset {} stale RUNNING job(s) to PENDING (not auto-enqueued). Re-trigger: {}",
                    affected, staleJobs.stream()
                            .map(j -> "chatFileId=" + j.getChatFileId() + ", jobType=" + j.getJobType())
                            .toList());
        }

        // 中间态 chat_file:仅记 warn 日志,不自动改状态
        List<ChatFile> intermediate = chatFileRepository.selectList(new LambdaQueryWrapper<ChatFile>()
                .in(ChatFile::getStatus,
                        ChatFile.STATUS_PARSING, ChatFile.STATUS_SESSIONIZING,
                        ChatFile.STATUS_CHUNKING, ChatFile.STATUS_EMBEDDING));
        if (!intermediate.isEmpty()) {
            log.warn("Stale job recovery: {} chat_file(s) in intermediate state, may need manual re-trigger: {}",
                    intermediate.size(),
                    intermediate.stream().map(c -> c.getId() + "(" + c.getStatus() + ")").toList());
        }
    }
}
