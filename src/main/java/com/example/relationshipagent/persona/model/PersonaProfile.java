package com.example.relationshipagent.persona.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Immutable Persona revision. Only a reviewed revision may become ACTIVE.
 */
@Data
@TableName("persona_profile")
public class PersonaProfile {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String STATUS_REJECTED = "REJECTED";
    @TableId(type = IdType.INPUT)
    private String id;
    private String chatFileId;
    private String targetPerson;
    private String version;
    private String profileJson;
    private String basedOnMemoryIds;
    private String status;
    private Instant createdAt;
    private String inputHash;
    private String personaVersion;
    private String promptVersion;
    private String modelName;
    private String providerName;
    private String parentProfileId;
    private String changeType;
    private String validationJson;
    private String coverageNote;
    private String agentRunId;
    private Instant activatedAt;
    private Instant updatedAt;
}
