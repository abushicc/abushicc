package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Deterministically detected relationship interval; not a model-authored fact.
 */
@Data
@TableName("relationship_stage")
public class RelationshipStage {
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String stageKey;
    private String stageType;
    private Instant startTime;
    private Instant endTime;
    private String metricsJson;
    private String summary;
    private BigDecimal confidence;
    private String source;
    private String reviewStatus;
    private String detectorVersion;
    private String inputHash;
    private Instant createdAt;
}
