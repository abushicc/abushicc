package com.example.relationshipagent.analysis.dto;

import com.example.relationshipagent.analysis.model.AnalysisReport;

import java.time.Instant;

/**
 * Public list projection; intentionally excludes prompt/evidence manifests and internal errors.
 */
public record AnalysisReportSummaryResponse(String id, String reportType, String title, String status,
                                            String question, String coverageNote, String inputHash,
                                            String analysisVersion, String promptVersion, String modelName,
                                            Instant createdAt, Instant finishedAt) {
    public static AnalysisReportSummaryResponse from(AnalysisReport report) {
        return new AnalysisReportSummaryResponse(report.getId(), report.getReportType(), report.getTitle(), report.getStatus(),
                report.getQuestion(), report.getCoverageNote(), report.getInputHash(), report.getAnalysisVersion(),
                report.getPromptVersion(), report.getModelName(), report.getCreatedAt(), report.getFinishedAt());
    }
}
