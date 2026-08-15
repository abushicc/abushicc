package com.example.relationshipagent.memory.agent;

import java.util.List;

/**
 * Untrusted per-session Memory Agent output. It is validated before any persistence.
 */
public record ObservationDraft(String schemaVersion, List<SessionObservationDraft> sessions, List<String> limitations) {
    public record SessionObservationDraft(String sessionRefId, List<ObservationItemDraft> observations) {
    }

    public record ObservationItemDraft(String observationKey, String observationType, String statement, String polarity,
                                       double confidence, List<String> supportEvidenceRefIds,
                                       List<String> counterEvidenceRefIds, String uncertaintyNote) {
    }
}
