package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.memory.evidence.ObservationBatch;
import com.example.relationshipagent.memory.model.MemoryExtractionBatch;
import com.example.relationshipagent.memory.repository.MemoryExtractionBatchRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-batch idempotency boundary. It prevents repeat model calls after a worker restart, even for empty output.
 */
@Service
public class MemoryExtractionBatchService {
    private final MemoryExtractionBatchRepository batches;

    public MemoryExtractionBatchService(MemoryExtractionBatchRepository batches) {
        this.batches = batches;
    }

    public MemoryExtractionBatch claim(String chatFileId, String targetPerson, String inputHash, ObservationBatch source) {
        MemoryExtractionBatch existing = find(chatFileId, targetPerson, inputHash, source.batchKey());
        if (existing == null) {
            MemoryExtractionBatch row = new MemoryExtractionBatch();
            row.setId(UUID.randomUUID().toString());
            row.setChatFileId(chatFileId);
            row.setTargetPerson(targetPerson);
            row.setInputHash(inputHash);
            row.setBatchKey(source.batchKey());
            row.setSessionIds(source.packets().stream().map(p -> p.sessionId()).reduce((a, b) -> a + "," + b).orElse(""));
            row.setStatus(MemoryExtractionBatch.PENDING);
            row.setObservationCount(0);
            row.setCreatedAt(Instant.now());
            try {
                batches.insert(row);
                existing = row;
            } catch (DuplicateKeyException race) {
                existing = find(chatFileId, targetPerson, inputHash, source.batchKey());
                if (existing == null) throw race;
            }
        }
        if (MemoryExtractionBatch.SUCCESS.equals(existing.getStatus())) return null;
        int updated = batches.update(null, new LambdaUpdateWrapper<MemoryExtractionBatch>().eq(MemoryExtractionBatch::getId, existing.getId()).in(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.PENDING, MemoryExtractionBatch.FAILED).set(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.RUNNING).set(MemoryExtractionBatch::getErrorMessage, null));
        return updated == 1 ? batches.selectById(existing.getId()) : null;
    }

    public void success(String id, String agentRunId, int count) {
        batches.update(null, new LambdaUpdateWrapper<MemoryExtractionBatch>().eq(MemoryExtractionBatch::getId, id).eq(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.RUNNING).set(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.SUCCESS).set(MemoryExtractionBatch::getAgentRunId, agentRunId).set(MemoryExtractionBatch::getObservationCount, count).set(MemoryExtractionBatch::getFinishedAt, Instant.now()));
    }

    public void fail(String id, String message) {
        batches.update(null, new LambdaUpdateWrapper<MemoryExtractionBatch>().eq(MemoryExtractionBatch::getId, id).eq(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.RUNNING).set(MemoryExtractionBatch::getStatus, MemoryExtractionBatch.FAILED).set(MemoryExtractionBatch::getErrorMessage, message).set(MemoryExtractionBatch::getFinishedAt, Instant.now()));
    }

    private MemoryExtractionBatch find(String file, String target, String input, String key) {
        return batches.selectOne(new LambdaQueryWrapper<MemoryExtractionBatch>().eq(MemoryExtractionBatch::getChatFileId, file).eq(MemoryExtractionBatch::getTargetPerson, target).eq(MemoryExtractionBatch::getInputHash, input).eq(MemoryExtractionBatch::getBatchKey, key));
    }
}
