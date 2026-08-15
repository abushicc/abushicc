package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.model.MemorySimilarityCandidate;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only cross-key candidate lookup. Similarity is evidence for review, never an automatic merge instruction.
 */
@Service
public class MemorySimilarityCandidateService {
    private final MemoryItemRepository memories;

    public MemorySimilarityCandidateService(MemoryItemRepository memories) {
        this.memories = memories;
    }

    public List<MemorySimilarityCandidate> find(String chatFileId, String memoryId, int limit) {
        MemoryItem source = memories.selectById(memoryId);
        if (source == null || !chatFileId.equals(source.getChatFileId()))
            throw new BizException(ErrorCode.MEMORY_ITEM_NOT_FOUND);
        if (!MemoryItem.STATUS_ACTIVE.equals(source.getStatus()) || source.getEmbedding() == null || source.getEmbeddingModel() == null)
            throw new BizException(ErrorCode.MEMORY_PREREQUISITE_MISSING, "Memory has not been embedded");
        return memories.selectSimilarDifferentKey(memoryId, Math.min(Math.max(limit, 1), 20));
    }
}
