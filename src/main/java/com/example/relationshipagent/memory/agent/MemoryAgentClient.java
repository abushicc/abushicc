package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.memory.evidence.ObservationBatch;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Model boundary only. It has no database dependency and returns untrusted Draft JSON.
 */
@Service
@ConditionalOnProperty(prefix = "ra.memory", name = "enabled", havingValue = "true")
public class MemoryAgentClient {
    private final ResponsesApiClient responses;
    private final MemoryPromptFactory prompts;
    private final ObjectMapper json;
    private final RelationshipAgentProperties properties;

    public MemoryAgentClient(ResponsesApiClient responses, MemoryPromptFactory prompts, ObjectMapper json, RelationshipAgentProperties properties) {
        this.responses = responses;
        this.prompts = prompts;
        this.json = json;
        this.properties = properties;
    }

    public DraftResult generate(ObservationBatch batch, String targetPerson) {
        var prompt = prompts.create(batch, targetPerson);
        Exception failure = null;
        ResponsesApiClient.ResponsesResult response = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            response = responses.generateJson(prompt.developerPrompt(), prompt.userPrompt(), prompt.jsonSchema(), properties.memory().maxOutputTokens());
            try {
                return new DraftResult(json.readValue(response.outputText(), ObservationDraft.class), response);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw new InvalidObservationDraftException("Responses API returned invalid ObservationDraft JSON after one repair retry", failure);
    }

    public record DraftResult(ObservationDraft draft, ResponsesApiClient.ResponsesResult response) {
    }

    public static class InvalidObservationDraftException extends RuntimeException {
        public InvalidObservationDraftException(String m, Throwable c) {
            super(m, c);
        }
    }
}
