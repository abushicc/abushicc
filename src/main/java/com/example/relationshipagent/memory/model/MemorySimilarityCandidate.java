package com.example.relationshipagent.memory.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A vector-ranked review candidate. It is deliberately not a merge decision.
 */
public record MemorySimilarityCandidate(
        String id, String memoryKey, String memoryType, String polarity, String content,
        BigDecimal confidence, String reviewStatus, Instant validFrom, Instant validTo,
        double distance) {
}
