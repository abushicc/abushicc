package com.example.relationshipagent.memory.evidence;

import java.time.Instant;
import java.util.List;

/**
 * Complete session packet; formatted text is never trusted as a model-produced source.
 */
public record ObservationEvidencePacket(String sessionRefId, String sessionId, Instant startTime, Instant endTime,
                                        String targetPerson, int targetMessageCount, boolean oversized,
                                        List<ObservationEvidenceRef> messages, List<String> cautions) {
    public int characterCount() {
        return messages.stream().mapToInt(ObservationEvidenceRef::characterCount).sum();
    }
}
