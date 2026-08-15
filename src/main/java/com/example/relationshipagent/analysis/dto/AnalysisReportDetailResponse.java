package com.example.relationshipagent.analysis.dto;

import com.example.relationshipagent.analysis.model.AnalysisReport;

import java.time.Instant;

/**
 * Detail projection. Internal prompt/user-context/evidence-manifest fields are never returned.
 */
public record AnalysisReportDetailResponse(String id, String reportType, String title, String status,
                                           String question, String contentMarkdown, String coverageNote,
                                           String reportJson, String validationJson, String inputHash,
                                           String analysisVersion, String promptVersion, String modelName,
                                           String providerName, Instant createdAt, Instant startedAt,
                                           Instant finishedAt) {
    public static AnalysisReportDetailResponse from(AnalysisReport report) {
        return new AnalysisReportDetailResponse(report.getId(), report.getReportType(), report.getTitle(), report.getStatus(),
                report.getQuestion(), report.getContentMarkdown(), report.getCoverageNote(), report.getReportJson(),
                report.getValidationJson(), report.getInputHash(), report.getAnalysisVersion(), report.getPromptVersion(),
                report.getModelName(), report.getProviderName(), report.getCreatedAt(), report.getStartedAt(), report.getFinishedAt());
    }
}
