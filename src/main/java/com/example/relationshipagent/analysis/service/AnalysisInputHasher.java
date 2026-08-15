package com.example.relationshipagent.analysis.service;

import com.example.relationshipagent.analysis.feature.AnalysisSnapshot;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.processing.ProcessingJobService;

/**
 * Creates a stable, version-aware identity for an immutable analysis input.
 */
public final class AnalysisInputHasher {
    private AnalysisInputHasher() {
    }

    public static String hash(AnalysisSnapshot snapshot, RelationshipAgentProperties.Analysis analysis,
                              String question, String canonicalUserContext) {
        return ProcessingJobService.hashInput(snapshot.chatFileId(), snapshot.sourceSha256(),
                String.valueOf(snapshot.firstMessageTime()), String.valueOf(snapshot.lastMessageTime()),
                String.valueOf(snapshot.messageCount()), String.valueOf(snapshot.sessionCount()),
                String.valueOf(snapshot.chunkCount()), snapshot.statisticsHash(), snapshot.chunkVersion(),
                snapshot.embeddingModel(), snapshot.analysisVersion(), analysis.promptVersion(), analysis.provider(),
                analysis.model(), question == null ? "" : question, canonicalUserContext);
    }
}
