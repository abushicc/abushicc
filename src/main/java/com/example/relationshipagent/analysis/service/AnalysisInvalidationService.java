package com.example.relationshipagent.analysis.service;

import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.processing.ProcessingJob;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entry point for upstream rebuild flows; retains historical reports while preventing stale writes.
 */
@Service
public class AnalysisInvalidationService {
    private final AnalysisReportRepository reports;
    private final ProcessingJobService jobs;

    public AnalysisInvalidationService(AnalysisReportRepository reports, ProcessingJobService jobs) {
        this.reports = reports;
        this.jobs = jobs;
    }

    @Transactional
    public void supersedeForUpstreamRebuild(String chatFileId, String upstream) {
        String reason = "superseded by upstream rebuild: " + upstream;
        jobs.cancelRunning(chatFileId, ProcessingJob.TYPE_ANALYSIS, reason);
        reports.supersedeByChatFile(chatFileId, reason);
    }
}
