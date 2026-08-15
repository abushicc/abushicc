package com.example.relationshipagent.companion.controller;

import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.companion.service.CompanionSessionService;
import com.example.relationshipagent.companion.service.CompanionTurnService;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V15 contract: safety replies are idempotent and session Persona never drifts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class CompanionControllerIT {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("companion_controller_test").withUsername("postgres").withPassword("postgres");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl); registry.add("spring.datasource.username", postgres::getUsername); registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl); registry.add("spring.flyway.user", postgres::getUsername); registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.ai.openai.embedding.enabled", () -> "false"); registry.add("ra.companion.enabled", () -> "true");
        registry.add("ra.analysis.base-url", () -> "http://localhost"); registry.add("ra.analysis.api-key", () -> "test-key");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanionSessionService sessions;
    @Autowired CompanionTurnService turns;
    private String chatFileId;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM chat_file"); chatFileId = UUID.randomUUID().toString(); String personaId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_file(id,file_name,source_sha256,source_format,file_path,encoding,source_timezone,parser_version,status) VALUES (?,?,?,?,?,?,?,?,?)", chatFileId, "a.csv", "sha", "CSV", "/tmp/a.csv", "UTF-8", "Asia/Shanghai", "v1", "READY");
        jdbc.update("INSERT INTO persona_profile(id,chat_file_id,target_person,version,profile_json,status,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',NOW(),NOW())", personaId, chatFileId, "她", "v1", "{}");
    }

    @Test void createsFixedPersonaSessionAndSafelyReplaysSameRequest() {
        ResponseEntity<ApiResponse<Map>> created = exchange("/sessions", HttpMethod.POST, Map.of("targetPerson", "她"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED); String sessionId = String.valueOf(created.getBody().data().get("id"));
        Map<String, String> message = Map.of("clientRequestId", "request-1", "content", "你是真人吗");
        ResponseEntity<ApiResponse<Map>> first = exchange("/sessions/" + sessionId + "/messages", HttpMethod.POST, message);
        ResponseEntity<ApiResponse<Map>> replay = exchange("/sessions/" + sessionId + "/messages", HttpMethod.POST, message);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().data().get("turnId")).isEqualTo(replay.getBody().data().get("turnId"));
        assertThat(first.getBody().data().get("historyStance")).isEqualTo("NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE chat_session_id=?", Integer.class, sessionId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT persona_profile_id FROM chat_session WHERE id=?", String.class, sessionId)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT status FROM companion_turn WHERE chat_session_id=?", String.class, sessionId)).isEqualTo("SUCCESS");
    }

    @Test void rejectsParallelTurnAndOnlyReclaimsStaleAttemptWithNewToken() {
        String sessionId = sessions.create(chatFileId, "她").getId();
        CompanionTurnService.Claim first = turns.claim(chatFileId, sessionId, "request-running", "今天怎么样");
        assertThatThrownBy(() -> turns.claim(chatFileId, sessionId, "request-other", "另一条消息"))
                .isInstanceOf(BizException.class).hasMessageContaining("已有消息正在生成");
        jdbc.update("UPDATE companion_turn SET started_at = NOW() - INTERVAL '10 minutes' WHERE id=?", first.turn().getId());
        CompanionTurnService.Claim reclaimed = turns.claim(chatFileId, sessionId, "request-running", "今天怎么样");
        assertThat(reclaimed.kind()).isEqualTo(CompanionTurnService.ClaimKind.RECLAIMED);
        assertThat(reclaimed.turn().getAttemptToken()).isNotEqualTo(first.turn().getAttemptToken());
        assertThat(reclaimed.turn().getAttemptCount()).isEqualTo(2);
    }

    @Test void rejectsFurtherTurnsWhenTheFixedPersonaIsSuperseded() {
        String sessionId = sessions.create(chatFileId, "她").getId();
        jdbc.update("UPDATE persona_profile SET status='SUPERSEDED' WHERE chat_file_id=?", chatFileId);
        ResponseEntity<ApiResponse<Map>> response = exchange("/sessions/" + sessionId + "/messages", HttpMethod.POST,
                Map.of("clientRequestId", "request-stale-persona", "content", "你好"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE chat_session_id=?", Integer.class, sessionId)).isZero();
    }

    private ResponseEntity<ApiResponse<Map>> exchange(String suffix, HttpMethod method, Object body) {
        return http.exchange("http://localhost:" + port + "/api/chat-files/" + chatFileId + "/companion" + suffix, method,
                body == null ? null : new HttpEntity<>(body), new ParameterizedTypeReference<>() {});
    }
}
