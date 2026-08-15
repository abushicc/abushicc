package com.example.relationshipagent.memory.agent;

import java.time.Instant;
import java.util.List;

/**
 * Untrusted cross-session merge result. It is validated before a MemoryItem is written.
 */
public record MemoryMergeDraft(String schemaVersion, List<MemoryItemDraft> items, List<String> limitations) {
    public record MemoryItemDraft(String memoryKey, String memoryType, String content, String polarity,
                                  double confidence, List<String> sourceObservationIds,
                                  Instant validFrom, Instant validTo, String conflictNote) {
    }
}
