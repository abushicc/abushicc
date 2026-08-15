package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Explicit human review operations. Disabling a Memory invalidates any active Persona that used the source set.
 */
@Service
public class MemoryReviewService {
    private final MemoryItemRepository memories;
    private final PersonaProfileRepository personas;

    public MemoryReviewService(MemoryItemRepository memories, PersonaProfileRepository personas) {
        this.memories = memories;
        this.personas = personas;
    }

    @Transactional
    public MemoryItem approve(String memoryId) {
        MemoryItem item = find(memoryId);
        if (!MemoryItem.STATUS_ACTIVE.equals(item.getStatus()))
            throw new BizException(ErrorCode.PARAM_INVALID, "only ACTIVE Memory may be approved");
        memories.update(null, new UpdateWrapper<MemoryItem>().eq("id", memoryId).eq("status", MemoryItem.STATUS_ACTIVE).set("review_status", MemoryItem.REVIEW_APPROVED).set("updated_at", Instant.now()));
        return memories.selectById(memoryId);
    }

    @Transactional
    public MemoryItem disable(String memoryId) {
        MemoryItem item = find(memoryId);
        if (!MemoryItem.STATUS_ACTIVE.equals(item.getStatus()))
            throw new BizException(ErrorCode.PARAM_INVALID, "only ACTIVE Memory may be disabled");
        Instant now = Instant.now();
        int changed = memories.update(null, new UpdateWrapper<MemoryItem>().eq("id", memoryId).eq("status", MemoryItem.STATUS_ACTIVE).set("status", MemoryItem.STATUS_DISABLED).set("updated_at", now));
        if (changed != 1) throw new BizException(ErrorCode.PARAM_INVALID, "Memory state changed; refresh and retry");
        personas.update(null, new UpdateWrapper<PersonaProfile>().eq("chat_file_id", item.getChatFileId()).eq("target_person", item.getTargetPerson()).eq("status", PersonaProfile.STATUS_ACTIVE).set("status", PersonaProfile.STATUS_SUPERSEDED).set("updated_at", now));
        return memories.selectById(memoryId);
    }

    private MemoryItem find(String id) {
        MemoryItem item = memories.selectById(id);
        if (item == null) throw new BizException(ErrorCode.MEMORY_ITEM_NOT_FOUND);
        return item;
    }
}
