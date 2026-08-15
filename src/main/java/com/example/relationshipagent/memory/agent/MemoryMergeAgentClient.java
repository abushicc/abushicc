package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Remote boundary for M5; output remains untrusted until MemoryMergeDraftValidator accepts it.
 */
@Service
@ConditionalOnProperty(prefix = "ra.memory", name = "enabled", havingValue = "true")
public class MemoryMergeAgentClient {
    private final ResponsesApiClient responses;
    private final MemoryMergePromptFactory prompts;
    private final ObjectMapper json;
    private final RelationshipAgentProperties properties;

    public MemoryMergeAgentClient(ResponsesApiClient responses, MemoryMergePromptFactory prompts, ObjectMapper json, RelationshipAgentProperties properties) {
        this.responses = responses;
        this.prompts = prompts;
        this.json = json;
        this.properties = properties;
    }

    public DraftResult generate(List<MemoryObservation> observations, String targetPerson) {
        var p = prompts.create(observations, targetPerson);
        Exception failure = null;
        ResponsesApiClient.ResponsesResult response = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            response = responses.generateJson(p.developerPrompt(), p.userPrompt(), p.jsonSchema(), properties.memory().maxOutputTokens());
            try {
                return new DraftResult(json.readValue(response.outputText(), MemoryMergeDraft.class), response);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw new InvalidMemoryMergeDraftException("Responses API returned invalid MemoryMergeDraft JSON after one repair retry", failure);
    }

    public record DraftResult(MemoryMergeDraft draft, ResponsesApiClient.ResponsesResult response) {
    }

    public static class InvalidMemoryMergeDraftException extends RuntimeException {
        public InvalidMemoryMergeDraftException(String m, Throwable c) {
            super(m, c);
        }
    }
}
