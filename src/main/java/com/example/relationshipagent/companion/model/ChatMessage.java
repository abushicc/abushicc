package com.example.relationshipagent.companion.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * A message from the generated conversation domain; never an original-history input.
 */
@Data
@TableName("chat_message")
public class ChatMessage {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
    public static final String PROVENANCE_USER_INPUT = "USER_INPUT";
    public static final String PROVENANCE_GENERATED = "GENERATED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String chatSessionId;
    private String role;
    private String content;
    private String provenance;
    private String usedMemoryIds;
    private String usedSessionIds;
    private Instant createdAt;
    private String replyToMessageId;
    private String clientRequestId;
    private String usedChunkIds;
    private String inputHash;
    private String modelName;
    private String providerName;
    private String agentRunId;
    private String safetyJson;
}
