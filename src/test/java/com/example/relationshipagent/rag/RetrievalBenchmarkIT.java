package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.core.env.Environment;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索召回基准评测（M7.2）：读取 benchmark JSON → 每题调 RetrievalService → 判定命中 → 输出召回率。
 *
 * <p>通过标准：召回率 ≥ 80%（设计文档 14.5）。
 * <p>需要真实 embedding 数据 → 默认跳过（@Tag("benchmark") + EMBEDDING_API_KEY 环境变量）。
 * <p>queries 少于 20 题时只给警告不判失败（标注未完成）。
 *
 * <p>运行：{@code mvnw.cmd test -Dgroups=benchmark}
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
@DisplayName("检索召回基准评测")
class RetrievalBenchmarkIT {

    private static final boolean USE_EXTERNAL_DB = Boolean.parseBoolean(
            System.getenv().getOrDefault("BENCHMARK_USE_EXTERNAL_DB", "false"));

    private static final Logger log = LoggerFactory.getLogger(RetrievalBenchmarkIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int TOP_K = 5;
    /** 基准时间标注只允许小幅时区/会话切分误差，不应把邻近会话当成命中。 */
    private static final long WINDOW_MINUTES = 5;
    private static final int MIN_QUERIES = 20;

    static PostgreSQLContainer<?> postgres = USE_EXTERNAL_DB ? null
            : new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("relationship_agent_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL_DB) {
            String url = System.getenv().getOrDefault("BENCHMARK_DB_URL",
                    "jdbc:postgresql://localhost:5432/relationship_agent");
            String username = System.getenv().getOrDefault("BENCHMARK_DB_USERNAME", "postgres");
            String password = System.getenv().getOrDefault("BENCHMARK_DB_PASSWORD", "postgres");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> username);
            registry.add("spring.datasource.password", () -> password);
            registry.add("spring.flyway.url", () -> url);
            registry.add("spring.flyway.user", () -> username);
            registry.add("spring.flyway.password", () -> password);
        } else {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        }
    }

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ChatFileRepository chatFileRepository;

    @Autowired
    private Environment environment;

    private static BenchmarkFile benchmark;

    @BeforeAll
    static void loadBenchmark() throws Exception {
        if (!USE_EXTERNAL_DB) {
            postgres.start();
        }
        // 基准文件在仓库根目录 benchmark/ 下；mvn test 的工作目录即项目根目录
        String benchmarkFile = System.getenv().getOrDefault(
                "BENCHMARK_FILE", "benchmark/retrieval-benchmark.v2.json");
        File bmFile = new File(benchmarkFile);
        if (!bmFile.exists()) {
            log.warn("基准文件不存在: {}，跳过评测", bmFile.getAbsolutePath());
            benchmark = new BenchmarkFile(1, "", List.of());
        } else {
            benchmark = MAPPER.readValue(bmFile, BenchmarkFile.class);
            log.info("Loaded benchmark: version={}, queries={}",
                    benchmark.version(), benchmark.queries().size());
        }
    }

    @AfterAll
    static void stopContainer() {
        if (!USE_EXTERNAL_DB && postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("检索召回率 ≥ 80%")
    void shouldAchieveMinimumRecall() {
        if (benchmark.queries().isEmpty()) {
            log.warn("基准文件为空或无查询，跳过评测");
            return;
        }

        // 获取已 READY 的 chat_file
        List<ChatFile> files = chatFileRepository.selectList(null);
        ChatFile ready = files.stream()
                .filter(f -> ChatFile.STATUS_READY.equals(f.getStatus()))
                .findFirst().orElse(null);

        if (ready == null) {
            log.warn("没有 READY 状态的 chat_file，跳过评测（需先完成 EMBED 全量向量化）");
            return;
        }

        String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
        boolean externalEmbeddingConfigured = !"test-only-placeholder".equals(apiKey);
        log.info("Benchmark embedding config: externalEmbeddingConfigured={}, apiKeyLength={}",
                externalEmbeddingConfigured, apiKey.length());

        int total = 0, hit = 0, errors = 0;
        Map<String, int[]> categoryStats = new HashMap<>();
        int positiveTotal = 0, positiveHit = 0;
        int negativeTotal = 0, negativeAbstained = 0;
        for (var q : benchmark.queries()) {
            total++;
            SearchRequest req = new SearchRequest(q.query(), null, null, null, null, TOP_K, "score");
            try {
                RetrievalService.SearchResponse resp = retrievalService.search(ready.getId(), req);
                if (externalEmbeddingConfigured && "keywordOnly".equals(resp.mode())) {
                    throw new AssertionError("真实 embedding 已配置但检索降级为 keywordOnly，检查 API key、网络和模型配置");
                }
                boolean negative = Boolean.TRUE.equals(q.expectAbstain())
                        || q.expectedSessionStartTimes() == null
                        || q.expectedSessionStartTimes().isEmpty();
                boolean timeHit = judgeHit(resp.sessions(), q.expectedSessionStartTimes());
                boolean evidenceHit = negative || judgeEvidence(resp, q.expectedEvidenceTerms());
                boolean isHit = negative
                        ? !resp.answerable() && resp.sessions().isEmpty()
                        : resp.answerable() && timeHit && evidenceHit;
                if (isHit) hit++;
                int[] stats = categoryStats.computeIfAbsent(q.category(), k -> new int[2]);
                stats[0]++;
                if (isHit) stats[1]++;
                if (negative) {
                    negativeTotal++;
                    if (resp.sessions().isEmpty()) negativeAbstained++;
                } else {
                    positiveTotal++;
                    if (isHit) positiveHit++;
                }
                log.info("{}: query=\"{}\" → {} [time={}, evidence={}, mode={}] ({})",
                        q.id(), q.query(), isHit ? "HIT" : "MISS", timeHit, evidenceHit,
                        resp.mode(), resp.sessions().stream().map(s -> s.startTime).toList());
            } catch (Exception e) {
                errors++;
                log.error("{}: query=\"{}\" → ERROR: {}", q.id(), q.query(), e.getMessage());
            }
        }

        double recall = total > 0 ? (double) hit / total : 0.0;
        log.info("=== 召回率报告 === 命中: {}/{}, 召回率: {}", hit, total,
                String.format("%.1f%%", recall * 100));
        categoryStats.forEach((category, stats) ->
                log.info("category={} hit={}/{} recall={}", category, stats[1], stats[0],
                        stats[0] == 0 ? 0.0 : (double) stats[1] / stats[0]));
        if (negativeTotal > 0) {
            double fpr = (double) (negativeTotal - negativeAbstained) / negativeTotal;
            log.info("positive recall={}/{} ({}), negative abstain={}/{}, false-positive rate={}\n",
                    positiveHit, positiveTotal, positiveTotal == 0 ? 0.0 : (double) positiveHit / positiveTotal,
                    negativeAbstained, negativeTotal, fpr);
        }

        if (benchmark.queries().size() < MIN_QUERIES) {
            log.warn("标注未完成：当前 {} 题，需 ≥ {} 题。跳过召回率判定。",
                    benchmark.queries().size(), MIN_QUERIES);
        } else {
            assertThat(errors).as("评测不应有查询异常").isZero();
            assertThat(recall).as("总体命中率应 ≥ 80%").isGreaterThanOrEqualTo(0.80);
            if (benchmark.version() >= 2 && positiveTotal > 0) {
                assertThat((double) positiveHit / positiveTotal)
                        .as("正样本 Recall 应 ≥ 85%").isGreaterThanOrEqualTo(0.85);
                if (negativeTotal > 0) {
                    assertThat((double) (negativeTotal - negativeAbstained) / negativeTotal)
                            .as("负样本误报率应 ≤ 25%").isLessThanOrEqualTo(0.25);
                }
            }
        }
    }

    /**
     * 命中判定：返回的任一会话 start_time 落在任一期望时间 ±45min 窗口内。
     */
    private boolean judgeHit(List<RetrievalService.SessionHit> sessions,
                             List<String> expectedTimes) {
        if (expectedTimes == null || expectedTimes.isEmpty()) {
            return sessions.isEmpty();
        }
        for (var sh : sessions) {
            if (sh.startTime == null) continue;
            for (String exp : expectedTimes) {
                Instant expTime = Instant.from(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                                .withZone(ZONE).parse(exp));
                long diff = Math.abs(sh.startTime.getEpochSecond() - expTime.getEpochSecond());
                if (diff <= WINDOW_MINUTES * 60) return true;
            }
        }
        return false;
    }

    private boolean judgeEvidence(RetrievalService.SearchResponse response,
                                  List<String> evidenceTerms) {
        if (evidenceTerms == null || evidenceTerms.isEmpty()) return true;
        StringBuilder text = new StringBuilder();
        for (var session : response.sessions()) {
            if (session.summary != null) text.append(session.summary).append('\n');
            if (session.formattedText != null) text.append(session.formattedText).append('\n');
            if (session.hits != null) {
                for (var hit : session.hits) {
                    if (hit.retrievalText() != null) text.append(hit.retrievalText()).append('\n');
                }
            }
        }
        String haystack = text.toString().toLowerCase();
        return evidenceTerms.stream().anyMatch(term -> term != null
                && !term.isBlank() && haystack.contains(term.toLowerCase()));
    }

    // ---- DTO ----
    record BenchmarkFile(int version, String notes, List<QueryItem> queries) {}
    record QueryItem(String id, String category, String query,
                     List<String> expectedSessionStartTimes,
                     List<String> expectedEvidenceTerms,
                     Boolean expectAbstain,
                     String comment) {}
}
