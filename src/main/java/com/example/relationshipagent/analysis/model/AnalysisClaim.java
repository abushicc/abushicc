package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One independently validated statement in an analysis report.
 */
@Data
@TableName("analysis_claim")
public class AnalysisClaim {
    public static final String TYPE_FACT = "FACT";
    public static final String TYPE_INFERENCE = "INFERENCE";
    public static final String TYPE_HYPOTHESIS = "HYPOTHESIS";
    public static final String VALID = "VALID";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String REJECTED = "REJECTED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String reportId;
    private String claimKey;
    private String sectionKey;
    private String claimType;
    private String statement;
    private BigDecimal confidence;
    private String uncertaintyNote;
    private String validationStatus;
    private String validationError;
    private Integer ordinal;
    private Instant createdAt;
}
