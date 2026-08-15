package com.example.relationshipagent.memory.evidence;

import java.time.Instant;

/**
 * A server-originated message reference exposed to the Observation model as an opaque ID.
 */
public record ObservationEvidenceRef(String evidenceRefId, String messageId, String sessionId,
                                     Instant messageTime, String speaker, String text, String messageType) {
    public String sourceKey() {
        return "MSG:" + messageId;
    }

    public int characterCount() {
        return text == null ? 0 : text.length();
    }
}
