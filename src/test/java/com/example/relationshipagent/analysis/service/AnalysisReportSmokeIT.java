package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import com.example.relationshipagent.analysis.repository.AnalysisReportRepository;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit real-model smoke check; never runs in default test lifecycle. */
@SpringBootTest(properties="ra.analysis.enabled=true") @ActiveProfiles("dev") @EnabledIfSystemProperty(named="analysis.report.smoke", matches="true")
class AnalysisReportSmokeIT {
    @Autowired ChatFileRepository files; @Autowired AnalysisReportRepository reports; @Autowired AnalysisOrchestrator orchestrator;
    @Test void shouldGenerateAValidatedReportFromReadyDockerData() throws Exception {
        ChatFile file=files.selectOne(new LambdaQueryWrapper<ChatFile>().eq(ChatFile::getStatus,ChatFile.STATUS_READY).last("LIMIT 1")); assertThat(file).isNotNull();
        var accepted=orchestrator.request(file.getId(),"阶段三真实 smoke：请只输出证据充分的总体沟通变化。",Map.of());
        Instant deadline=Instant.now().plus(Duration.ofMinutes(5)); AnalysisReport report;
        do { Thread.sleep(1000); report=reports.selectById(accepted.reportId()); } while(Instant.now().isBefore(deadline) && (AnalysisReport.STATUS_PENDING.equals(report.getStatus())||AnalysisReport.STATUS_RUNNING.equals(report.getStatus())));
        assertThat(report.getStatus()).isIn(AnalysisReport.STATUS_SUCCESS,AnalysisReport.STATUS_REVIEW_REQUIRED);
        assertThat(report.getContentMarkdown()).contains("# 关系分析报告");
        System.out.printf("Analysis report smoke passed: reportId=%s status=%s%n",report.getId(),report.getStatus());
    }
}
