package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.agent.AnalysisAgentClient;
import com.example.relationshipagent.analysis.evidence.EvidencePacketBuilder;
import com.example.relationshipagent.analysis.job.AnalysisJobExecutor;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.analysis.validation.AnalysisDraftValidator;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.processing.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Coordinates expensive analysis outside transactions and uses processing_job as its lease.
 */
@Service
public class AnalysisOrchestrator {
    private final AnalysisReportCreationService creation;
    private final ProcessingJobService jobs;
    private final AnalysisJobExecutor executor;
    private final AnalysisReportRepository reports;
    private final DeterministicAnalysisService deterministic;
    private final EvidencePacketBuilder packets;
    private final ObjectProvider<AnalysisAgentClient> agent;
    private final AnalysisDraftValidator validator;
    private final AnalysisReportWriter writer;
    private final RelationshipAgentProperties properties;
    private final ObjectMapper json;
    private final AgentRunAuditService audits;

    public AnalysisOrchestrator(AnalysisReportCreationService creation, ProcessingJobService jobs, AnalysisJobExecutor executor, AnalysisReportRepository reports, DeterministicAnalysisService deterministic, EvidencePacketBuilder packets, ObjectProvider<AnalysisAgentClient> agent, AnalysisDraftValidator validator, AnalysisReportWriter writer, RelationshipAgentProperties properties, ObjectMapper json, AgentRunAuditService audits) {
        this.creation = creation;
        this.jobs = jobs;
        this.executor = executor;
        this.reports = reports;
        this.deterministic = deterministic;
        this.packets = packets;
        this.agent = agent;
        this.validator = validator;
        this.writer = writer;
        this.properties = properties;
        this.json = json;
        this.audits = audits;
    }

    public Accepted request(String chatFileId, String question, Map<String, Object> context) {
        // 报告创建和 processing_job 幂等，真正的规则分析、证据构建和 LLM 调用在后台租约内执行。
        if (!properties.analysis().enabled() || agent.getIfAvailable() == null)
            throw new BizException(ErrorCode.ANALYSIS_DISABLED);
        var created = creation.createOrGet(chatFileId, question, context);
        AnalysisReport report = created.report();
        ProcessingJob job = jobs.createOrGet(chatFileId, ProcessingJob.TYPE_ANALYSIS, report.getInputHash());
        if (job != null && jobs.tryTakeover(job.getId()))
            executor.submit(job.getId(), () -> run(job.getId(), report.getId()));
        return new Accepted(report.getId(), job == null ? null : job.getId(), report.getStatus(), created.reused());
    }

    private void run(String jobId, String reportId) {
        com.example.relationshipagent.analysis.model.AgentRun audit = null;
        try {
            if (!jobs.isLeaseActive(jobId)) return;
            if (reports.tryStart(reportId) != 1) return;
            jobs.updateCursor(jobId, Map.of("reportId", reportId, "phase", "STAGES_EVENTS"));
            // 先生成确定性阶段/事件，再构建证据包；后续 LLM 只能基于该快照生成报告。
            var result = deterministic.generate(reports.selectById(reportId).getChatFileId());
            jobs.heartbeat(jobId);
            if (!jobs.isLeaseActive(jobId)) return;
            AnalysisReport report = reports.selectById(reportId);
            var evidence = packets.build(report.getChatFileId(), result.inputHash(), report.getQuestion());
            jobs.updateCursor(jobId, Map.of("reportId", reportId, "phase", "GENERATING", "packetCount", evidence.size()));
            audit = audits.start(report.getChatFileId(), properties.analysis().provider(), properties.analysis().model(), evidence.size());
            reports.attachAgentRun(reportId, audit.getId());
            Map<String, Object> context = json.readValue(report.getUserContextJson(), new TypeReference<Map<String, Object>>() {
            });
            // 远程生成不占用数据库事务；租约再次校验后才允许把通过验证的声明写回报告。
            var generated = agent.getObject().generate(evidence, report.getQuestion(), context);
            var draft = generated.draft();
            if (!jobs.isLeaseActive(jobId)) return;
            var validated = validator.validate(draft, evidence);
            jobs.updateCursor(jobId, Map.of("reportId", reportId, "phase", "PERSISTING"));
            writer.write(report, draft, evidence, validated);
            audits.success(audit, generated.response(), (int) validated.claims().stream().filter(c -> "VALID".equals(c.status())).count());
            jobs.markSuccess(jobId);
        } catch (Exception e) {
            audits.failed(audit, e);
            reports.failRunning(reportId, safe(e));
            jobs.markFailed(jobId, safe(e));
        }
    }

    private static String safe(Exception e) {
        String s = e.getClass().getSimpleName() + ": " + e.getMessage();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public record Accepted(String reportId, String jobId, String status, boolean reused) {
    }
}
