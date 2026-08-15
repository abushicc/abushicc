package com.example.relationshipagent.analysis.controller;

import com.example.relationshipagent.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
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

/** HTTP contract test: reports are scoped to their chat file and never leak manifest/user-context fields. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AnalysisReportControllerIT {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg17").withDatabaseName("analysis_controller_test").withUsername("postgres").withPassword("postgres");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.url", postgres::getJdbcUrl); r.add("spring.flyway.user", postgres::getUsername); r.add("spring.flyway.password", postgres::getPassword);
        r.add("spring.ai.openai.embedding.enabled", () -> "false"); r.add("ra.analysis.enabled", () -> "false");
    }
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    private String chatFileId, reportId;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM analysis_claim_evidence"); jdbc.update("DELETE FROM analysis_claim"); jdbc.update("DELETE FROM analysis_report"); jdbc.update("DELETE FROM chat_file");
        chatFileId=UUID.randomUUID().toString(); reportId=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_file(id,file_name,source_sha256,source_format,file_path,encoding,source_timezone,parser_version,status) VALUES (?,?,?,?,?,?,?,?,?)",chatFileId,"a.csv","sha","CSV","/tmp/a.csv","UTF-8","Asia/Shanghai","v1","READY");
        jdbc.update("INSERT INTO analysis_report(id,chat_file_id,report_type,title,coverage_note,status,input_hash,user_context_json,evidence_manifest_json,created_at) VALUES (?,?,?,?,?,'SUCCESS',?,CAST(? AS jsonb),CAST(? AS jsonb),NOW())",reportId,chatFileId,"FULL","title","coverage","0".repeat(64),"{\"private\":true}","{\"private\":true}");
    }
    @Test void shouldReturnSafeDetailAndRejectCrossFileLookup() {
        ResponseEntity<ApiResponse<Map>> detail=http.exchange(url("/"+reportId),HttpMethod.GET,null,new ParameterizedTypeReference<>(){});
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(detail.getBody().data()).doesNotContainKeys("userContextJson","evidenceManifestJson","errorMessage");
        ResponseEntity<ApiResponse<Map>> wrong=http.exchange("http://localhost:"+port+"/api/chat-files/"+UUID.randomUUID()+"/analysis-reports/"+reportId,HttpMethod.GET,null,new ParameterizedTypeReference<>(){});
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    private String url(String suffix) { return "http://localhost:"+port+"/api/chat-files/"+chatFileId+"/analysis-reports"+suffix; }
}
