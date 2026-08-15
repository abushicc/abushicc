package com.example.relationshipagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.relationshipagent.analysis.model.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface AnalysisReportRepository extends BaseMapper<AnalysisReport> {

    @Update("UPDATE analysis_report SET status='SUPERSEDED', error_message=#{reason}, finished_at=NOW() WHERE chat_file_id=#{chatFileId} AND status IN ('PENDING','RUNNING','SUCCESS','REVIEW_REQUIRED')")
    int supersedeByChatFile(@Param("chatFileId") String chatFileId, @Param("reason") String reason);

    @Update("UPDATE analysis_report SET status='RUNNING', started_at=NOW() WHERE id=#{reportId} AND status='PENDING'")
    int tryStart(@Param("reportId") String reportId);

    @Update("UPDATE analysis_report SET status='FAILED', error_message=#{error}, finished_at=NOW() WHERE id=#{reportId} AND status='RUNNING'")
    int failRunning(@Param("reportId") String reportId, @Param("error") String error);

    @Update("UPDATE analysis_report SET agent_run_id=#{agentRunId} WHERE id=#{reportId} AND status='RUNNING'")
    int attachAgentRun(@Param("reportId") String reportId, @Param("agentRunId") String agentRunId);

    @Insert("""
            INSERT INTO analysis_report (id,chat_file_id,report_type,title,coverage_note,status,question,user_context_json,input_hash,
              analysis_version,prompt_version,model_name,provider_name,created_at)
            VALUES (#{report.id},#{report.chatFileId},#{report.reportType},#{report.title},#{report.coverageNote},#{report.status},#{report.question},
              CAST(#{report.userContextJson} AS jsonb),#{report.inputHash},#{report.analysisVersion},#{report.promptVersion},
              #{report.modelName},#{report.providerName},#{report.createdAt})
            """)
    int insertPendingWithJsonb(@Param("report") AnalysisReport report);

    /**
     * Explicit JSONB casts avoid relying on JDBC String-to-jsonb coercion.
     */
    @Update("""
            UPDATE analysis_report SET content_markdown=#{report.contentMarkdown}, coverage_note=#{report.coverageNote},
              report_json=CAST(#{report.reportJson} AS jsonb), evidence_manifest_json=CAST(#{report.evidenceManifestJson} AS jsonb),
              validation_json=CAST(#{report.validationJson} AS jsonb), status=#{report.status}, error_message=#{report.errorMessage},
              finished_at=#{report.finishedAt}
            WHERE id=#{report.id} AND status='RUNNING'
            """)
    int completeWithJsonb(@Param("report") AnalysisReport report);
}
