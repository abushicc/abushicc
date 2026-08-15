package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Server-resolved evidence for one report claim; model-provided quotes are never trusted.
 */
@Data
@TableName("analysis_claim_evidence")
public class AnalysisClaimEvidence {
    public static final String ROLE_SUPPORT = "SUPPORT";
    public static final String ROLE_COUNTER = "COUNTER";
    public static final String ROLE_CONTEXT = "CONTEXT";

    @TableId(type = IdType.INPUT)
    private String id;
    private String claimId;
    private String evidenceRefId;
    private String evidenceRole;
    private String messageId;
    private String sessionId;
    private String chunkId;
    private String statisticPath;
    private String quoteText;
    private Instant messageTime;
    private Integer ordinal;
}
