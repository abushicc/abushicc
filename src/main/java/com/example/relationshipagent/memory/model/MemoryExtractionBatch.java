package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Durable checkpoint for a request batch, including successful batches with zero observations.
 */
@Data
@TableName("memory_extraction_batch")
public class MemoryExtractionBatch {
    public static final String PENDING = "PENDING", RUNNING = "RUNNING", SUCCESS = "SUCCESS", FAILED = "FAILED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String inputHash;
    private String batchKey;
    private String sessionIds;
    private String status;
    private Integer observationCount;
    private String agentRunId;
    private String errorMessage;
    private Instant createdAt;
    private Instant finishedAt;
}
