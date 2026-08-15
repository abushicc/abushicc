package com.example.relationshipagent.analysis.validation;

import com.example.relationshipagent.analysis.evidence.EvidenceKind;
import com.example.relationshipagent.analysis.evidence.EvidenceRef;

import java.util.List;

/**
 * Applies conservative server-side confidence ceilings; model confidence is never authoritative.
 */
public class ClaimConfidenceCalibrator {
    public double calibrate(String claimType, double modelConfidence, List<EvidenceRef> support, List<EvidenceRef> counter) {
        double cap = switch (claimType) {
            case "HYPOTHESIS" -> .45d;
            case "FACT" -> support.size() > 1 ? .95d : .85d;
            case "INFERENCE" -> hasStatisticAndMessage(support) && !counter.isEmpty() ? .80d : .65d;
            default -> 0d;
        };
        return Math.min(Math.max(0d, modelConfidence), cap);
    }

    private boolean hasStatisticAndMessage(List<EvidenceRef> refs) {
        return refs.stream().anyMatch(ref -> ref.kind() == EvidenceKind.STATISTIC)
                && refs.stream().anyMatch(ref -> ref.kind() == EvidenceKind.MESSAGE);
    }
}
