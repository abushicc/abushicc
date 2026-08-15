package com.example.relationshipagent.analysis.service;

import com.example.relationshipagent.analysis.feature.*;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisReportCreationServiceTest {
    @Test void shouldReuseExistingInputWithoutInsertingAgain() {
        AnalysisSnapshotService snapshots = mock(AnalysisSnapshotService.class); when(snapshots.create("cf")).thenReturn(snapshot());
        AnalysisReportRepository reports = mock(AnalysisReportRepository.class); AnalysisReport existing = new AnalysisReport(); existing.setId("existing");
        when(reports.selectOne(any())).thenReturn(existing);
        var service = new AnalysisReportCreationService(snapshots, reports, properties(), new ObjectMapper());
        var result = service.createOrGet("cf", "问题", Map.of("endDate", "2025-01-01"));
        assertThat(result.reused()).isTrue(); assertThat(result.report().getId()).isEqualTo("existing"); verify(reports, never()).insertPendingWithJsonb(any());
    }
    @Test void shouldCreatePendingReportForNewInput() {
        AnalysisSnapshotService snapshots = mock(AnalysisSnapshotService.class); when(snapshots.create("cf")).thenReturn(snapshot());
        AnalysisReportRepository reports = mock(AnalysisReportRepository.class); when(reports.selectOne(any())).thenReturn(null); when(reports.insertPendingWithJsonb(any())).thenReturn(1);
        var result = new AnalysisReportCreationService(snapshots, reports, properties(), new ObjectMapper()).createOrGet("cf", "问题", Map.of());
        assertThat(result.reused()).isFalse(); assertThat(result.report().getStatus()).isEqualTo(AnalysisReport.STATUS_PENDING); assertThat(result.report().getInputHash()).hasSize(64); verify(reports).insertPendingWithJsonb(any());
    }
    private static AnalysisSnapshot snapshot() { return new AnalysisSnapshot("cf", "sha", Instant.EPOCH, Instant.EPOCH, 1, 1, 1, Instant.EPOCH, "stats", "v", "m", "a"); }
    private static RelationshipAgentProperties properties() { return new RelationshipAgentProperties(new RelationshipAgentProperties.Session(1,1,1),new RelationshipAgentProperties.Chunk(1,1),new RelationshipAgentProperties.Job(1,1,1),new RelationshipAgentProperties.Retrieval(1,1),new RelationshipAgentProperties.Embedding("embed","p",1,1,1,1),new RelationshipAgentProperties.Statistics(List.of())); }
}
