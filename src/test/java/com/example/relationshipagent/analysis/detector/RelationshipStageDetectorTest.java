package com.example.relationshipagent.analysis.detector;

import com.example.relationshipagent.analysis.feature.RelationshipFeatureSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipStageDetectorTest {

    private final RelationshipStageDetector detector = new RelationshipStageDetector();

    @Test
    void shouldEmitSporadicStageOnlyForPersistentZeroMonths() {
        RelationshipFeatureSet features = features(List.of(month("2025-01", 20, 4), month("2025-02", 20, 4),
                month("2025-03", 0, 0), month("2025-04", 0, 0)));

        List<StageCandidate> result = detector.detect(features);

        assertThat(result).anySatisfy(stage -> {
            assertThat(stage.stageType()).isEqualTo(RelationshipStageDetector.SPORADIC);
            assertThat(stage.stageKey()).isEqualTo("sporadic-2025-03-2025-04");
            assertThat(stage.confidence()).isBetween(0.5d, 0.95d);
        });
    }

    @Test
    void shouldFallbackToOneStableStageWhenNoLabelPersists() {
        RelationshipFeatureSet features = features(List.of(month("2025-01", 5, 1), month("2025-02", 30, 5),
                month("2025-03", 5, 1)));

        List<StageCandidate> result = detector.detect(features);

        assertThat(result).singleElement().satisfies(stage -> {
            assertThat(stage.stageType()).isEqualTo(RelationshipStageDetector.STABLE);
            assertThat(stage.metrics().get("thresholdMode")).isEqualTo("QUANTILE_FALLBACK");
        });
    }

    private static RelationshipFeatureSet features(List<RelationshipFeatureSet.MonthlyFeature> months) {
        return new RelationshipFeatureSet("relationship-features-v1", "UTC",
                new RelationshipFeatureSet.Coverage(Instant.parse("2025-01-01T00:00:00Z"),
                        Instant.parse("2025-04-30T00:00:00Z"), 1, 1, Map.of(), 0, 0), months,
                new RelationshipFeatureSet.TerminalFeature("LAST_SESSION_FALLBACK", Instant.parse("2025-04-30T00:00:00Z"),
                        null, null, null, false, List.of(), List.of()));
    }

    private static RelationshipFeatureSet.MonthlyFeature month(String value, long messages, long sessions) {
        Map<String, Long> zero = new LinkedHashMap<>();
        zero.put("me", 0L);
        zero.put("other", 0L);
        Map<String, RelationshipFeatureSet.ReplyMetric> replies = Map.of(
                "me", new RelationshipFeatureSet.ReplyMetric(null, null, 0),
                "other", new RelationshipFeatureSet.ReplyMetric(null, null, 0));
        return new RelationshipFeatureSet.MonthlyFeature(value, messages, sessions, messages == 0 ? 0 : 1,
                zero, replies, zero, zero,
                new RelationshipFeatureSet.SessionIntensity(null, null, null, null, Map.of(), Map.of(), sessions), 0);
    }
}
