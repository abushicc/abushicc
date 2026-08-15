package com.example.relationshipagent.analysis.service;

import com.example.relationshipagent.analysis.feature.AnalysisSnapshot;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisInputHasherTest {
    private final AnalysisSnapshot snapshot = new AnalysisSnapshot("cf", "sha", Instant.EPOCH, Instant.EPOCH, 1, 2, 3, Instant.EPOCH, "stats", "chunks-v1", "embed-v1", "analysis-v1");
    @Test void shouldChangeWhenPromptModelOrQuestionChanges() {
        String original = AnalysisInputHasher.hash(snapshot, analysis("p1", "m1"), "q1", "{\"a\":1}");
        assertThat(AnalysisInputHasher.hash(snapshot, analysis("p2", "m1"), "q1", "{\"a\":1}")).isNotEqualTo(original);
        assertThat(AnalysisInputHasher.hash(snapshot, analysis("p1", "m2"), "q1", "{\"a\":1}")).isNotEqualTo(original);
        assertThat(AnalysisInputHasher.hash(snapshot, analysis("p1", "m1"), "q2", "{\"a\":1}")).isNotEqualTo(original);
    }
    @Test void shouldBeStableForSameCanonicalInput() {
        assertThat(AnalysisInputHasher.hash(snapshot, analysis("p1", "m1"), "q", "{\"a\":1,\"b\":2}"))
                .isEqualTo(AnalysisInputHasher.hash(snapshot, analysis("p1", "m1"), "q", "{\"a\":1,\"b\":2}"));
    }
    private static RelationshipAgentProperties.Analysis analysis(String prompt, String model) { return new RelationshipAgentProperties.Analysis(true,"provider","https://example.test","key",model,"responses","high",false,1,1,1,1000,1000,"a",prompt); }
}
