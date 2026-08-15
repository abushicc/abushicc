package com.example.relationshipagent.analysis.feature;

import java.time.Instant;

/**
 * Immutable version identity for one deterministic analysis input.
 */
public record AnalysisSnapshot(
        String chatFileId,
        String sourceSha256,
        Instant firstMessageTime,
        Instant lastMessageTime,
        long messageCount,
        long sessionCount,
        long chunkCount,
        Instant statisticsComputedAt,
        String statisticsHash,
        String chunkVersion,
        String embeddingModel,
        String analysisVersion
) {
}
