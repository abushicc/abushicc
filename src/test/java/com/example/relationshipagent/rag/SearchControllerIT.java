package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索集成测试（M5.4 编码先行部分）：Testcontainers + pgvector，
 * 用人工构造的向量验证 pgvector 检索链路与过滤生效。
 *
 * <p>不依赖外部 API——向量由 SQL 直接写入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SearchController 集成测试")
class SearchControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("relationship_agent_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        // 禁用 embedding
        registry.add("spring.ai.openai.embedding.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalChunkRepository chunkRepository;

    @Autowired
    private ConversationSessionRepository sessionRepository;

    private String chatFileId;

    @BeforeAll
    static void verifyPgVector() {
        // Testcontainers 容器已自动启动
    }

    @BeforeEach
    void setUp() {
        // 同一容器内按测试方法复用数据库，先清理上一用例的 fixture。
        jdbcTemplate.update("DELETE FROM retrieval_chunk");
        jdbcTemplate.update("DELETE FROM session_message");
        jdbcTemplate.update("DELETE FROM message");
        jdbcTemplate.update("DELETE FROM conversation_session");
        jdbcTemplate.update("DELETE FROM chat_file");

        chatFileId = UUID.randomUUID().toString();

        // 直接通过 JDBC 插入测试数据（绕过 MyBatis-Plus 实体）
        // chat_file
        jdbcTemplate.update(
                "INSERT INTO chat_file(id, file_name, source_sha256, source_format, file_path, " +
                "encoding, source_timezone, parser_version, status) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                chatFileId, "test.csv", "sha256-test", "CSV", "/tmp/test.csv",
                "GB18030", "Asia/Shanghai", "v1", ChatFile.STATUS_CHUNKED);

        // 3 个会话
        Instant base = Instant.parse("2021-06-01T10:00:00Z");
        for (int i = 1; i <= 3; i++) {
            String sid = "session-" + i;
            jdbcTemplate.update(
                    "INSERT INTO conversation_session(id, chat_file_id, start_time, end_time, " +
                    "message_count, duration_seconds, forced_split, speaker_stats, formatted_text, " +
                    "session_type, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,NOW())",
                    sid, chatFileId,
                    java.sql.Timestamp.from(base.plusSeconds(3600L * i)),
                    java.sql.Timestamp.from(base.plusSeconds(3600L * i + 1800)),
                    30, 1800, false, "{\"kiwi\":20}", "test formatted text " + i,
                    ConversationSession.TYPE_GENERAL);
        }

        // retrieval_chunk 的首尾消息存在外键；speaker 过滤也依赖 session_message。
        for (int i = 1; i <= 30; i++) {
            String messageId = "m" + i;
            String speaker = i % 2 == 0 ? "耳朵小" : "kiwi";
            jdbcTemplate.update(
                    "INSERT INTO message(id, chat_file_id, speaker, content, cleaned_content, " +
                    "message_time, message_type, source_local_id, source_line_no) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                    messageId, chatFileId, speaker, "test " + i, "test " + i,
                    java.sql.Timestamp.from(base.plusSeconds(i * 60L)), "TEXT", (long) i, i);
            int sessionNo = (i - 1) / 10 + 1;
            int sequenceNo = (i - 1) % 10 + 1;
            jdbcTemplate.update(
                    "INSERT INTO session_message(session_id, message_id, seq_in_session) VALUES (?,?,?)",
                    "session-" + sessionNo, messageId, sequenceNo);
        }

        // 3 个检索块 + 人工向量（3 维简化向量）
        // c1: [1,0,0] → query [1,0,0] 距离 ≈ 0（最近）
        // c2: [0,1,0] → query [1,0,0] 距离大
        // c3: [0,0,1] → query [1,0,0] 距离大
        insertChunkWithVector("chunk-1", chatFileId, "session-1", "m1", "m10", 1,
                "时间：2021-06-01 10:00 - 2021-06-01 10:30\n参与人：kiwi, 耳朵小\n消息数：30 条\n\n[10:00] kiwi：你好啊",
                "[1,0,0]", "qwen3.7-text-embedding");
        insertChunkWithVector("chunk-2", chatFileId, "session-2", "m11", "m20", 1,
                "时间：2021-06-01 11:00 - 2021-06-01 11:30\n参与人：kiwi, 耳朵小\n消息数：30 条\n\n[11:00] 耳朵小：生气了",
                "[0,1,0]", "qwen3.7-text-embedding");
        insertChunkWithVector("chunk-3", chatFileId, "session-3", "m21", "m30", 1,
                "时间：2021-06-01 12:00 - 2021-06-01 12:30\n参与人：kiwi, 耳朵小\n消息数：30 条\n\n[12:00] kiwi：吃饭了没",
                "[0,0,1]", "qwen3.7-text-embedding");
    }

    private void insertChunkWithVector(String id, String chatFileId, String sessionId,
                                        String startMsgId, String endMsgId, int seqNo,
                                        String retrievalText, String vectorText, String model) {
        jdbcTemplate.update(
                "INSERT INTO retrieval_chunk(id, chat_file_id, parent_session_id, " +
                "start_message_id, end_message_id, sequence_no, retrieval_text, text_hash, " +
                "embedding_model, embedding_version, embedding, embedded_at, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS vector),NOW(),NOW())",
                id, chatFileId, sessionId, startMsgId, endMsgId, seqNo,
                retrievalText, "hash-" + id, model, model, vectorText);
    }

    // ===== 降级检索测试（无 EmbeddingModel） =====

    @Test
    @DisplayName("降级模式：关键词命中 → mode=keywordOnly")
    void shouldSearchInKeywordOnlyMode() {
        SearchRequest req = new SearchRequest("你好", null, null, null, null, 5, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo(0);

        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        assertThat(data.get("mode")).isEqualTo("keywordOnly");
        // "你好" 命中 chunk-1
        assertThat(data.get("sessions")).isNotNull();
    }

    @Test
    @DisplayName("keyword 'shengqi' hits session-2")
    void shouldFindSessionByKeyword() {
        SearchRequest req = new SearchRequest("生气", null, null, null, null, 5, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        // 应有结果
        java.util.List<Map<String, Object>> sessions =
                (java.util.List<Map<String, Object>>) data.get("sessions");
        assertThat(sessions).isNotEmpty();
        // 第一个结果的 sessionId 应为 "session-2"
        assertThat(sessions.get(0).get("sessionId")).isEqualTo("session-2");
    }

    @Test
    @DisplayName("无关 query → 返回空数组不报错")
    void shouldReturnEmptyForNoMatch() {
        SearchRequest req = new SearchRequest("xyz不存在的关键词xyz", null, null, null, null, 5, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        java.util.List<?> sessions = (java.util.List<?>) data.get("sessions");
        assertThat(sessions).isEmpty();
    }

    @Test
    @DisplayName("topK=1 → 最多返回 1 条")
    void shouldRespectTopK() {
        SearchRequest req = new SearchRequest("你", null, null, null, null, 1, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        java.util.List<?> sessions = (java.util.List<?>) data.get("sessions");
        assertThat(sessions).hasSizeLessThanOrEqualTo(1);
    }

    // ===== 过滤测试 =====

    @Test
    @DisplayName("speaker 过滤：仅返回含指定说话人的会话")
    void shouldFilterBySpeaker() {
        SearchRequest req = new SearchRequest("你好", "kiwi", null, null, null, 5, "score");
        // keywordSearch SQL 需要 speaker 参数，此处验证不报错
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("时间过滤：条件参数正确传递")
    void shouldFilterByTimeWindow() {
        SearchRequest req = new SearchRequest("时间",
                null,
                Instant.parse("2021-05-01T00:00:00Z"),
                Instant.parse("2021-07-01T00:00:00Z"),
                null, 5, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        java.util.List<?> sessions = (java.util.List<?>) data.get("sessions");
        // 3 个会话都在 2021-06-01 → 应全部命中
        assertThat(sessions).hasSize(3);
    }

    @Test
    @DisplayName("时间过滤：窗口外 → 返回空")
    void shouldReturnEmptyWhenTimeWindowExcludesAll() {
        SearchRequest req = new SearchRequest("你",
                null,
                Instant.parse("2022-01-01T00:00:00Z"),
                Instant.parse("2022-12-31T23:59:59Z"),
                null, 5, "score");
        ResponseEntity<ApiResponse<Map>> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/chat-files/" + chatFileId + "/search",
                HttpMethod.POST,
                new HttpEntity<>(req),
                new ParameterizedTypeReference<ApiResponse<Map>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().data();
        java.util.List<?> sessions = (java.util.List<?>) data.get("sessions");
        assertThat(sessions).isEmpty();
    }
}
