package com.example.relationshipagent.memory.snapshot;

import com.example.relationshipagent.analysis.feature.AnalysisSnapshot;

/**
 * Memory extraction identity adds target and its own independent versions to the immutable data snapshot.
 */
public record MemorySnapshot(AnalysisSnapshot source, String targetPerson, String extractorVersion,
                             String observationPromptVersion, String aggregationVersion, String mergePromptVersion,
                             String personaPromptVersion) {
}
