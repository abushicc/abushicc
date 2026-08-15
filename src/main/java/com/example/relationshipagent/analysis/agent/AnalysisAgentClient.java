package com.example.relationshipagent.analysis.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.analysis.evidence.EvidencePacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Model boundary only: no database access, validation, or report persistence belongs here.
 */
@Service
@ConditionalOnProperty(prefix = "ra.analysis", name = "enabled", havingValue = "true")
public class AnalysisAgentClient {
    private final ResponsesApiClient responsesApiClient;
    private final AnalysisPromptFactory promptFactory;
    private final ObjectMapper objectMapper;

    public AnalysisAgentClient(ResponsesApiClient responsesApiClient, AnalysisPromptFactory promptFactory, ObjectMapper objectMapper) {
        this.responsesApiClient = responsesApiClient;
        this.promptFactory = promptFactory;
        this.objectMapper = objectMapper;
    }

    public DraftResult generate(List<EvidencePacket> packets, String question, Map<String, Object> userContext) {
        AnalysisPromptFactory.AnalysisPrompt prompt = promptFactory.create(packets, question, userContext);
        ResponsesApiClient.ResponsesResult response = null;
        Exception parseFailure = null;
        // A schema-compatible provider may still return malformed JSON. One bounded re-request is safe;
        // the output remains untrusted and is independently validated afterwards.
        for (int attempt = 0; attempt < 2; attempt++) {
            response = responsesApiClient.generateJson(prompt.developerPrompt(), prompt.userPrompt(), prompt.jsonSchema());
            try {
                return new DraftResult(objectMapper.readValue(response.outputText(), AnalysisDraft.class), response);
            } catch (Exception e) {
                parseFailure = e;
            }
        }
        throw new InvalidAnalysisDraftException("Responses API returned invalid AnalysisDraft JSON after one repair retry", parseFailure);
    }

    public record DraftResult(AnalysisDraft draft, ResponsesApiClient.ResponsesResult response) {
    }

    public static class InvalidAnalysisDraftException extends RuntimeException {
        public InvalidAnalysisDraftException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
