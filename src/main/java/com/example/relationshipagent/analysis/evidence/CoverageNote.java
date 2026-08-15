package com.example.relationshipagent.analysis.evidence;

import java.util.List;

/**
 * Explicitly records coverage and truncation rather than silently dropping evidence.
 */
public record CoverageNote(
        int evidenceItemsBeforeBudget,
        int evidenceItemsAfterBudget,
        boolean truncated,
        List<String> omittedPacketIds,
        long unreadableMediaCount,
        boolean counterEvidenceSearched,
        boolean counterEvidenceFound
) {
}
