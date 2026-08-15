package com.example.relationshipagent.analysis.validation;

import com.example.relationshipagent.analysis.agent.AnalysisDraft;
import com.example.relationshipagent.analysis.agent.AnalysisPromptFactory;
import com.example.relationshipagent.analysis.evidence.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisDraftValidatorTest {
    private final AnalysisDraftValidator validator = new AnalysisDraftValidator();

    @Test
    void shouldValidateFactAndApplyConfidenceCap() {
        var claim = new AnalysisDraft.AnalysisClaimDraft("c1", "FACT", "有明确回复", .99, List.of("MES-1"), List.of(), "", List.of());
        var result = validator.validate(draft("OVERVIEW", claim), packets(message("MES-1", EvidenceRole.SUPPORT)));
        assertThat(result.claims()).singleElement().satisfies(c -> { assertThat(c.status()).isEqualTo("VALID"); assertThat(c.confidence()).isEqualTo(.85); });
    }
    @Test
    void shouldRejectUnknownOrOverlappingEvidence() {
        var claim = new AnalysisDraft.AnalysisClaimDraft("c1", "FACT", "结论", .5, List.of("NOPE"), List.of("NOPE"), "", List.of());
        var result = validator.validate(draft("OVERVIEW", claim), packets(message("MES-1", EvidenceRole.SUPPORT)));
        assertThat(result.claims()).singleElement().satisfies(c -> { assertThat(c.status()).isEqualTo("REJECTED"); assertThat(c.errors()).anyMatch(e -> e.startsWith("UNKNOWN_EVIDENCE_REF")); });
    }
    private AnalysisDraft draft(String section, AnalysisDraft.AnalysisClaimDraft claim) { return new AnalysisDraft(AnalysisPromptFactory.SCHEMA_VERSION, new AnalysisDraft.DraftCoverage("ok", List.of()), List.of(new AnalysisDraft.AnalysisSectionDraft(section, "s", List.of(claim))), List.of("limit")); }
    private List<EvidencePacket> packets(EvidenceRef ref) { return List.of(new EvidencePacket("p", "GLOBAL_OVERVIEW", "x", null, null, Map.of(), List.of(ref), List.of(), null, List.of())); }
    private EvidenceRef message(String id, EvidenceRole role) { return new EvidenceRef(id, EvidenceKind.MESSAGE, role, "m", null, null, null, null, "a", "text", null, null, "test"); }
}
