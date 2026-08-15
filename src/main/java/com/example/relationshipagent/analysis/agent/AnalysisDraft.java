package com.example.relationshipagent.analysis.agent;

import java.util.List;

/**
 * Untrusted model output. M6 validates every claim before persistence.
 */
public record AnalysisDraft(String schemaVersion, DraftCoverage coverage,
                            List<AnalysisSectionDraft> sections, List<String> limitations) {
    public record DraftCoverage(String summary, List<String> uncoveredPacketIds) {
    }

    public record AnalysisSectionDraft(String sectionKey, String summary, List<AnalysisClaimDraft> claims) {
    }

    public record AnalysisClaimDraft(String claimKey, String claimType, String statement, double confidence,
                                     List<String> supportEvidenceRefIds, List<String> counterEvidenceRefIds,
                                     String uncertaintyNote, List<String> alternativeExplanations) {
    }
}
