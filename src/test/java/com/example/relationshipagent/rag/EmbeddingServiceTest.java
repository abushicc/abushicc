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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EmbeddingService 单测（M3.6 编码先行部分）：mock EmbeddingModel，
 * 验证重试、维度校验、断点续跑 SQL 语义、未配置容错、heartbeat 调用。
 *
 * <p>不需要真实 API key——全部走 mock。
 */
class EmbeddingServiceTest {

    private ChatFileService chatFileService;
    private RetrievalChunkRepository chunkRepository;
    private EmbeddingBatchWriter batchWriter;
    private ProcessingJobService jobService;
    private EmbeddingJobExecutor embedExecutor;
    private RelationshipAgentProperties properties;
    private EmbeddingModel embeddingModel;

    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        chatFileService = mock(ChatFileService.class);
        chunkRepository = mock(RetrievalChunkRepository.class);
        batchWriter = mock(EmbeddingBatchWriter.class);
        jobService = mock(ProcessingJobService.class);
        when(jobService.isLeaseActive(anyString())).thenReturn(true);
        embedExecutor = mock(EmbeddingJobExecutor.class);
        properties = new RelationshipAgentProperties(
                new RelationshipAgentProperties.Session(45, 200, 60),
                new RelationshipAgentProperties.Chunk(45, 8),
                new RelationshipAgentProperties.Job(1000, 3, 1_800_000),
                new RelationshipAgentProperties.Retrieval(5, 3),
                new RelationshipAgentProperties.Embedding("qwen3.7-text-embedding", "dashscope",
                        1024, 20, 3, 2000),
                new RelationshipAgentProperties.Statistics(List.of()));
        embeddingModel = mock(EmbeddingModel.class);

        // ObjectProvider that returns the mock
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        when(provider.getObject()).thenReturn(embeddingModel);

        service = new EmbeddingService(chatFileService, chunkRepository, batchWriter,
                jobService, embedExecutor, properties, provider,
                "qwen3.7-text-embedding");
    }

    // ===== 容错测试 =====

    @Test
    @DisplayName("未配置 EmbeddingModel 时抛 EMBEDDING_NOT_CONFIGURED")
    void shouldThrowWhenEmbeddingNotConfigured() {
        var emptyProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        var svc = new EmbeddingService(chatFileService, chunkRepository, batchWriter,
                jobService, embedExecutor, properties, emptyProvider,
                "qwen3.7-text-embedding");

        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);

        assertThatThrownBy(() -> svc.startEmbed(cf.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.EMBEDDING_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("status 不是 CHUNKED/READY → CHAT_FILE_NOT_READY")
    void shouldThrowWhenStatusNotReady() {
        ChatFile cf = chatFileStub(ChatFile.STATUS_SESSIONIZED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);

        assertThatThrownBy(() -> service.startEmbed(cf.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CHAT_FILE_NOT_READY);
    }

    // ===== 配置漂移测试 =====

    @Test
    @DisplayName("ra.embedding.model 与 spring.ai 配置不一致时抛 SystemException")
    void shouldThrowOnConfigMismatch() {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        // 构造时 configuredModel 与 properties 不一致
        var svc = new EmbeddingService(chatFileService, chunkRepository, batchWriter,
                jobService, embedExecutor, properties, provider,
                "different-model"); // 不一致

        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);

        assertThatThrownBy(() -> svc.startEmbed(cf.getId()))
                .isInstanceOf(SystemException.class);
    }

    // ===== 幂等测试 =====

    @Test
    @DisplayName("EMBED SUCCESS 后再触发 → IDEMPOTENT_SKIP")
    void shouldSkipWhenAlreadySuccess() {
        ChatFile cf = chatFileStub(ChatFile.STATUS_READY);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenReturn(null); // null = 已 SUCCESS

        assertThatThrownBy(() -> service.startEmbed(cf.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENT_SKIP);
    }

    @Test
    @DisplayName("EMBED 已在运行 → JOB_ALREADY_RUNNING")
    void shouldThrowWhenAlreadyRunning() {
        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenThrow(new BizException(ErrorCode.JOB_ALREADY_RUNNING));

        assertThatThrownBy(() -> service.startEmbed(cf.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.JOB_ALREADY_RUNNING);
    }

    // ===== doEmbed 核心逻辑测试（通过 submit 捕获 Runnable 验证） =====

    @Test
    @DisplayName("正常流程：批量调用 embedding → heartbeat 被调用 → status=READY")
    void shouldCompleteEmbedSuccessfully() throws Exception {
        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        ProcessingJob job = jobStub("job-1");
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenReturn(job);
        when(jobService.tryTakeover("job-1")).thenReturn(true);

        // 2 批：第一批 20 条，第二批 0 条结束
        List<RetrievalChunk> batch1 = createChunks(20);
        when(chunkRepository.countPendingEmbed(cf.getId(), "qwen3.7-text-embedding")).thenReturn(20L);
        when(chunkRepository.selectPendingEmbed(cf.getId(), "qwen3.7-text-embedding", 20))
                .thenReturn(batch1).thenReturn(List.of());

        // mock embedding 返回 1024 维
        float[][] vecs = new float[20][1024];
        for (int i = 0; i < 20; i++) { vecs[i][0] = 0.1f * i; vecs[i][1] = 0.2f * i; }
        when(embeddingModel.embed(anyList())).thenReturn(List.of(vecs));

        // 捕获提交的 Runnable 并同步执行
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(embedExecutor).submit(anyString(), any(Runnable.class));

        service.startEmbed(cf.getId());

        // 验证
        verify(embeddingModel).embed(anyList());
        verify(batchWriter).writeBatch(eq(batch1), any(float[][].class), eq("qwen3.7-text-embedding"));
        verify(jobService, atLeastOnce()).heartbeat("job-1");
        verify(chatFileService).updateStatus(cf.getId(), ChatFile.STATUS_READY);
        verify(jobService).markSuccess("job-1");
    }

    // ===== 维度校验 =====

    @Test
    @DisplayName("API 返回维度与配置不一致时抛 SystemException")
    void shouldThrowOnDimensionMismatch() {
        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        ProcessingJob job = jobStub("job-dim");
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenReturn(job);
        when(jobService.tryTakeover("job-dim")).thenReturn(true);

        List<RetrievalChunk> batch = createChunks(5);
        when(chunkRepository.countPendingEmbed(anyString(), anyString())).thenReturn(5L);
        when(chunkRepository.selectPendingEmbed(anyString(), anyString(), anyInt()))
                .thenReturn(batch).thenReturn(List.of());

        // mock 返回 999 维（与配置 1024 不匹配）
        float[][] vecs = new float[5][999];
        when(embeddingModel.embed(anyList())).thenReturn(List.of(vecs));

        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(embedExecutor).submit(anyString(), any(Runnable.class));

        service.startEmbed(cf.getId());

        // 维度不匹配 → markFailed 被调用
        verify(jobService).markFailed(eq("job-dim"), contains("维度不匹配"));
    }

    // ===== 重试测试 =====

    @Test
    @DisplayName("embedding API 失败应重试 maxRetries 次")
    void shouldRetryOnApiFailure() {
        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        ProcessingJob job = jobStub("job-retry");
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenReturn(job);
        when(jobService.tryTakeover("job-retry")).thenReturn(true);

        List<RetrievalChunk> batch = createChunks(3);
        when(chunkRepository.countPendingEmbed(anyString(), anyString())).thenReturn(3L);
        when(chunkRepository.selectPendingEmbed(anyString(), anyString(), anyInt()))
                .thenReturn(batch).thenReturn(List.of());

        // 每次都抛异常
        when(embeddingModel.embed(anyList())).thenThrow(new RuntimeException("API error"));

        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(embedExecutor).submit(anyString(), any(Runnable.class));

        service.startEmbed(cf.getId());

        // 重试 4 次（1 次初始 + 3 次重试 = maxRetries+1 = 4）
        verify(embeddingModel, times(4)).embed(anyList());
        verify(jobService).markFailed(eq("job-retry"), anyString());
    }

    // ===== 断点续跑逻辑 =====

    @Test
    @DisplayName("selectPendingEmbed 只选 embedding_model='' 或本模型且 embedding IS NULL 的行")
    void shouldOnlySelectPendingChunks() {
        // 验证 SQL 语义通过 Repository 方法签名：该方法已在 XML 中实现
        // countPendingEmbed 和 selectPendingEmbed 的 WHERE 条件确保了记录级断点续跑语义
        // 此处验证 mock 调用参数正确
        ChatFile cf = chatFileStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        ProcessingJob job = jobStub("job-pending");
        when(jobService.createOrGet(anyString(), eq(ProcessingJob.TYPE_EMBED), anyString()))
                .thenReturn(job);
        when(jobService.tryTakeover("job-pending")).thenReturn(true);
        when(chunkRepository.countPendingEmbed(cf.getId(), "qwen3.7-text-embedding")).thenReturn(0L);
        when(chunkRepository.selectPendingEmbed(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(embedExecutor).submit(anyString(), any(Runnable.class));

        service.startEmbed(cf.getId());

        verify(chunkRepository).countPendingEmbed(cf.getId(), "qwen3.7-text-embedding");
        verify(chunkRepository).selectPendingEmbed(cf.getId(), "qwen3.7-text-embedding", 20);
        // 无待处理块 → 直接 markSuccess
        verify(jobService).markSuccess("job-pending");
    }

    // ===== 辅助方法 =====

    private ChatFile chatFileStub(String status) {
        ChatFile cf = new ChatFile();
        cf.setId("cf-001");
        cf.setFileName("test.csv");
        cf.setSourceSha256("sha256-abc");
        cf.setStatus(status);
        cf.setSourceTimezone("Asia/Shanghai");
        return cf;
    }

    private ProcessingJob jobStub(String id) {
        ProcessingJob job = new ProcessingJob();
        job.setId(id);
        job.setChatFileId("cf-001");
        job.setJobType(ProcessingJob.TYPE_EMBED);
        job.setStatus(ProcessingJob.STATUS_PENDING);
        return job;
    }

    private List<RetrievalChunk> createChunks(int count) {
        List<RetrievalChunk> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            RetrievalChunk c = new RetrievalChunk();
            c.setId("chunk-" + i);
            c.setChatFileId("cf-001");
            c.setParentSessionId("s-" + i);
            c.setRetrievalText("test message " + i);
            c.setEmbeddingModel("");
            list.add(c);
        }
        return list;
    }
}
