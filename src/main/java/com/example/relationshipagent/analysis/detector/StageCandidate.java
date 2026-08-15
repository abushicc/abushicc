package com.example.relationshipagent.analysis.detector;

import java.time.Instant;
import java.util.Map;

/**
 * A deterministic interval candidate. It is evidence for later review, never a diagnosis.
 */
public record StageCandidate(
        String stageKey,
        String stageType,
        Instant startTime,
        Instant endTime,
        Map<String, Object> metrics,
        String summary,
        double confidence
) {
}
