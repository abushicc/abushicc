package com.example.relationshipagent.analysis.config;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Creates the isolated client for the configured OpenAI-compatible Responses provider.
 */
@Configuration
@ConditionalOnExpression("${ra.analysis.enabled:false} or ${ra.memory.enabled:false} or ${ra.companion.enabled:false}")
public class AnalysisResponsesConfiguration {

    @Bean("analysisResponsesRestClient")
    RestClient analysisResponsesRestClient(RelationshipAgentProperties properties) {
        RelationshipAgentProperties.Analysis analysis = properties.analysis();
        validate(analysis);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(analysis.connectTimeoutMs());
        factory.setReadTimeout(analysis.readTimeoutMs());

        return RestClient.builder()
                .baseUrl(stripTrailingSlash(analysis.baseUrl()))
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + analysis.apiKey())
                .build();
    }

    @Bean
    ResponsesApiClient responsesApiClient(
            @Qualifier("analysisResponsesRestClient") RestClient restClient,
            RelationshipAgentProperties properties,
            ObjectMapper objectMapper) {
        return new ResponsesApiClient(restClient, properties.analysis(), objectMapper);
    }

    private static void validate(RelationshipAgentProperties.Analysis analysis) {
        if (!"responses".equals(analysis.wireApi())) {
            throw new IllegalStateException("ra.analysis.wire-api must be responses");
        }
        if (isBlank(analysis.baseUrl()) || isBlank(analysis.apiKey()) || isBlank(analysis.model())
                || isBlank(analysis.reasoningEffort())) {
            throw new IllegalStateException("ra.analysis requires base-url, api-key, model and reasoning-effort when enabled");
        }
        if (analysis.maxOutputTokens() <= 0 || analysis.connectTimeoutMs() <= 0 || analysis.readTimeoutMs() <= 0) {
            throw new IllegalStateException("ra.analysis timeout and max-output-tokens values must be positive");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
