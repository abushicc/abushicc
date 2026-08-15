package com.example.relationshipagent.analysis.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Privacy-safe audit row: summaries and usage only, never prompt or model output bodies.
 */
@Data
@TableName("agent_run")
public class AgentRun {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String agentType;
    private String inputSummary;
    private String outputSummary;
    private String modelName;
    private String providerName;
    private String tokenUsage;
    private Long durationMs;
    private String status;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
}
