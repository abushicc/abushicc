package com.example.relationshipagent.analysis.client;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 3 的 OpenAI Responses API 调用方。
 *
 * <p>Spring AI 1.1.8 的 OpenAI ChatModel 走 Chat Completions，不能用于要求
 * {@code /v1/responses} 的 provider。该客户端与 DashScope embedding 完全隔离。
 */
public class ResponsesApiClient {
    private static final Logger log = LoggerFactory.getLogger(ResponsesApiClient.class);

    private final RestClient restClient;
    private final RelationshipAgentProperties.Analysis properties;
    private final ObjectMapper objectMapper;

    public ResponsesApiClient(RestClient restClient,
                              RelationshipAgentProperties.Analysis properties,
                              ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求严格 JSON Schema 输出。返回的 text 仍是不可信的模型产物，必须在 M6 的
     * AnalysisDraftValidator 中校验后才能持久化为正式报告。
     */
    public ResponsesResult generateJson(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
        return generateJson(developerPrompt, userPrompt, jsonSchema, properties.maxOutputTokens());
    }

    /**
     * Callers with a smaller, bounded artifact (Memory/Persona) must not inherit report-sized output budgets.
     */
    public ResponsesResult generateJson(String developerPrompt, String userPrompt, JsonNode jsonSchema, int maxOutputTokens) {
        return generateJson(developerPrompt, userPrompt, jsonSchema,
                new ResponsesOptions(properties.model(), properties.reasoningEffort(), properties.store(), maxOutputTokens, "analysis_draft"));
    }

    /**
     * Reuses the transport while allowing a bounded Agent to supply its own model options and schema name.
     */
    public ResponsesResult generateJson(String developerPrompt, String userPrompt, JsonNode jsonSchema,
                                        ResponsesOptions options) {
        // 传输层只负责请求、重试和提取 output_text；业务层必须继续做 schema/证据校验。
        if (options == null || options.maxOutputTokens() <= 0)
            throw new IllegalArgumentException("Responses API maxOutputTokens must be positive");
        if (options.model() == null || options.model().isBlank() || options.reasoningEffort() == null || options.reasoningEffort().isBlank()) {
            throw new IllegalArgumentException("Responses API model and reasoningEffort are required");
        }
        Map<String, Object> request = buildRequest(developerPrompt, userPrompt, jsonSchema, options);
        int attempts = Math.max(1, properties.maxRetries() + 1);
        long startedAt = System.nanoTime();
        log.info("Responses API started: schema={}, model={}, reasoning={}, maxOutputTokens={}, maxAttempts={}",
                options.schemaName(), options.model(), options.reasoningEffort(), options.maxOutputTokens(), attempts);
        // 只对传输异常、429 和 5xx 重试；4xx 参数错误直接失败，避免无意义地重复请求。
        for (int attempt = 1; attempt <= attempts; attempt++)
            try {
                JsonNode response = restClient.post()
                        .uri("/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new ResponsesApiException("Responses API returned an empty body");
                }
                String outputText = extractOutputText(response);
                if (outputText.isBlank()) {
                    throw new ResponsesApiException("Responses API returned no output_text");
                }
                JsonNode usage = response.path("usage").isMissingNode()
                        ? objectMapper.createObjectNode() : response.path("usage");
                log.info("Responses API completed: schema={}, responseId={}, model={}, attempt={}, elapsedMs={}, inputTokens={}, outputTokens={}",
                        options.schemaName(), response.path("id").asText("-"), response.path("model").asText(options.model()),
                        attempt, elapsedMillis(startedAt), usage.path("input_tokens").asInt(0),
                        usage.path("output_tokens").asInt(0));
                return new ResponsesResult(
                        response.path("id").asText(""),
                        response.path("model").asText(options.model()),
                        outputText,
                        usage);
            } catch (RestClientException e) {
                String failure = safeFailure(e);
                if (attempt == attempts || !retryable(e)) {
                    log.error("Responses API failed: schema={}, model={}, attempt={}, elapsedMs={}, reason={}",
                            options.schemaName(), options.model(), attempt, elapsedMillis(startedAt), failure);
                    throw new ResponsesApiException(failure, e);
                }
                log.warn("Responses API retrying: schema={}, model={}, attempt={}/{}, backoffMs={}, reason={}",
                        options.schemaName(), options.model(), attempt, attempts,
                        Math.max(0L, properties.backoffMs()) * attempt, failure);
                backoff(attempt, e);
            }
        throw new ResponsesApiException("Responses API request exhausted retries");
    }

    private static String safeFailure(RestClientException e) {
        if (e instanceof RestClientResponseException response)
            return "Responses API HTTP " + response.getStatusCode().value();
        if (e instanceof ResourceAccessException) return "Responses API network/read timeout";
        return "Responses API client failure: " + e.getClass().getSimpleName();
    }

    private static boolean retryable(RestClientException e) {
        if (e instanceof RestClientResponseException response)
            return response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError();
        // RestClient 还会用基类包装连接重置、截断响应和消息转换失败；它们没有
        // 可判定为调用方错误的 HTTP 状态，按同一有界策略重试更适合长批处理。
        return true;
    }

    private void backoff(int attempt, Exception source) {
        try {
            Thread.sleep(Math.max(0L, properties.backoffMs()) * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponsesApiException("Responses API retry interrupted", source);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    Map<String, Object> buildRequest(String developerPrompt, String userPrompt, JsonNode jsonSchema) {
        return buildRequest(developerPrompt, userPrompt, jsonSchema, properties.maxOutputTokens());
    }

    Map<String, Object> buildRequest(String developerPrompt, String userPrompt, JsonNode jsonSchema, int maxOutputTokens) {
        return buildRequest(developerPrompt, userPrompt, jsonSchema,
                new ResponsesOptions(properties.model(), properties.reasoningEffort(), properties.store(), maxOutputTokens, "analysis_draft"));
    }

    Map<String, Object> buildRequest(String developerPrompt, String userPrompt, JsonNode jsonSchema, ResponsesOptions options) {
        if (developerPrompt == null || developerPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("Responses API prompts must not be blank");
        }
        if (jsonSchema == null || jsonSchema.isMissingNode() || jsonSchema.isNull()) {
            throw new IllegalArgumentException("Responses API JSON schema is required");
        }

        List<Map<String, Object>> input = new ArrayList<>();
        input.add(Map.of("role", "developer", "content", developerPrompt));
        input.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", options.schemaName() == null || options.schemaName().isBlank() ? "structured_draft" : options.schemaName());
        format.put("strict", true);
        format.put("schema", jsonSchema);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", options.model());
        request.put("input", input);
        request.put("reasoning", Map.of("effort", options.reasoningEffort()));
        request.put("store", options.store());
        request.put("max_output_tokens", options.maxOutputTokens());
        request.put("text", Map.of("format", format));
        return request;
    }

    private static String extractOutputText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    text.append(content.path("text").asText());
                }
            }
        }
        return text.toString();
    }

    public record ResponsesResult(String responseId, String model, String outputText, JsonNode usage) {
    }

    public record ResponsesOptions(String model, String reasoningEffort, boolean store, int maxOutputTokens,
                                   String schemaName) {
    }

    public static class ResponsesApiException extends RuntimeException {
        public ResponsesApiException(String message) {
            super(message);
        }

        public ResponsesApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
