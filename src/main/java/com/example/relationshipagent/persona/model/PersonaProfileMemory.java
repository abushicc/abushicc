package com.example.relationshipagent.persona.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Evidence link from a Persona feature path to an ACTIVE Memory item.
 */
@Data
@TableName("persona_profile_memory")
public class PersonaProfileMemory {
    private String personaProfileId;
    private String memoryItemId;
    private String featurePath;
    private Integer ordinal;
}
