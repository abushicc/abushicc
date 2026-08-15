package com.example.relationshipagent.analysis.rendering;

import com.example.relationshipagent.analysis.agent.AnalysisDraft;
import com.example.relationshipagent.analysis.agent.AnalysisPromptFactory;
import com.example.relationshipagent.analysis.validation.AnalysisDraftValidator;
import com.example.relationshipagent.analysis.evidence.EvidenceRef;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Renders only validated claims and server-resolved evidence; model quotes are never used.
 */
@Component
public class MarkdownReportRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<String, String> HEADINGS = Map.ofEntries(
            Map.entry("OVERVIEW", "总体结论"), Map.entry("RELATIONSHIP_STAGES", "关系阶段划分"),
            Map.entry("COMMUNICATION_PATTERNS", "沟通模式分析"), Map.entry("CONFLICT_TOPICS", "高频冲突主题"),
            Map.entry("EMOTIONAL_TRENDS", "情绪变化趋势"), Map.entry("NEEDS_DIFFERENCES", "双方需求差异"),
            Map.entry("TURNING_POINTS", "关键转折点"), Map.entry("RELATIONSHIP_ENDING", "关系终止分析"),
            Map.entry("POSSIBLE_FACTORS", "可能的促成因素与替代解释"));

    public String render(AnalysisDraft draft, AnalysisDraftValidator.ValidationResult validation) {
        StringBuilder out = new StringBuilder("# 关系分析报告\n\n> 这是基于聊天文本的辅助分析，不等同于现实关系事实或专业诊断。\n\n");
        out.append("## 证据覆盖范围与限制\n").append(escape(draft.coverage().summary())).append("\n");
        for (String limitation : draft.limitations()) out.append("- ").append(escape(limitation)).append("\n");
        Map<String, List<AnalysisDraftValidator.ValidatedClaim>> bySection = new LinkedHashMap<>();
        for (AnalysisDraftValidator.ValidatedClaim claim : validation.claims())
            bySection.computeIfAbsent(claim.sectionKey(), ignored -> new ArrayList<>()).add(claim);
        LinkedHashMap<String, EvidenceRef> index = new LinkedHashMap<>();
        for (String section : AnalysisPromptFactory.SECTION_KEYS) {
            out.append("\n## ").append(HEADINGS.get(section)).append("\n");
            for (AnalysisDraftValidator.ValidatedClaim claim : bySection.getOrDefault(section, List.of())) {
                if (!"VALID".equals(claim.status())) continue;
                out.append("【").append(label(claim.draft().claimType())).append("｜置信度 ").append(String.format(Locale.ROOT, "%.2f", claim.confidence())).append("】")
                        .append(escape(claim.draft().statement())).append("\n");
                appendEvidence(out, "支持证据", claim.support(), index);
                appendEvidence(out, "反证/限制", claim.counter(), index);
                if (claim.draft().uncertaintyNote() != null && !claim.draft().uncertaintyNote().isBlank())
                    out.append("限制：").append(escape(claim.draft().uncertaintyNote())).append("\n");
            }
        }
        out.append("\n## 待复核结论\n");
        validation.claims().stream().filter(c -> "REVIEW_REQUIRED".equals(c.status())).forEach(c -> out.append("- ").append(escape(c.draft().statement())).append("\n"));
        out.append("\n## 证据索引\n");
        index.forEach((id, ref) -> out.append("- ").append(id).append("：").append(evidenceText(ref)).append("\n"));
        return out.toString();
    }

    private static void appendEvidence(StringBuilder out, String label, List<EvidenceRef> refs, Map<String, EvidenceRef> index) {
        if (!refs.isEmpty()) {
            out.append(label).append("：");
            for (EvidenceRef ref : refs) {
                index.putIfAbsent(ref.evidenceRefId(), ref);
                out.append('[').append(ref.evidenceRefId()).append("] ");
            }
            out.append("\n");
        }
    }

    private static String evidenceText(EvidenceRef ref) {
        return (ref.occurredAt() == null ? "" : TIME.format(ref.occurredAt()) + "，") + escape(ref.speaker() == null ? "" : ref.speaker() + "：") + escape(ref.text());
    }

    private static String label(String type) {
        return switch (type) {
            case "FACT" -> "事实";
            case "HYPOTHESIS" -> "假设";
            default -> "推断";
        };
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
