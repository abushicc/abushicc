package com.example.relationshipagent.analysis.detector;

/**
 * Initial deterministic evidence reference; M4 later expands it into a complete evidence packet.
 */
public record EvidenceSeed(String role, String messageId, String sessionId, String statisticPath) {
}
