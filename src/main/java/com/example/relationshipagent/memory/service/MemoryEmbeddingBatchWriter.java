package com.example.relationshipagent.memory.service;

import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional write boundary for Memory vectors; it never changes Memory content or state.
 */
@Component
public class MemoryEmbeddingBatchWriter {
    private final MemoryItemRepository memories;

    public MemoryEmbeddingBatchWriter(MemoryItemRepository memories) {
        this.memories = memories;
    }

    @Transactional
    public void write(List<MemoryItem> items, float[][] vectors, String model) {
        if (items.size() != vectors.length) throw new IllegalArgumentException("embedding result count mismatch");
        for (int i = 0; i < items.size(); i++)
            memories.updateEmbedding(items.get(i).getId(), vector(vectors[i]), model);
    }

    static String vector(float[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(Float.toString(values[i]));
        }
        return out.append(']').toString();
    }
}
