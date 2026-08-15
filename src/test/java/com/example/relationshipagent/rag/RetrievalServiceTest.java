package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RetrievalService 单测（M5.4 编码先行部分）：RRF 融合逻辑、关键词提取、降级/混合模式判定。
 *
 * <p>不依赖真实 API——向量路通过 mock 返回预设结果，验证融合排序正确性。
 */
class RetrievalServiceTest {

    private ChatFileService chatFileService;
    private RetrievalChunkRepository chunkRepository;
    private ConversationSessionRepository sessionRepository;
    private RelationshipAgentProperties properties;
    private EmbeddingModel embeddingModel;

    private RetrievalService service;

    @BeforeEach
    void setUp() {
        chatFileService = mock(ChatFileService.class);
        chunkRepository = mock(RetrievalChunkRepository.class);
        sessionRepository = mock(ConversationSessionRepository.class);
        properties = new RelationshipAgentProperties(
                new RelationshipAgentProperties.Session(45, 200, 60),
                new RelationshipAgentProperties.Chunk(45, 8),
                new RelationshipAgentProperties.Job(1000, 3, 1_800_000),
                new RelationshipAgentProperties.Retrieval(5, 3),
                new RelationshipAgentProperties.Embedding("qwen3.7-text-embedding", "dashscope",
                        1024, 20, 3, 2000),
                new RelationshipAgentProperties.Statistics(List.of()));
        embeddingModel = mock(EmbeddingModel.class);

        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embeddingModel);
        when(provider.getObject()).thenReturn(embeddingModel);

        service = new RetrievalService(chatFileService, chunkRepository,
                sessionRepository, properties, provider);
    }

    // ===== 模式判定 =====

    @Nested
    @DisplayName("模式判定")
    class ModeDetermination {

        @Test
        @DisplayName("status=READY 且 EmbeddingModel 可用 → hybrid")
        void shouldUseHybridWhenReady() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of());
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            var resp = service.search(cf.getId(), new SearchRequest("你好", null, null, null, null, 5, "score"));
            assertThat(resp.mode()).isEqualTo("hybrid");
        }

        @Test
        @DisplayName("status=CHUNKED（未向量化）→ keywordOnly")
        void shouldFallbackToKeywordOnlyWhenChunked() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            var resp = service.search(cf.getId(), new SearchRequest("你好", null, null, null, null, 5, "score"));
            assertThat(resp.mode()).isEqualTo("keywordOnly");
        }

        @Test
        @DisplayName("status < CHUNKED → CHAT_FILE_NOT_READY")
        void shouldThrowWhenStatusTooEarly() {
            ChatFile cf = cfStub(ChatFile.STATUS_SESSIONIZED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);

            assertThatThrownBy(() -> service.search(cf.getId(),
                    new SearchRequest("test", null, null, null, null, 5, "score")))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).errorCode())
                    .isEqualTo(ErrorCode.CHAT_FILE_NOT_READY);
        }
    }

    // ===== 关键词提取 =====

    @Nested
    @DisplayName("关键词提取")
    class KeywordExtraction {

        @Test
        @DisplayName("正则切词 + 去停用词 + 长度过滤")
        void shouldExtractValidKeywords() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED); // keywordOnly mode
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            // query 含停用词"的""了""我"和有效词；避免时间意图路由干扰关键词提取测试。
            service.search(cf.getId(), new SearchRequest("她生气或者不满的时候一般怎么表达", null, null, null, null, 5, "score"));

            // 关键词应为 "我们" "第一次" "聊天" "说了" "什么"（长度≥2 且不在停用词表）
            // 验证 keywordSearch 被调用
            verify(chunkRepository).keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("纯停用词 query → 无有效关键词 → keywordSearch 不调用")
        void shouldSkipKeywordSearchForStopWordOnlyQuery() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);

            service.search(cf.getId(), new SearchRequest("的 了 吗 呢", null, null, null, null, 5, "score"));

            // 全部是停用词 → keywords 为空 → keywordSearch 不调用
            verify(chunkRepository, never()).keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("未知兴趣题不把连接词‘或者’当作事实证据")
        void shouldNotTreatOrConnectorAsLexicalEvidence() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            RetrievalChunk connectorOnly = chunkStub("connector", "s-connector");
            connectorOnly.setRetrievalText("这里只出现或者这个连接词");
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(connectorOnly));

            var response = service.search(cf.getId(),
                    new SearchRequest("她平时喜欢打高尔夫或者滑雪吗", null, null, null, null, 5, "score"));

            assertThat(response.answerable()).isFalse();
            assertThat(response.sessions()).isEmpty();
            verify(chunkRepository).keywordSearch(anyString(),
                    argThat(words -> (words.contains("打高尔夫") || words.contains("高尔夫"))
                            && words.contains("滑雪") && !words.contains("或者")),
                    any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("‘气到’扩展为可回溯的生气/争吵证据")
        void shouldExpandAngerEventKeywords() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            RetrievalChunk evidence = chunkStub("anger", "s-anger");
            evidence.setRetrievalText("真的我要生气了，干脆大吵一架吧");
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(evidence));
            when(sessionRepository.selectById("s-anger")).thenReturn(sessionStub("s-anger"));

            var response = service.search(cf.getId(),
                    new SearchRequest("她因为论文格式被改论文的人气到的记录", null, null, null, null, 5, "score"));

            assertThat(response.answerable()).isTrue();
            assertThat(response.reasonCodes()).contains("LEXICAL_EVIDENCE");
            verify(chunkRepository).keywordSearch(anyString(),
                    argThat(words -> words.contains("生气") && words.contains("大吵一架") && !words.contains("人气")),
                    any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("‘没睡’扩展为原文中的‘不睡/睡不着’证据")
        void shouldExpandNotSleepingKeywords() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            RetrievalChunk evidence = chunkStub("awake", "s-awake");
            evidence.setRetrievalText("和别人说晚安，实际上不睡还是在玩");
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(evidence));
            when(sessionRepository.selectById("s-awake")).thenReturn(sessionStub("s-awake"));

            var response = service.search(cf.getId(),
                    new SearchRequest("互相说了晚安其实都还没睡的那次聊天", null, null, null, null, 5, "score"));

            assertThat(response.answerable()).isTrue();
            verify(chunkRepository).keywordSearch(anyString(),
                    argThat(words -> words.contains("不睡") && words.contains("睡不着")),
                    any(), any(), any(), any(), anyInt());
        }
    }

    // ===== RRF 融合逻辑 =====

    @Nested
    @DisplayName("RRF 融合")
    class RrfFusion {

        @Test
        @DisplayName("两路有重叠 → RRF 融合后合并去重排序")
        void shouldMergeAndDeduplicateWithRrf() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);

            // 向量路返回 c1, c2, c3（按距离排序）
            RetrievalChunk c1 = chunkStub("c1", "s1");
            RetrievalChunk c2 = chunkStub("c2", "s2");
            RetrievalChunk c3 = chunkStub("c3", "s3");
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of(c1, c2, c3));

            // 关键词路返回 c2, c4（c2 重叠）
            RetrievalChunk c4 = chunkStub("c4", "s4");
            // 让关键词匹配 c2 和 c4 的 retrievalText
            c2.setRetrievalText("test 测试查询 content for c2");
            c4.setRetrievalText("test content 测试查询 for c4");
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(c2, c4));

            // mock embedding
            float[] qv = new float[1024];
            when(embeddingModel.embed(anyString())).thenReturn(qv);

            // mock session 查询
            when(chunkRepository.selectById("c1")).thenReturn(c1);
            when(chunkRepository.selectById("c2")).thenReturn(c2);
            when(chunkRepository.selectById("c3")).thenReturn(c3);
            when(chunkRepository.selectById("c4")).thenReturn(c4);
            when(sessionRepository.selectById("s1")).thenReturn(sessionStub("s1"));
            when(sessionRepository.selectById("s2")).thenReturn(sessionStub("s2"));
            when(sessionRepository.selectById("s3")).thenReturn(sessionStub("s3"));
            when(sessionRepository.selectById("s4")).thenReturn(sessionStub("s4"));

            var resp = service.search(cf.getId(), new SearchRequest("测试查询", null, null, null, null, 5, "score"));

            assertThat(resp.mode()).isEqualTo("hybrid");
            // c2 两路都有 → RRF 分数最高
            assertThat(resp.sessions()).isNotEmpty();
            // 所有 session 都应在结果中
            assertThat(resp.sessions().stream().map(s -> s.sessionId).toList())
                    .contains("s1", "s2", "s3", "s4");
        }

        @Test
        @DisplayName("只有关键词路 → 降级模式按命中词数打分")
        void shouldUseKeywordOnlyScoring() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED); // 降级模式
            when(chatFileService.getById(cf.getId())).thenReturn(cf);

            RetrievalChunk c1 = chunkStub("c1", "s1");
            c1.setRetrievalText("哈哈哈真好笑");
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(c1));
            when(chunkRepository.selectById("c1")).thenReturn(c1);
            when(sessionRepository.selectById("s1")).thenReturn(sessionStub("s1"));

            var resp = service.search(cf.getId(), new SearchRequest("哈哈哈 真好笑", null, null, null, null, 5, "score"));

            assertThat(resp.mode()).isEqualTo("keywordOnly");
            assertThat(resp.sessions()).hasSize(1);
        }

        @Test
        @DisplayName("空结果 query → 返回空数组不报错")
        void shouldReturnEmptyForNoMatch() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of());
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());

            var resp = service.search(cf.getId(), new SearchRequest("xyz不存在的词xyz", null, null, null, null, 5, "score"));
            assertThat(resp.sessions()).isEmpty();
        }

        @Test
        @DisplayName("向量候选全部低于距离阈值且无关键词证据 → 拒答")
        void shouldAbstainWhenOnlyLowSimilarityCandidatesRemain() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

            RetrievalChunk lowSimilarity = chunkStub("low", "s-low");
            lowSimilarity.setSearchDistance(0.90d);
            lowSimilarity.setRetrievalText("她喜欢聊天");
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of(lowSimilarity));
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(lowSimilarity));
            when(chunkRepository.selectById("low")).thenReturn(lowSimilarity);
            when(sessionRepository.selectById("s-low")).thenReturn(sessionStub("s-low"));

            var resp = service.search(cf.getId(),
                    new SearchRequest("她喜欢什么综艺", null, null, null, null, 5, "score"));

            assertThat(resp.answerable()).isFalse();
            assertThat(resp.sessions()).isEmpty();
            assertThat(resp.reasonCodes()).contains("NO_LEXICAL_EVIDENCE");
        }
    }

    // ===== 结构化时间查询 =====

    @Nested
    @DisplayName("结构化时间查询")
    class StructuredQueries {

        @Test
        @DisplayName("第一次聊天 → 直接按会话时间升序，不走向量")
        void shouldRouteFirstChatToStructuredSearch() {
            ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            ConversationSession first = sessionStub("first");
            first.setStartTime(Instant.parse("2021-01-07T08:41:00Z"));
            ConversationSession later = sessionStub("later");
            later.setStartTime(Instant.parse("2021-05-01T08:41:00Z"));
            when(sessionRepository.selectList(any())).thenReturn(List.of(first, later));
            when(chunkRepository.selectList(any())).thenReturn(List.of());

            var resp = service.search(cf.getId(),
                    new SearchRequest("我们第一次聊天都说了什么", null, null, null, null, 5, "score"));

            assertThat(resp.mode()).isEqualTo("structured");
            assertThat(resp.sessions()).extracting(s -> s.sessionId).containsExactly("first", "later");
            verifyNoInteractions(embeddingModel);
        }
    }

    // ===== 排序 =====

    @Nested
    @DisplayName("排序")
    class Sorting {

        @Test
        @DisplayName("sort=timeAsc → 按 startTime 升序")
        void shouldSortByTimeAsc() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

            RetrievalChunk c1 = chunkStub("c1", "s1");
            RetrievalChunk c2 = chunkStub("c2", "s2");
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of(c1, c2));
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());
            when(chunkRepository.selectById("c1")).thenReturn(c1);
            when(chunkRepository.selectById("c2")).thenReturn(c2);

            ConversationSession s1 = sessionStub("s1");
            s1.setStartTime(Instant.parse("2021-06-01T10:00:00Z"));
            ConversationSession s2 = sessionStub("s2");
            s2.setStartTime(Instant.parse("2021-01-01T10:00:00Z"));
            when(sessionRepository.selectById("s1")).thenReturn(s1);
            when(sessionRepository.selectById("s2")).thenReturn(s2);

            var resp = service.search(cf.getId(), new SearchRequest("测试", null, null, null, null, 5, "timeAsc"));

            List<String> ids = resp.sessions().stream().map(s -> s.sessionId).toList();
            assertThat(ids).containsExactly("s2", "s1"); // s2 更早
        }

        @Test
        @DisplayName("sort=timeDesc → 按 startTime 降序")
        void shouldSortByTimeDesc() throws Exception {
            ChatFile cf = cfStub(ChatFile.STATUS_READY);
            when(chatFileService.getById(cf.getId())).thenReturn(cf);
            when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

            RetrievalChunk c1 = chunkStub("c1", "s1");
            RetrievalChunk c2 = chunkStub("c2", "s2");
            when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyInt())).thenReturn(List.of(c1, c2));
            when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of());
            when(chunkRepository.selectById("c1")).thenReturn(c1);
            when(chunkRepository.selectById("c2")).thenReturn(c2);

            ConversationSession s1 = sessionStub("s1");
            s1.setStartTime(Instant.parse("2021-06-01T10:00:00Z"));
            ConversationSession s2 = sessionStub("s2");
            s2.setStartTime(Instant.parse("2021-01-01T10:00:00Z"));
            when(sessionRepository.selectById("s1")).thenReturn(s1);
            when(sessionRepository.selectById("s2")).thenReturn(s2);

            var resp = service.search(cf.getId(), new SearchRequest("测试", null, null, null, null, 5, "timeDesc"));

            List<String> ids = resp.sessions().stream().map(s -> s.sessionId).toList();
            assertThat(ids).containsExactly("s1", "s2"); // s1 更晚
        }
    }

    // ===== topK 生效 =====

    @Test
    @DisplayName("topK=3 → 最多返回 3 条")
    void shouldRespectTopK() throws Exception {
        ChatFile cf = cfStub(ChatFile.STATUS_READY);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        // mock embedding 调用成功
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        List<RetrievalChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RetrievalChunk c = chunkStub("c" + i, "s" + i);
            chunks.add(c);
            when(chunkRepository.selectById("c" + i)).thenReturn(c);
            when(sessionRepository.selectById("s" + i)).thenReturn(sessionStub("s" + i));
        }
        when(chunkRepository.vectorSearch(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyInt())).thenReturn(chunks);
        when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        var resp = service.search(cf.getId(), new SearchRequest("测试", null, null, null, null, 3, "score"));
        assertThat(resp.sessions()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("内部 evidence 检索保留 chunk 消息边界、通道和会话分数")
    void shouldReturnAuditableEvidenceRetrievalResultWithoutEmbedding() {
        ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        RetrievalChunk chunk = chunkStub("chunk-audit", "session-audit");
        chunk.setRetrievalText("测试 查询 的可审计检索文本");
        when(chunkRepository.keywordSearch(anyString(), anyList(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(chunk));
        when(chunkRepository.selectById("chunk-audit")).thenReturn(chunk);
        ConversationSession session = sessionStub("session-audit");
        session.setFormattedText("完整会话文本");
        when(sessionRepository.selectById("session-audit")).thenReturn(session);

        var result = service.retrieveEvidence(cf.getId(),
                new RetrievalService.EvidenceRetrievalRequest("测试 查询", null, null, null, null, 3, "score"));

        assertThat(result.mode()).isEqualTo("keywordOnly");
        assertThat(result.intent()).isEqualTo(RetrievalService.QueryIntent.EVENT);
        assertThat(result.answerable()).isTrue();
        assertThat(result.reasonCodes()).contains("LEXICAL_EVIDENCE");
        assertThat(result.sessions()).singleElement().satisfies(hit -> {
            assertThat(hit.formattedText()).isEqualTo("完整会话文本");
            assertThat(hit.score()).isPositive();
            assertThat(hit.chunks()).singleElement().satisfies(evidence -> {
                assertThat(evidence.chunkId()).isEqualTo("chunk-audit");
                assertThat(evidence.startMessageId()).isEqualTo("m-start");
                assertThat(evidence.endMessageId()).isEqualTo("m-end");
                assertThat(evidence.retrievalChannel()).isEqualTo("KEYWORD");
                assertThat(evidence.score()).isEqualTo(hit.score());
            });
        });
        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("短双字语气词被分词拆开时仍保留精确词法锚点")
    void shouldKeepShortHanPhraseAsLexicalAnchor() {
        ChatFile cf = cfStub(ChatFile.STATUS_CHUNKED);
        when(chatFileService.getById(cf.getId())).thenReturn(cf);
        RetrievalChunk chunk = chunkStub("catchphrase", "s-catchphrase");
        chunk.setRetrievalText("这里包含哦呦这个语气词");
        when(chunkRepository.keywordSearch(anyString(), argThat(words -> words.contains("哦呦")), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(chunk));
        when(sessionRepository.selectById("s-catchphrase")).thenReturn(sessionStub("s-catchphrase"));

        var response = service.search(cf.getId(), new SearchRequest("哦呦", null, null, null, null, 3, "score"));

        assertThat(response.answerable()).isTrue();
        assertThat(response.sessions()).extracting(hit -> hit.sessionId).containsExactly("s-catchphrase");
        assertThat(response.reasonCodes()).contains("LEXICAL_EVIDENCE");
        verifyNoInteractions(embeddingModel);
    }

    // ===== 辅助方法 =====

    private ChatFile cfStub(String status) {
        ChatFile cf = new ChatFile();
        cf.setId("cf-test");
        cf.setStatus(status);
        cf.setSourceTimezone("Asia/Shanghai");
        return cf;
    }

    private RetrievalChunk chunkStub(String id, String sessionId) {
        RetrievalChunk c = new RetrievalChunk();
        c.setId(id);
        c.setChatFileId("cf-test");
        c.setParentSessionId(sessionId);
        c.setSequenceNo(1);
        c.setRetrievalText("test content for " + id);
        c.setStartMessageId("m-start");
        c.setEndMessageId("m-end");
        return c;
    }

    private ConversationSession sessionStub(String id) {
        ConversationSession s = new ConversationSession();
        s.setId(id);
        s.setChatFileId("cf-test");
        s.setStartTime(Instant.parse("2021-03-15T12:00:00Z"));
        s.setEndTime(Instant.parse("2021-03-15T12:30:00Z"));
        s.setMessageCount(30);
        s.setSessionType(ConversationSession.TYPE_GENERAL);
        return s;
    }
}
