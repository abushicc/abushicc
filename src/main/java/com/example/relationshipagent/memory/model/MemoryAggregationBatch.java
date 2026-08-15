package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Durable aggregation candidate checkpoint; successful candidates are never re-sent solely after a worker restart.
 */
@Data
@TableName("memory_aggregation_batch")
public class MemoryAggregationBatch {
    public static final String PENDING = "PENDING", RUNNING = "RUNNING", SUCCESS = "SUCCESS", FAILED = "FAILED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String inputHash;
    private String candidateKey;
    private String sourceObservationIds;
    private String status;
    private Integer memoryItemCount;
    private String agentRunId;
    private String leaseToken;
    private String errorMessage;
    private Instant createdAt;
    private Instant finishedAt;
}
