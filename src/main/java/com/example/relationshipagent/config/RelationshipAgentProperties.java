package com.example.relationshipagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 应用可调参数集中配置（设计文档 17.4）。
 *
 * <p>阶段 2 新增 embedding（向量模型）和 statistics（统计补全）配置块。
 */
@ConfigurationProperties(prefix = "ra")
public record RelationshipAgentProperties(
        Session session,
        Chunk chunk,
        Job job,
        Retrieval retrieval,
        Embedding embedding,
        Statistics statistics,
        Analysis analysis,
        Memory memory,
        Companion companion
) {

    @ConstructorBinding
    public RelationshipAgentProperties {
    }

    /**
     * Backward-compatible constructor used by existing tests and callers.
     */
    public RelationshipAgentProperties(Session session, Chunk chunk, Job job,
                                       Retrieval retrieval, Embedding embedding,
                                       Statistics statistics) {
        this(session, chunk, job, retrieval, embedding, statistics, Analysis.disabled(), Memory.disabled(), Companion.disabled());
    }

    public record Session(
            int gapThresholdMinutes,
            int maxMessagesPerSession,
            int displayMergeSeconds
    ) {
    }

    public record Chunk(
            int targetMessages,
            int overlapMessages
    ) {
    }

    public record Job(
            int importBatchSize,
            int maxRetry,
            long staleRunningMs
    ) {
    }

    public record Retrieval(
            int defaultTopK,
            int companionTopK,
            double maxVectorDistance,
            int longChatMinMessages
    ) {
        /**
         * Backward-compatible constructor used by existing tests and callers.
         */
        public Retrieval(int defaultTopK, int companionTopK) {
            this(defaultTopK, companionTopK, 0.55d, 30);
        }
    }

    /**
     * Embedding 模型配置（阶段 2；模型未定，全部走配置，代码不写死）
     */
    public record Embedding(
            String model,
            String provider,
            int dimensions,
            int batchSize,
            int maxRetries,
            long backoffMs
    ) {
    }

    /**
     * 统计补全配置（阶段 2 M6：文体指纹的口头禅词表，人工可调）
     */
    public record Statistics(
            java.util.List<String> catchphrases
    ) {
    }

    /**
     * 阶段 3 Analysis Agent 配置。
     *
     * <p>该调用方独立使用 OpenAI Responses API，不能复用 spring.ai.openai 的全局
     * base-url/api-key；后者继续服务 DashScope embedding。
     */
    public record Analysis(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String wireApi,
            String reasoningEffort,
            boolean store,
            int maxOutputTokens,
            int maxRetries,
            long backoffMs,
            int connectTimeoutMs,
            int readTimeoutMs,
            String analysisVersion,
            String promptVersion
    ) {
        public static Analysis disabled() {
            return new Analysis(false, "", "", "", "", "responses", "high", false,
                    12000, 2, 2000, 10000, 180000, "analysis-v1", "analysis-prompt-v1");
        }
    }

    /**
     * Stage 4 controls. Model/provider credentials remain in the isolated Analysis/Responses configuration.
     */
    public record Memory(
            boolean enabled,
            String defaultTargetPerson,
            int maxSessionsPerBatch,
            int maxInputChars,
            int maxOutputTokens,
            String extractorVersion,
            String aggregationVersion,
            String observationPromptVersion,
            String mergePromptVersion,
            String personaPromptVersion
    ) {
        public static Memory disabled() {
            return new Memory(false, "", 10, 80000, 8000,
                    "memory-extractor-v2", "memory-aggregator-v1", "memory-observation-prompt-v4",
                    "memory-merge-prompt-v1", "persona-prompt-v4");
        }
    }

    /**
     * Stage 5 online conversation controls. Credentials intentionally reuse the isolated Responses transport.
     */
    public record Companion(
            boolean enabled,
            String defaultTargetPerson,
            String model,
            String reasoningEffort,
            boolean store,
            int maxOutputTokens,
            int maxUserChars,
            int maxReplyChars,
            int maxInputChars,
            int recentHistoryTurns,
            int minHistoryTurns,
            int maxRetrievalQueries,
            int maxRetrievalChunks,
            int maxMemories,
            int refreshSearchAfterTurns,
            int sessionIdleMinutes,
            long staleTurnMs,
            String contextVersion,
            String promptVersion,
            String safetyVersion
    ) {
        public static Companion disabled() {
            return new Companion(false, "", "gpt-5.6-sol", "high", false,
                    800, 4000, 1200, 28000, 20, 4, 3, 5, 5, 5, 30,
                    360000, "companion-context-v1", "companion-prompt-v2", "companion-safety-v1");
        }
    }
}
