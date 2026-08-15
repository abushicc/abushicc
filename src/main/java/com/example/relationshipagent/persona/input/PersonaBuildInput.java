package com.example.relationshipagent.persona.input;

import com.example.relationshipagent.memory.model.MemoryItem;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Whitelisted Persona input assembled from approved Memory, deterministic metrics and real-message IDs.
 */
public record PersonaBuildInput(String chatFileId, String targetPerson, List<MemoryItem> memories,
                                JsonNode styleFingerprint, Instant rangeFrom, Instant rangeTo,
                                List<PersonaFewShotCandidate> fewShotCandidates) {
}
