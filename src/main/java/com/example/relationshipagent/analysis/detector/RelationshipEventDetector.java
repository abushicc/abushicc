package com.example.relationshipagent.analysis.detector;

import java.util.List;

/**
 * Small composable detector for one class of explainable event candidate.
 */
public interface RelationshipEventDetector {
    List<EventCandidate> detect(AnalysisContext context);
}
