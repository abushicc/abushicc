package com.example.relationshipagent.memory.dto;

import com.example.relationshipagent.memory.model.MemoryItem;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Review DTO intentionally omits embeddings, agent output and raw Observation evidence.
 */
public record MemoryItemResponse(String id, String memoryType, String content, BigDecimal confidence, String status,
                                 String reviewStatus, String polarity, Instant validFrom, Instant validTo,
                                 Instant createdAt, String parentMemoryId) {
    public static MemoryItemResponse from(MemoryItem item) {
        return new MemoryItemResponse(item.getId(), item.getMemoryType(), item.getContent(), item.getConfidence(), item.getStatus(), item.getReviewStatus(), item.getPolarity(), item.getValidFrom(), item.getValidTo(), item.getCreatedAt(), item.getParentMemoryId());
    }
}
