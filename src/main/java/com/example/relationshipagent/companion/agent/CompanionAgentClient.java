package com.example.relationshipagent.companion.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Companion-specific options over the shared Responses transport.
 */
@Component
public class CompanionAgentClient {
    private final ObjectProvider<ResponsesApiClient> transport;
    private final RelationshipAgentProperties properties;

    public CompanionAgentClient(ObjectProvider<ResponsesApiClient> transport, RelationshipAgentProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    public ResponsesApiClient.ResponsesResult generate(CompanionPromptFactory.Prompt prompt) {
        RelationshipAgentProperties.Companion companion = properties.companion();
        if (companion == null || !companion.enabled()) throw new BizException(ErrorCode.COMPANION_DISABLED);
        if (companion.store()) throw new IllegalStateException("ra.companion.store must remain false");
        ResponsesApiClient client = transport.getIfAvailable();
        if (client == null) throw new BizException(ErrorCode.COMPANION_DISABLED);
        return client.generateJson(prompt.developerPrompt(), prompt.userPrompt(), prompt.jsonSchema(),
                new ResponsesApiClient.ResponsesOptions(companion.model(), companion.reasoningEffort(), false,
                        companion.maxOutputTokens(), "companion_reply"));
    }
}
