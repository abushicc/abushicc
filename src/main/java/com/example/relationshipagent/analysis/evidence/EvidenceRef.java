package com.example.relationshipagent.analysis.evidence;

import java.time.Instant;

/**
 * A stable, server-resolved reference. Model-generated quote/time/id values are never accepted.
 */
public record EvidenceRef(
        String evidenceRefId,
        EvidenceKind kind,
        EvidenceRole suggestedRole,
        String messageId,
        String sessionId,
        String chunkId,
        String statisticPath,
        Instant occurredAt,
        String speaker,
        String text,
        String contextBefore,
        String contextAfter,
        String provenance
) {
    public String sourceKey() {
        if (messageId != null) return "MSG:" + messageId;
        if (chunkId != null) return "CHK:" + chunkId;
        if (sessionId != null) return "SES:" + sessionId;
        return "STA:" + statisticPath;
    }

    public int characterCount() {
        return length(text) + length(contextBefore) + length(contextAfter);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
