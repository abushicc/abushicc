package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.memory.model.MemoryAggregationBatch;
import com.example.relationshipagent.memory.repository.MemoryAggregationBatchRepository;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * CAS-protected M5 checkpoint, including the insert-race path.
 */
@Service
public class MemoryAggregationBatchService {
    private final MemoryAggregationBatchRepository batches;

    public MemoryAggregationBatchService(MemoryAggregationBatchRepository batches) {
        this.batches = batches;
    }

    public MemoryAggregationBatch claim(String chatFileId, String targetPerson, String inputHash, MemoryAggregationCandidateService.Candidate candidate, String leaseToken) {
        String key = key(candidate);
        MemoryAggregationBatch existing = find(chatFileId, targetPerson, inputHash, key);
        if (existing == null) {
            MemoryAggregationBatch row = new MemoryAggregationBatch();
            row.setId(UUID.randomUUID().toString());
            row.setChatFileId(chatFileId);
            row.setTargetPerson(targetPerson);
            row.setInputHash(inputHash);
            row.setCandidateKey(key);
            row.setSourceObservationIds(candidate.observations().stream().map(o -> o.getId()).reduce((a, b) -> a + "," + b).orElse(""));
            row.setStatus(MemoryAggregationBatch.PENDING);
            row.setMemoryItemCount(0);
            row.setCreatedAt(Instant.now());
            try {
                batches.insert(row);
                existing = row;
            } catch (DuplicateKeyException race) {
                existing = find(chatFileId, targetPerson, inputHash, key);
                if (existing == null) throw race;
            }
        }
        if (MemoryAggregationBatch.SUCCESS.equals(existing.getStatus())) return null;
        int changed = batches.update(null, new LambdaUpdateWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getId, existing.getId()).in(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.PENDING, MemoryAggregationBatch.FAILED).set(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.RUNNING).set(MemoryAggregationBatch::getLeaseToken, leaseToken).set(MemoryAggregationBatch::getErrorMessage, null));
        return changed == 1 ? batches.selectById(existing.getId()) : null;
    }

    public boolean isClaimActive(String id, String leaseToken) {
        return batches.selectCount(new LambdaQueryWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getId, id).eq(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.RUNNING).eq(MemoryAggregationBatch::getLeaseToken, leaseToken)) == 1;
    }

    /**
     * Requeues only interrupted, unfinished candidates after the owning job lease was taken over.
     */
    public void requeueRunning(String chatFileId, String targetPerson, String inputHash) {
        batches.update(null, new LambdaUpdateWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getChatFileId, chatFileId).eq(MemoryAggregationBatch::getTargetPerson, targetPerson).eq(MemoryAggregationBatch::getInputHash, inputHash).eq(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.RUNNING).set(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.PENDING).set(MemoryAggregationBatch::getLeaseToken, null).set(MemoryAggregationBatch::getErrorMessage, "requeued after job lease takeover"));
    }

    public void success(String id, String leaseToken, String agentRunId, int count) {
        batches.update(null, new LambdaUpdateWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getId, id).eq(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.RUNNING).eq(MemoryAggregationBatch::getLeaseToken, leaseToken).set(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.SUCCESS).set(MemoryAggregationBatch::getAgentRunId, agentRunId).set(MemoryAggregationBatch::getMemoryItemCount, count).set(MemoryAggregationBatch::getFinishedAt, Instant.now()));
    }

    public void fail(String id, String leaseToken, String message) {
        batches.update(null, new LambdaUpdateWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getId, id).eq(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.RUNNING).eq(MemoryAggregationBatch::getLeaseToken, leaseToken).set(MemoryAggregationBatch::getStatus, MemoryAggregationBatch.FAILED).set(MemoryAggregationBatch::getErrorMessage, message).set(MemoryAggregationBatch::getFinishedAt, Instant.now()));
    }

    private MemoryAggregationBatch find(String file, String target, String input, String key) {
        return batches.selectOne(new LambdaQueryWrapper<MemoryAggregationBatch>().eq(MemoryAggregationBatch::getChatFileId, file).eq(MemoryAggregationBatch::getTargetPerson, target).eq(MemoryAggregationBatch::getInputHash, input).eq(MemoryAggregationBatch::getCandidateKey, key));
    }

    private static String key(MemoryAggregationCandidateService.Candidate c) {
        return ProcessingJobService.hashInput(c.memoryKey(), String.valueOf(c.memoryType()), String.valueOf(c.polarity()), c.observations().stream().map(o -> o.getId()).reduce((a, b) -> a + "," + b).orElse(""));
    }
}
