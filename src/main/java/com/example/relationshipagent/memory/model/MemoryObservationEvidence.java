package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Database-rehydrated evidence for an observation; model-provided quotations are never persisted.
 */
@Data
@TableName("memory_observation_evidence")
public class MemoryObservationEvidence {
    public static final String SUPPORT = "SUPPORT";
    public static final String COUNTER = "COUNTER";
    public static final String CONTEXT = "CONTEXT";
    @TableId(type = IdType.INPUT)
    private String id;
    private String observationId;
    private String evidenceRole;
    private String messageId;
    private String sessionId;
    private String quoteText;
    private Instant messageTime;
    private Integer ordinal;
}
