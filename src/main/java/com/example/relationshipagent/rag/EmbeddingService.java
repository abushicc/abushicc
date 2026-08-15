package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.common.exception.SystemException;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * EMBED 向量化服务（阶段 2 M3）：批量调用云端 embedding API 将检索块向量化。
 *
 * <p>记录级幂等续跑（0.5 决策 1）：每个 chunk 的 (embedding_model, embedding, embedded_at) 即断点状态，
 * 重跑时只选 embedding_model='' 或本模型 embedding IS NULL 的行，已完成的行天然跳过。
 * 不使用 cursor_json。
 *
 * <p>容错：EmbeddingModel bean 未配置时抛 EMBEDDING_NOT_CONFIGURED（0.2 硬要求）；
 * 缺 key 时应用仍能正常启动，仅 EMBED/向量检索功能不可用。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ChatFileService chatFileService;
    private final RetrievalChunkRepository chunkRepository;
    private final EmbeddingBatchWriter batchWriter;
    private final ProcessingJobService jobService;
    private final EmbeddingJobExecutor embedExecutor;
    private final RelationshipAgentProperties properties;
    private final ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider;
    private final String configuredModel;

    public EmbeddingService(ChatFileService chatFileService,
                            RetrievalChunkRepository chunkRepository,
                            EmbeddingBatchWriter batchWriter,
                            ProcessingJobService jobService,
                            EmbeddingJobExecutor embedExecutor,
                            RelationshipAgentProperties properties,
                            ObjectProvider<org.springframework.ai.embedding.EmbeddingModel> embeddingModelProvider,
                            @Value("${spring.ai.openai.embedding.options.model:}") String configuredModel) {
        this.chatFileService = chatFileService;
        this.chunkRepository = chunkRepository;
        this.batchWriter = batchWriter;
        this.jobService = jobService;
        this.embedExecutor = embedExecutor;
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
        this.configuredModel = configuredModel;
    }

    /**
     * 触发 EMBED 任务（异步）。
     *
     * @return jobId
     */
    public String startEmbed(String chatFileId) {
        ChatFile chatFile = chatFileService.getById(chatFileId);
        if (!List.of(ChatFile.STATUS_CHUNKED, ChatFile.STATUS_READY).contains(chatFile.getStatus())) {
            throw new BizException(ErrorCode.CHAT_FILE_NOT_READY);
        }

        // 容错：模型未配置时明确报错（0.2 硬要求）
        var model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            throw new BizException(ErrorCode.EMBEDDING_NOT_CONFIGURED,
                    "embedding 未启用：请配置 API key 并将 spring.ai.openai.embedding.enabled 置 true");
        }

        // 配置漂移快速失败
        if (!properties.embedding().model().equals(configuredModel)) {
            throw new SystemException(String.format(
                    "配置冲突：ra.embedding.model=%s 但 spring.ai.openai.embedding.options.model=%s",
                    properties.embedding().model(), configuredModel));
        }

        String inputHash = ProcessingJobService.hashInput(
                chatFile.getSourceSha256(), "embed-v2", properties.embedding().model());
        ProcessingJob job = jobService.createOrGet(chatFileId, ProcessingJob.TYPE_EMBED, inputHash);
        if (job == null) throw new BizException(ErrorCode.IDEMPOTENT_SKIP);
        if (!jobService.tryTakeover(job.getId())) throw new BizException(ErrorCode.JOB_ALREADY_RUNNING);
        chatFileService.updateStatus(chatFileId, ChatFile.STATUS_EMBEDDING);
        embedExecutor.submit("embed-" + chatFileId, () -> doEmbed(chatFile, job, model));
        return job.getId();
    }

    private void doEmbed(ChatFile chatFile, ProcessingJob job,
                         org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        try {
            String model = properties.embedding().model();
            int dimensions = properties.embedding().dimensions();
            int batchSize = properties.embedding().batchSize();

            long total = chunkRepository.countPendingEmbed(chatFile.getId(), model);
            jobService.updateProgress(job.getId(), 0, (int) total);
            int done = 0;
            long startMs = System.currentTimeMillis();

            while (true) {
                List<RetrievalChunk> batch = chunkRepository.selectPendingEmbed(
                        chatFile.getId(), model, batchSize);
                if (batch.isEmpty()) break;

                List<String> texts = batch.stream().map(RetrievalChunk::getRetrievalText).toList();
                float[][] vectors = embedWithRetry(embeddingModel, texts);

                // 维度校验
                if (vectors.length > 0 && vectors[0].length != dimensions) {
                    throw new SystemException(String.format(
                            "Embedding 维度不匹配：ra.embedding.dimensions=%d，API 返回 %d",
                            dimensions, vectors[0].length));
                }

                batchWriter.writeBatch(batch, vectors, model);
                done += batch.size();
                if (!jobService.isLeaseActive(job.getId())) return;
                jobService.updateProgress(job.getId(), done, (int) total);
                jobService.heartbeat(job.getId()); // 0.5 决策 5：防止长任务误判僵死
            }

            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("Embed completed: chatFileId={}, model={}, chunks={}, elapsedMs={}",
                    chatFile.getId(), model, done, elapsedMs);

            if (!jobService.isLeaseActive(job.getId())) return;
            chatFileService.updateStatus(chatFile.getId(), ChatFile.STATUS_READY);
            jobService.markSuccess(job.getId());
        } catch (Exception e) {
            log.error("Embed failed: chatFileId={}", chatFile.getId(), e);
            if (!jobService.isLeaseActive(job.getId())) return;
            jobService.markFailed(job.getId(), e.getMessage());
            chatFileService.updateError(chatFile.getId(), e.getMessage());
        }
    }

    /**
     * 批量 embedding 调用 + 线性退避重试（M3.2）
     */
    private float[][] embedWithRetry(org.springframework.ai.embedding.EmbeddingModel model,
                                     List<String> texts) {
        int maxRetries = properties.embedding().maxRetries();
        long backoffMs = properties.embedding().backoffMs();
        for (int attempt = 1; ; attempt++) {
            try {
                List<float[]> result = model.embed(texts);
                return result.toArray(new float[0][]);
            } catch (Exception e) {
                if (attempt > maxRetries) throw e;
                long delay = backoffMs * attempt;
                log.warn("Embed API failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt, maxRetries, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }
}
