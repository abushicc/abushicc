package com.example.relationshipagent.analysis.validation;

import com.example.relationshipagent.analysis.evidence.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimConfidenceCalibratorTest {
    private final ClaimConfidenceCalibrator calibrator = new ClaimConfidenceCalibrator();
    @Test void shouldCapHypothesesAndSingleEvidenceFacts() {
        var message = ref("m", EvidenceKind.MESSAGE);
        assertThat(calibrator.calibrate("HYPOTHESIS", .9, List.of(message), List.of())).isEqualTo(.45);
        assertThat(calibrator.calibrate("FACT", .9, List.of(message), List.of())).isEqualTo(.85);
    }
    @Test void shouldRequireMixedSupportAndCounterForHighInferenceCap() {
        var message = ref("m", EvidenceKind.MESSAGE); var statistic = ref("s", EvidenceKind.STATISTIC);
        assertThat(calibrator.calibrate("INFERENCE", .9, List.of(message, statistic), List.of(message))).isEqualTo(.8);
        assertThat(calibrator.calibrate("INFERENCE", .9, List.of(message, statistic), List.of())).isEqualTo(.65);
    }
    private static EvidenceRef ref(String id, EvidenceKind kind) { return new EvidenceRef(id,kind,EvidenceRole.SUPPORT,kind == EvidenceKind.MESSAGE ? id : null,null,null,kind == EvidenceKind.STATISTIC ? "statistics.x" : null,null,null,"",null,null,"test"); }
}
