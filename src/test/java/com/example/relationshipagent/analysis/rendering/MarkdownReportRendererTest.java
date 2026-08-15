package com.example.relationshipagent.analysis.rendering;

import com.example.relationshipagent.analysis.agent.*;
import com.example.relationshipagent.analysis.evidence.*;
import com.example.relationshipagent.analysis.validation.AnalysisDraftValidator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportRendererTest {
    @Test void shouldEscapeClaimTextAndExcludeRejectedClaims() {
        var valid = new AnalysisDraft.AnalysisClaimDraft("a","FACT","<script>",.5,List.of("m"),List.of(),"",List.of());
        var rejected = new AnalysisDraft.AnalysisClaimDraft("b","FACT","secret",.5,List.of(),List.of(),"",List.of());
        var draft = new AnalysisDraft(AnalysisPromptFactory.SCHEMA_VERSION,new AnalysisDraft.DraftCoverage("coverage",List.of()),List.of(new AnalysisDraft.AnalysisSectionDraft("OVERVIEW","",List.of(valid,rejected))),List.of());
        var ref = new EvidenceRef("m",EvidenceKind.MESSAGE,EvidenceRole.SUPPORT,"m",null,null,null,null,"me","text",null,null,"test");
        var validation = new AnalysisDraftValidator().validate(draft,List.of(new EvidencePacket("p","GLOBAL_OVERVIEW","",null,null,java.util.Map.of(),List.of(ref),List.of(),null,List.of())));
        String markdown = new MarkdownReportRenderer().render(draft,validation);
        assertThat(markdown).contains("&lt;script&gt;").doesNotContain("\n【事实｜置信度 0.50】secret");
    }
}
