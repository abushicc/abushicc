package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Versioned long-term memory, supported by one or more observations.
 */
@Data
@TableName("memory_item")
public class MemoryItem {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String REVIEW_PENDING = "PENDING_REVIEW";
    public static final String REVIEW_APPROVED = "APPROVED";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String memoryType;
    private String content;
    private String evidence;
    private BigDecimal confidence;
    private String embedding;
    private String embeddingModel;
    private String status;
    private String supersededBy;
    private Instant createdAt;
    private Instant updatedAt;
    private String memoryKey;
    private String polarity;
    private Instant validFrom;
    private Instant validTo;
    private String inputHash;
    private String aggregationVersion;
    private String promptVersion;
    private String reviewStatus;
    private String parentMemoryId;
    private String agentRunId;
}
