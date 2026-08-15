package com.example.relationshipagent.analysis.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record AnalysisReportRequest(String reportType, @Size(max = 500) String question,
                                    Map<String, Object> userContext, boolean force) {
}
