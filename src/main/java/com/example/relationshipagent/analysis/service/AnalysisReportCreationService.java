package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.feature.AnalysisSnapshot;
import com.example.relationshipagent.analysis.feature.AnalysisSnapshotService;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Creates a report exactly once per immutable analysis input; it never calls the model.
 */
@Service
public class AnalysisReportCreationService {
    public static final String REPORT_TYPE_FULL = "FULL";
    private final AnalysisSnapshotService snapshots;
    private final AnalysisReportRepository reports;
    private final RelationshipAgentProperties properties;
    private final ObjectMapper json;

    public AnalysisReportCreationService(AnalysisSnapshotService snapshots, AnalysisReportRepository reports, RelationshipAgentProperties properties, ObjectMapper json) {
        this.snapshots = snapshots;
        this.reports = reports;
        this.properties = properties;
        this.json = json;
    }

    @Transactional
    public CreationResult createOrGet(String chatFileId, String question, Map<String, Object> userContext) {
        AnalysisSnapshot snapshot = snapshots.create(chatFileId);
        String context = asJson(userContext == null ? Map.of() : userContext);
        String hash = AnalysisInputHasher.hash(snapshot, properties.analysis(), question, context);
        AnalysisReport existing = find(chatFileId, hash);
        if (existing != null) return new CreationResult(existing, true);
        AnalysisReport report = new AnalysisReport();
        report.setId(UUID.randomUUID().toString());
        report.setChatFileId(chatFileId);
        report.setReportType(REPORT_TYPE_FULL);
        report.setTitle("关系分析报告");
        report.setCoverageNote("报告生成中；完成后写入证据覆盖范围。");
        report.setStatus(AnalysisReport.STATUS_PENDING);
        report.setQuestion(question);
        report.setUserContextJson(context);
        report.setInputHash(hash);
        report.setAnalysisVersion(properties.analysis().analysisVersion());
        report.setPromptVersion(properties.analysis().promptVersion());
        report.setModelName(properties.analysis().model());
        report.setProviderName(properties.analysis().provider());
        report.setCreatedAt(java.time.Instant.now());
        try {
            reports.insertPendingWithJsonb(report);
            return new CreationResult(report, false);
        } catch (DuplicateKeyException race) {
            AnalysisReport winner = find(chatFileId, hash);
            if (winner != null) return new CreationResult(winner, true);
            throw race;
        }
    }

    private AnalysisReport find(String chatFileId, String hash) {
        return reports.selectOne(new LambdaQueryWrapper<AnalysisReport>().eq(AnalysisReport::getChatFileId, chatFileId).eq(AnalysisReport::getReportType, REPORT_TYPE_FULL).eq(AnalysisReport::getInputHash, hash));
    }

    /**
     * Map key ordering is part of the input identity; semantically identical contexts must reuse a report.
     */
    private String asJson(Object value) {
        try {
            return json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("User context must be JSON-serializable", e);
        }
    }

    public record CreationResult(AnalysisReport report, boolean reused) {
    }
}
