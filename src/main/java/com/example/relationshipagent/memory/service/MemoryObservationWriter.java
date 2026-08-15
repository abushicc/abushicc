package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.memory.model.*;
import com.example.relationshipagent.memory.repository.*;
import com.example.relationshipagent.memory.validation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Persists only validated observations and database-rehydrated evidence as one batch transaction.
 */
@Service
public class MemoryObservationWriter {
    private final MemoryObservationRepository observations;
    private final MemoryObservationEvidenceRepository evidence;
    private final ObservationEvidenceResolver resolver;
    private final ObjectMapper json;

    public MemoryObservationWriter(MemoryObservationRepository observations, MemoryObservationEvidenceRepository evidence, ObservationEvidenceResolver resolver, ObjectMapper json) {
        this.observations = observations;
        this.evidence = evidence;
        this.resolver = resolver;
        this.json = json;
    }

    @Transactional
    public WriteResult write(String chatFileId, String inputHash, String extractorVersion, String promptVersion, String agentRunId, ObservationDraftValidator.ValidationResult validation) {
        int valid = 0, review = 0, skipped = 0;
        for (var candidate : validation.observations()) {
            if ("REJECTED".equals(candidate.status())) {
                skipped++;
                continue;
            }
            MemoryObservation existing = observations.selectOne(new LambdaQueryWrapper<MemoryObservation>()
                    .eq(MemoryObservation::getChatFileId, chatFileId)
                    .eq(MemoryObservation::getTargetPerson, candidate.packet().targetPerson())
                    .eq(MemoryObservation::getSourceSessionId, candidate.packet().sessionId())
                    .eq(MemoryObservation::getObservationKey, candidate.draft().observationKey())
                    .eq(MemoryObservation::getInputHash, inputHash));
            if (existing != null) {
                skipped++;
                continue;
            }
            List<ResolvedRole> resolved = resolveAll(chatFileId, candidate);
            if (resolved == null)
                throw new IllegalStateException("Observation evidence no longer belongs to its source session");
            Instant now = Instant.now();
            MemoryObservation row = new MemoryObservation();
            row.setId(UUID.randomUUID().toString());
            row.setChatFileId(chatFileId);
            row.setTargetPerson(candidate.packet().targetPerson());
            row.setObservationType(candidate.draft().observationType());
            row.setStatement(candidate.draft().statement());
            row.setEvidence(asJson(Map.of("sourceSessionId", candidate.packet().sessionId())));
            row.setConfidence(BigDecimal.valueOf(candidate.confidence()));
            row.setSource("LLM");
            row.setStatus(MemoryObservation.STATUS_ACTIVE);
            row.setCreatedAt(now);
            row.setSourceSessionId(candidate.packet().sessionId());
            row.setObservationKey(candidate.draft().observationKey());
            row.setPolarity(candidate.draft().polarity());
            row.setValidFrom(candidate.packet().startTime());
            row.setValidTo(candidate.packet().endTime());
            row.setInputHash(inputHash);
            row.setExtractorVersion(extractorVersion);
            row.setPromptVersion(promptVersion);
            row.setValidationStatus(candidate.status());
            row.setValidationError(String.join(",", candidate.errors()));
            row.setAgentRunId(agentRunId);
            row.setUpdatedAt(now);
            observations.insert(row);
            int ordinal = 0;
            for (ResolvedRole value : resolved) {
                MemoryObservationEvidence e = new MemoryObservationEvidence();
                e.setId(UUID.randomUUID().toString());
                e.setObservationId(row.getId());
                e.setEvidenceRole(value.role());
                e.setMessageId(value.evidence().messageId());
                e.setSessionId(value.evidence().sessionId());
                e.setQuoteText(value.evidence().quoteText());
                e.setMessageTime(value.evidence().messageTime());
                e.setOrdinal(ordinal++);
                evidence.insert(e);
            }
            if ("VALID".equals(candidate.status())) valid++;
            else review++;
        }
        return new WriteResult(valid, review, skipped);
    }

    /**
     * Promote a completed replacement run atomically at the status level only after every batch succeeded.
     */
    @Transactional
    public void supersedePreviousInputs(String chatFileId, String targetPerson, String inputHash) {
        observations.update(null, new LambdaUpdateWrapper<MemoryObservation>().eq(MemoryObservation::getChatFileId, chatFileId).eq(MemoryObservation::getTargetPerson, targetPerson).eq(MemoryObservation::getStatus, MemoryObservation.STATUS_ACTIVE).ne(MemoryObservation::getInputHash, inputHash).set(MemoryObservation::getStatus, MemoryObservation.STATUS_SUPERSEDED).set(MemoryObservation::getUpdatedAt, Instant.now()));
    }

    /**
     * A targeted benchmark rerun may replace only the sessions it actually re-read, never the rest of a chat file.
     */
    @Transactional
    public void supersedePreviousInputsForSessions(String chatFileId, String targetPerson, String inputHash, Collection<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return;
        observations.update(null, new LambdaUpdateWrapper<MemoryObservation>().eq(MemoryObservation::getChatFileId, chatFileId).eq(MemoryObservation::getTargetPerson, targetPerson).eq(MemoryObservation::getStatus, MemoryObservation.STATUS_ACTIVE).in(MemoryObservation::getSourceSessionId, sessionIds).ne(MemoryObservation::getInputHash, inputHash).set(MemoryObservation::getStatus, MemoryObservation.STATUS_SUPERSEDED).set(MemoryObservation::getUpdatedAt, Instant.now()));
    }

    private List<ResolvedRole> resolveAll(String chatFileId, ObservationDraftValidator.ValidatedObservation candidate) {
        List<ResolvedRole> result = new ArrayList<>();
        for (var ref : candidate.support()) {
            var resolved = resolver.resolve(chatFileId, candidate.packet().sessionId(), ref);
            if (resolved == null) return null;
            result.add(new ResolvedRole(MemoryObservationEvidence.SUPPORT, resolved));
        }
        for (var ref : candidate.counter()) {
            var resolved = resolver.resolve(chatFileId, candidate.packet().sessionId(), ref);
            if (resolved == null) return null;
            result.add(new ResolvedRole(MemoryObservationEvidence.COUNTER, resolved));
        }
        return result;
    }

    private String asJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize observation metadata", e);
        }
    }

    private record ResolvedRole(String role, ObservationEvidenceResolver.ResolvedObservationEvidence evidence) {
    }

    public record WriteResult(int valid, int reviewRequired, int skipped) {
    }
}
