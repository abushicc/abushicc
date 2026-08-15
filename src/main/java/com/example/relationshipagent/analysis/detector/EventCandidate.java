package com.example.relationshipagent.analysis.detector;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A reviewable rule candidate, not a claim about intention, cause, or relationship status.
 */
public record EventCandidate(
        String eventKey,
        String eventType,
        Instant startTime,
        Instant endTime,
        String statement,
        double confidence,
        Map<String, Object> metrics,
        List<EvidenceSeed> evidence
) {
}
