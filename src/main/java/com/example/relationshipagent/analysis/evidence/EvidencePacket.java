package com.example.relationshipagent.analysis.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One bounded evidence unit delivered to the Analysis Agent.
 */
public record EvidencePacket(
        String packetId,
        String packetType,
        String subjectKey,
        Instant startTime,
        Instant endTime,
        Map<String, Object> metrics,
        List<EvidenceRef> supportCandidates,
        List<EvidenceRef> counterCandidates,
        CoverageNote coverage,
        List<String> cautions
) {
}
