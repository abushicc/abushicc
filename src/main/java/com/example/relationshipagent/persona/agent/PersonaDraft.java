package com.example.relationshipagent.persona.agent;

import java.util.List;

/**
 * Untrusted Persona proposal. Examples are references only; their text is always rehydrated on the server.
 */
public record PersonaDraft(String schemaVersion, FeatureDraft communicationStyle, List<FeatureDraft> preferences,
                           List<FeatureDraft> dislikes, List<FeatureDraft> interactionPatterns,
                           List<FeatureDraft> emotionalExpression, List<FeatureDraft> values,
                           List<FeatureDraft> boundaries, List<FewShotDraft> fewShotExamples,
                           List<String> limitations) {
    public record FeatureDraft(String statement, List<String> sourceMemoryIds) {
    }

    public record FewShotDraft(String sessionId, List<String> contextMessageIds, List<String> targetMessageIds) {
    }
}
