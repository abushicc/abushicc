package com.example.relationshipagent.companion.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Persistent generated-conversation session. Its Persona is fixed at creation time.
 */
@Data
@TableName("chat_session")
public class ChatSession {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDED = "ENDED";

    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String personaVersion;
    private String personaProfileId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String currentTopic;
    private String topicTerms;
    private Integer turnsSinceLastSearch;
    private Instant lastSearchAt;
    private Instant lastMessageAt;
    private Instant endedAt;
    private String contextVersion;
    private Integer version;
}
