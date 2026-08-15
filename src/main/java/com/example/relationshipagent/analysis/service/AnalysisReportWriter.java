package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.agent.AnalysisDraft;
import com.example.relationshipagent.analysis.evidence.EvidencePacket;
import com.example.relationshipagent.analysis.model.*;
import com.example.relationshipagent.analysis.rendering.MarkdownReportRenderer;
import com.example.relationshipagent.analysis.repository.*;
import com.example.relationshipagent.analysis.validation.AnalysisDraftValidator;
import com.example.relationshipagent.analysis.validation.EvidenceReferenceResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Atomically persists only validated claims and database-rehydrated evidence for a RUNNING report.
 */
@Service
public class AnalysisReportWriter {
    private final AnalysisReportRepository reports;
    private final AnalysisClaimRepository claims;
    private final AnalysisClaimEvidenceRepository evidence;
    private final EvidenceReferenceResolver resolver;
    private final MarkdownReportRenderer renderer;
    private final ObjectMapper json;

    public AnalysisReportWriter(AnalysisReportRepository reports, AnalysisClaimRepository claims, AnalysisClaimEvidenceRepository evidence, EvidenceReferenceResolver resolver, MarkdownReportRenderer renderer, ObjectMapper json) {
        this.reports = reports;
        this.claims = claims;
        this.evidence = evidence;
        this.resolver = resolver;
        this.renderer = renderer;
        this.json = json;
    }

    @Transactional
    public void write(AnalysisReport report, AnalysisDraft draft, List<EvidencePacket> packets, AnalysisDraftValidator.ValidationResult validation) {
        // 写入前再次确认报告仍由当前 worker 持有 RUNNING 状态；声明和证据引用在一个事务中原子提交。
        if (!AnalysisReport.STATUS_RUNNING.equals(report.getStatus()))
            throw new IllegalStateException("Analysis report must be RUNNING before completion");
        clearPreviousClaims(report.getId());
        int valid = 0, review = 0, ordinal = 0;
        for (AnalysisDraftValidator.ValidatedClaim candidate : validation.claims()) {
            if ("REJECTED".equals(candidate.status())) continue;
            AnalysisClaim claim = new AnalysisClaim();
            claim.setId(UUID.randomUUID().toString());
            claim.setReportId(report.getId());
            claim.setClaimKey(candidate.draft().claimKey());
            claim.setSectionKey(candidate.sectionKey());
            claim.setClaimType(candidate.draft().claimType());
            claim.setStatement(candidate.draft().statement());
            claim.setConfidence(BigDecimal.valueOf(candidate.confidence()));
            claim.setUncertaintyNote(candidate.draft().uncertaintyNote());
            claim.setValidationStatus(candidate.status());
            claim.setValidationError(String.join(",", candidate.errors()));
            claim.setOrdinal(ordinal++);
            claim.setCreatedAt(Instant.now());
            claims.insert(claim);
            persistEvidence(report.getChatFileId(), claim.getId(), candidate.support(), AnalysisClaimEvidence.ROLE_SUPPORT);
            persistEvidence(report.getChatFileId(), claim.getId(), candidate.counter(), AnalysisClaimEvidence.ROLE_COUNTER);
            if ("VALID".equals(candidate.status())) valid++;
            else review++;
        }
        report.setContentMarkdown(renderer.render(draft, validation));
        report.setCoverageNote(draft.coverage().summary());
        report.setReportJson(asJson(draft));
        report.setEvidenceManifestJson(asJson(packets));
        report.setValidationJson(asJson(validation));
        report.setFinishedAt(Instant.now());
        report.setStatus(valid == 0 ? AnalysisReport.STATUS_FAILED : review > 0 || !validation.reportErrors().isEmpty() ? AnalysisReport.STATUS_REVIEW_REQUIRED : AnalysisReport.STATUS_SUCCESS);
        report.setErrorMessage(valid == 0 ? "No valid claims after validation" : null);
        if (reports.completeWithJsonb(report) != 1)
            throw new IllegalStateException("Analysis report is no longer RUNNING");
    }

    private void clearPreviousClaims(String reportId) {
        for (AnalysisClaim c : claims.selectList(new LambdaQueryWrapper<AnalysisClaim>().eq(AnalysisClaim::getReportId, reportId))) {
            evidence.delete(new LambdaQueryWrapper<AnalysisClaimEvidence>().eq(AnalysisClaimEvidence::getClaimId, c.getId()));
        }
        claims.delete(new LambdaQueryWrapper<AnalysisClaim>().eq(AnalysisClaim::getReportId, reportId));
    }

    private void persistEvidence(String chatFileId, String claimId, List<com.example.relationshipagent.analysis.evidence.EvidenceRef> refs, String role) {
        // 模型只提交 evidenceRefId，服务端重新解析成 message/session/chunk，防止模型伪造引用文本。
        int ordinal = 0;
        for (var ref : refs) {
            var resolved = resolver.resolve(chatFileId, ref);
            if (resolved == null)
                throw new IllegalStateException("Evidence changed or crosses chat file: " + ref.evidenceRefId());
            AnalysisClaimEvidence row = new AnalysisClaimEvidence();
            row.setId(UUID.randomUUID().toString());
            row.setClaimId(claimId);
            row.setEvidenceRefId(ref.evidenceRefId());
            row.setEvidenceRole(role);
            row.setMessageId(resolved.messageId());
            row.setSessionId(resolved.sessionId());
            row.setChunkId(resolved.chunkId());
            row.setStatisticPath(resolved.statisticPath());
            row.setQuoteText(resolved.quoteText());
            row.setMessageTime(resolved.messageTime());
            row.setOrdinal(ordinal++);
            evidence.insert(row);
        }
    }

    private String asJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize report JSON", e);
        }
    }
}
