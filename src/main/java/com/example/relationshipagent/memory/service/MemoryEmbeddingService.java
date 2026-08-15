package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.common.exception.SystemException;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.memory.job.MemoryJobExecutor;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * Independent, resumable vectorization for Memory deduplication candidates. It never merges or supersedes Memory.
 */
@Service
public class MemoryEmbeddingService {
    private final ChatFileRepository files;
    private final MemoryItemRepository memories;
    private final MemoryEmbeddingBatchWriter writer;
    private final ProcessingJobService jobs;
    private final MemoryJobExecutor executor;
    private final RelationshipAgentProperties props;
    private final ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> models;
    private final String configuredModel;

    public MemoryEmbeddingService(ChatFileRepository files, MemoryItemRepository memories, MemoryEmbeddingBatchWriter writer, ProcessingJobService jobs, MemoryJobExecutor executor, RelationshipAgentProperties props, ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> models, @Value("${spring.ai.openai.embedding.options.model:}") String configuredModel) {
        this.files = files;
        this.memories = memories;
        this.writer = writer;
        this.jobs = jobs;
        this.executor = executor;
        this.props = props;
        this.models = models;
        this.configuredModel = configuredModel;
    }

    public Accepted request(String chatFileId) {
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus()))
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING);
        var model = models.getIfAvailable();
        if (model == null)
            throw new BizException(ErrorCode.EMBEDDING_NOT_CONFIGURED, "embedding 未启用，Memory 向量候选不可用");
        if (!props.embedding().model().equals(configuredModel))
            throw new SystemException("Memory embedding model configuration conflicts with ra.embedding.model");
        String hash = inputHash(chatFileId, props.embedding().model());
        ProcessingJob job = jobs.createOrGet(chatFileId, ProcessingJob.TYPE_MEMORY_EMBED, hash);
        if (job == null) return new Accepted(null, hash, ProcessingJob.STATUS_SUCCESS, true);
        if (jobs.tryTakeover(job.getId())) executor.submit(job.getId(), () -> run(job.getId(), chatFileId, model));
        return new Accepted(job.getId(), hash, job.getStatus(), false);
    }

    private void run(String jobId, String chatFileId, org.springframework.ai.embedding.EmbeddingModel model) {
        try {
            String name = props.embedding().model();
            int size = props.embedding().batchSize();
            long total = memories.countPendingEmbed(chatFileId, name);
            int done = 0;
            jobs.updateProgress(jobId, 0, (int) total);
            while (true) {
                if (!jobs.isLeaseActive(jobId)) return;
                List<MemoryItem> batch = memories.selectPendingEmbed(chatFileId, name, size);
                if (batch.isEmpty()) break;
                List<float[]> vectors = model.embed(batch.stream().map(MemoryItem::getContent).toList());
                if (vectors.size() != batch.size())
                    throw new SystemException("Memory embedding API returned an unexpected vector count");
                for (float[] v : vectors)
                    if (v.length != props.embedding().dimensions())
                        throw new SystemException("Memory embedding dimension does not match ra.embedding.dimensions");
                writer.write(batch, vectors.toArray(new float[0][]), name);
                done += batch.size();
                jobs.updateProgress(jobId, done, (int) total);
                jobs.heartbeat(jobId);
            }
            if (jobs.isLeaseActive(jobId)) jobs.markSuccess(jobId);
        } catch (Exception e) {
            if (jobs.isLeaseActive(jobId)) jobs.markFailed(jobId, safe(e));
        }
    }

    /**
     * Hash source identity/content, never the vector write timestamp or model marker, so a completed run is truly idempotent.
     */
    private String inputHash(String file, String model) {
        String material = memories.selectList(new LambdaQueryWrapper<MemoryItem>().eq(MemoryItem::getChatFileId, file).eq(MemoryItem::getStatus, MemoryItem.STATUS_ACTIVE).orderByAsc(MemoryItem::getCreatedAt).orderByAsc(MemoryItem::getId)).stream().map(m -> m.getId() + "|" + m.getInputHash() + "|" + ProcessingJobService.hashInput(m.getContent() == null ? "" : m.getContent())).collect(Collectors.joining("\n"));
        return ProcessingJobService.hashInput(file, model, material);
    }

    private static String safe(Exception e) {
        String value = e.getClass().getSimpleName() + ": " + e.getMessage();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public record Accepted(String jobId, String inputHash, String status, boolean reused) {
    }
}
