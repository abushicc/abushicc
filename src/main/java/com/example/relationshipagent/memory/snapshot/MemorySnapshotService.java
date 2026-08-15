package com.example.relationshipagent.memory.snapshot;

import com.example.relationshipagent.analysis.feature.AnalysisSnapshotService;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.processing.ProcessingJobService;
import com.example.relationshipagent.memory.service.MemoryAggregationCandidateService;
import org.springframework.stereotype.Service;

@Service
public class MemorySnapshotService {
    private final AnalysisSnapshotService analysisSnapshots;
    private final RelationshipAgentProperties properties;

    public MemorySnapshotService(AnalysisSnapshotService analysisSnapshots, RelationshipAgentProperties properties) {
        this.analysisSnapshots = analysisSnapshots;
        this.properties = properties;
    }

    public MemorySnapshot create(String chatFileId, String targetPerson) {
        var m = properties.memory();
        return new MemorySnapshot(analysisSnapshots.create(chatFileId), targetPerson, m.extractorVersion(), m.observationPromptVersion(), m.aggregationVersion(), m.mergePromptVersion(), m.personaPromptVersion());
    }

    public String extractionHash(MemorySnapshot snapshot) {
        var s = snapshot.source();
        return ProcessingJobService.hashInput(s.chatFileId(), s.sourceSha256(), String.valueOf(s.firstMessageTime()), String.valueOf(s.lastMessageTime()), String.valueOf(s.messageCount()), String.valueOf(s.sessionCount()), s.statisticsHash(), s.chunkVersion(), s.embeddingModel(), s.analysisVersion(), snapshot.targetPerson(), snapshot.extractorVersion(), snapshot.observationPromptVersion());
    }

    public String aggregationHash(MemorySnapshot snapshot, java.util.List<MemoryAggregationCandidateService.Candidate> candidates) {
        return ProcessingJobService.hashInput(extractionHash(snapshot), snapshot.aggregationVersion(), snapshot.mergePromptVersion(),
                snapshot.targetPerson(), MemoryAggregationCandidateService.fingerprint(candidates));
    }
}
