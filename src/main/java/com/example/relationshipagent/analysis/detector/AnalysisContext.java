package com.example.relationshipagent.analysis.detector;

import com.example.relationshipagent.analysis.feature.RelationshipFeatureSet;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.session.ConversationSession;

import java.util.List;
import java.util.Map;

/**
 * Complete factual input available to deterministic event detectors.
 */
public record AnalysisContext(
        RelationshipFeatureSet features,
        List<Message> messages,
        List<ConversationSession> sessions,
        Map<String, List<Message>> sessionMessages
) {
}
