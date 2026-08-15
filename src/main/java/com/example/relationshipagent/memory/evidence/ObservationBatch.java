package com.example.relationshipagent.memory.evidence;

import java.util.List;

/**
 * Stable batch identity and full packets, deliberately without prompt text or model output.
 */
public record ObservationBatch(String batchKey, List<ObservationEvidencePacket> packets, int characterCount) {
}
