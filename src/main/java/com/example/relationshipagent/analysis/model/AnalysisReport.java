package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Persistent Analysis Agent report. Markdown is a rendered view of validated claims.
 */
@Data
@TableName("analysis_report")
public class AnalysisReport {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String reportType;
    private String title;
    private String contentMarkdown;
    private String evidence;
    private String coverageNote;
    private String status;
    private String question;
    private String userContextJson;
    private String reportJson;
    private String evidenceManifestJson;
    private String validationJson;
    private String inputHash;
    private String analysisVersion;
    private String promptVersion;
    private String modelName;
    private String providerName;
    private String agentRunId;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private String supersededBy;
    private Instant createdAt;
}
