package com.example.relationshipagent.memory.validation;

import java.util.List;

/**
 * Server-side ceiling for local observations; the model confidence is never authoritative.
 */
public class MemoryConfidenceCalibrator {
    public double calibrate(String type, double proposed, int supportCount, boolean hasCounter) {
        if (type == null) return 0;
        double cap = switch (type) {
            case "FACT", "EVENT" -> supportCount > 1 ? .92 : .85;
            case "PREFERENCE", "DISLIKE" -> .70;
            case "EMOTIONAL_PATTERN", "COMMUNICATION_PATTERN", "RELATIONSHIP_PATTERN", "VALUE" -> .60;
            default -> 0;
        };
        if (hasCounter) cap = Math.max(.1, cap - .15);
        return Math.min(Math.max(0, proposed), cap);
    }
}
