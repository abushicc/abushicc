package com.example.relationshipagent.companion.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Claim/generate/complete audit record. attemptToken is the write lease for one remote call.
 */
@Data
@TableName("companion_turn")
public class CompanionTurn {
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String chatSessionId;
    private String clientRequestId;
    private String userMessageId;
    private String assistantMessageId;
    private String requestHash;
    private String inputHash;
    private String contextRefsJson;
    private String retrievalJson;
    private String status;
    private Integer attemptCount;
    private String attemptToken;
    private String modelName;
    private String providerName;
    private String agentRunId;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
