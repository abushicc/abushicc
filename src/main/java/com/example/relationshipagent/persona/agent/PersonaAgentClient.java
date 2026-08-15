package com.example.relationshipagent.persona.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Isolated remote Persona boundary; it neither queries nor writes the database.
 */
@Service
@ConditionalOnProperty(prefix = "ra.memory", name = "enabled", havingValue = "true")
public class PersonaAgentClient {
    private final ResponsesApiClient responses;
    private final PersonaPromptFactory prompts;
    private final ObjectMapper json;
    private final RelationshipAgentProperties properties;

    public PersonaAgentClient(ResponsesApiClient responses, PersonaPromptFactory prompts, ObjectMapper json, RelationshipAgentProperties properties) {
        this.responses = responses;
        this.prompts = prompts;
        this.json = json;
        this.properties = properties;
    }

    public DraftResult generate(PersonaBuildInput input) {
        var p = prompts.create(input);
        Exception failure = null;
        ResponsesApiClient.ResponsesResult response = null;
        for (int i = 0; i < 2; i++) {
            response = responses.generateJson(p.developerPrompt(), p.userPrompt(), p.jsonSchema(), properties.memory().maxOutputTokens());
            try {
                return new DraftResult(json.readValue(response.outputText(), PersonaDraft.class), response);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw new InvalidPersonaDraftException("Responses API returned invalid PersonaDraft JSON after one repair retry", failure);
    }

    public record DraftResult(PersonaDraft draft, ResponsesApiClient.ResponsesResult response) {
    }

    public static class InvalidPersonaDraftException extends RuntimeException {
        public InvalidPersonaDraftException(String m, Throwable c) {
            super(m, c);
        }
    }
}
