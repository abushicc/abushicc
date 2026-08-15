package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A validated, session-local observation. It is never itself a stable personality conclusion.
 */
@Data
@TableName("memory_observation")
public class MemoryObservation {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String VALID = "VALID";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String REJECTED = "REJECTED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String observationType;
    private String statement;
    private String evidence;
    private BigDecimal confidence;
    private String source;
    private String status;
    private Instant createdAt;
    private String sourceSessionId;
    private String observationKey;
    private String polarity;
    private Instant validFrom;
    private Instant validTo;
    private String inputHash;
    private String extractorVersion;
    private String promptVersion;
    private String validationStatus;
    private String validationError;
    private String agentRunId;
    private Instant updatedAt;
}
