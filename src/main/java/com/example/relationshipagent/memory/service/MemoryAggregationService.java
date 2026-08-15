package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.model.MemoryItemObservation;
import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.memory.repository.MemoryItemObservationRepository;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.memory.validation.MemoryMergeDraftValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Versioned persistence for validated cross-session Memory merge output.
 */
@Service
public class MemoryAggregationService {
    private final MemoryItemRepository items;
    private final MemoryItemObservationRepository links;

    public MemoryAggregationService(MemoryItemRepository items, MemoryItemObservationRepository links) {
        this.items = items;
        this.links = links;
    }

    @Transactional
    public Result write(String chatFileId, String targetPerson, String inputHash, String aggregationVersion, String promptVersion, String agentRunId, MemoryMergeDraftValidator.ValidationResult validation) {
        // Merge 结果按 memoryKey 版本化写入；新版本成功后才把旧 ACTIVE 记忆标为 SUPERSEDED。
        int created = 0, review = 0, skipped = 0;
        for (var candidate : validation.memories()) {
            if ("REJECTED".equals(candidate.status())) {
                skipped++;
                continue;
            }
            var d = candidate.draft();
            var same = items.selectOne(new LambdaQueryWrapper<MemoryItem>().eq(MemoryItem::getChatFileId, chatFileId).eq(MemoryItem::getTargetPerson, targetPerson).eq(MemoryItem::getMemoryKey, d.memoryKey()).eq(MemoryItem::getInputHash, inputHash));
            if (same != null) {
                skipped++;
                continue;
            }
            Instant now = Instant.now();
            MemoryItem old = items.selectOne(new LambdaQueryWrapper<MemoryItem>().eq(MemoryItem::getChatFileId, chatFileId).eq(MemoryItem::getTargetPerson, targetPerson).eq(MemoryItem::getMemoryKey, d.memoryKey()).eq(MemoryItem::getStatus, MemoryItem.STATUS_ACTIVE).orderByDesc(MemoryItem::getCreatedAt).last("limit 1"));
            MemoryItem row = new MemoryItem();
            row.setId(UUID.randomUUID().toString());
            row.setChatFileId(chatFileId);
            row.setTargetPerson(targetPerson);
            row.setMemoryType(d.memoryType());
            row.setContent(d.content());
            row.setEvidence("sourceObservationIds=" + String.join(",", d.sourceObservationIds()));
            row.setConfidence(BigDecimal.valueOf(Math.max(0d, Math.min(1d, d.confidence()))));
            row.setStatus(MemoryItem.STATUS_ACTIVE);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setMemoryKey(d.memoryKey());
            row.setPolarity(d.polarity());
            row.setValidFrom(d.validFrom());
            row.setValidTo(d.validTo());
            row.setInputHash(inputHash);
            row.setAggregationVersion(aggregationVersion);
            row.setPromptVersion(promptVersion);
            row.setReviewStatus("VALID".equals(candidate.status()) ? MemoryItem.REVIEW_PENDING : "REVIEW_REQUIRED");
            row.setParentMemoryId(old == null ? null : old.getId());
            row.setAgentRunId(agentRunId);
            try {
                items.insert(row);
            } catch (DuplicateKeyException race) {
                skipped++;
                continue;
            }
            if (old != null) {
                items.update(null, new LambdaUpdateWrapper<MemoryItem>().eq(MemoryItem::getId, old.getId()).eq(MemoryItem::getStatus, MemoryItem.STATUS_ACTIVE).set(MemoryItem::getStatus, MemoryItem.STATUS_SUPERSEDED).set(MemoryItem::getSupersededBy, row.getId()).set(MemoryItem::getUpdatedAt, now));
            }
            int ordinal = 0;
            for (var o : candidate.observations()) {
                MemoryItemObservation link = new MemoryItemObservation();
                link.setMemoryItemId(row.getId());
                link.setObservationId(o.getId());
                link.setEvidenceRole("SUPPORT");
                link.setOrdinal(ordinal++);
                links.insert(link);
            }
            created++;
            if (!"VALID".equals(candidate.status())) review++;
        }
        return new Result(created, review, skipped);
    }

    public record Result(int created, int reviewRequired, int skipped) {
    }
}
