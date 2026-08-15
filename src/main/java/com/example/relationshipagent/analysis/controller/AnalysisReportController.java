package com.example.relationshipagent.analysis.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.dto.AnalysisReportRequest;
import com.example.relationshipagent.analysis.dto.AnalysisReportSummaryResponse;
import com.example.relationshipagent.analysis.dto.AnalysisReportDetailResponse;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.analysis.service.AnalysisOrchestrator;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat-files/{chatFileId}/analysis-reports")
public class AnalysisReportController {
    private final AnalysisOrchestrator orchestrator;
    private final ChatFileRepository files;
    private final AnalysisReportRepository reports;

    public AnalysisReportController(AnalysisOrchestrator orchestrator, ChatFileRepository files, AnalysisReportRepository reports) {
        this.orchestrator = orchestrator;
        this.files = files;
        this.reports = reports;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisOrchestrator.Accepted>> create(@PathVariable String chatFileId, @Valid @RequestBody AnalysisReportRequest request) {
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus()))
            throw new BizException(ErrorCode.ANALYSIS_PREREQUISITE_MISSING);
        if (request.reportType() != null && !request.reportType().isBlank() && !"FULL_RELATIONSHIP_REPORT".equals(request.reportType()))
            throw new BizException(ErrorCode.PARAM_INVALID, "unsupported reportType");
        var accepted = orchestrator.request(chatFileId, request.question(), request.userContext());
        return ResponseEntity.status(accepted.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @GetMapping
    public ApiResponse<List<AnalysisReportSummaryResponse>> list(@PathVariable String chatFileId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int safe = Math.min(Math.max(size, 1), 100);
        return ApiResponse.ok(reports.selectList(new LambdaQueryWrapper<AnalysisReport>().eq(AnalysisReport::getChatFileId, chatFileId).orderByDesc(AnalysisReport::getCreatedAt).last("LIMIT " + safe + " OFFSET " + Math.max(0, page) * safe)).stream().map(AnalysisReportSummaryResponse::from).toList());
    }

    @GetMapping("/{reportId}")
    public ApiResponse<AnalysisReportDetailResponse> detail(@PathVariable String chatFileId, @PathVariable String reportId) {
        AnalysisReport report = reports.selectById(reportId);
        if (report == null || !chatFileId.equals(report.getChatFileId()))
            throw new BizException(ErrorCode.ANALYSIS_REPORT_NOT_FOUND);
        return ApiResponse.ok(AnalysisReportDetailResponse.from(report));
    }
}
