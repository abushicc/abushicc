package com.example.relationshipagent.memory.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Source observation link. Composite key is enforced by PostgreSQL.
 */
@Data
@TableName("memory_item_observation")
public class MemoryItemObservation {
    private String memoryItemId;
    private String observationId;
    private String evidenceRole;
    private Integer ordinal;
}
