package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Structured support/counter/context evidence for a relationship event candidate.
 */
@Data
@TableName("relationship_event_evidence")
public class RelationshipEventEvidence {
    @TableId(type = IdType.INPUT)
    private String id;
    private String eventId;
    private String evidenceRole;
    private String messageId;
    private String sessionId;
    private String chunkId;
    private String statisticPath;
    private String quoteText;
    private Instant messageTime;
    private Integer ordinal;
}
