package com.example.relationshipagent.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.memory.model.MemoryObservation;
import com.example.relationshipagent.memory.repository.MemoryObservationRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import com.example.relationshipagent.processing.ProcessingJobService;

/**
 * Deterministic first pass for M5. Embeddings may rank candidates later, but never create a merge group alone.
 */
@Service
public class MemoryAggregationCandidateService {
    private final MemoryObservationRepository observations;

    public MemoryAggregationCandidateService(MemoryObservationRepository observations) {
        this.observations = observations;
    }

    public List<Candidate> find(String chatFileId, String targetPerson) {
        List<MemoryObservation> rows = observations.selectList(new LambdaQueryWrapper<MemoryObservation>()
                .eq(MemoryObservation::getChatFileId, chatFileId).eq(MemoryObservation::getTargetPerson, targetPerson)
                .eq(MemoryObservation::getStatus, MemoryObservation.STATUS_ACTIVE).eq(MemoryObservation::getValidationStatus, MemoryObservation.VALID)
                .orderByAsc(MemoryObservation::getCreatedAt).orderByAsc(MemoryObservation::getId));
        Map<GroupKey, List<MemoryObservation>> groups = rows.stream().filter(o -> o.getObservationKey() != null && !o.getObservationKey().isBlank())
                .collect(Collectors.groupingBy(o -> new GroupKey(o.getObservationKey(), o.getObservationType(), o.getPolarity()), LinkedHashMap::new, Collectors.toList()));
        // Do not call the merge model merely to rediscover a known insufficient sample.
        // FACT/EVENT may form a one-session Memory; every stable preference/pattern/value needs two sessions.
        return groups.entrySet().stream().map(e -> new Candidate(e.getKey().key(), e.getKey().type(), e.getKey().polarity(), List.copyOf(e.getValue()))).filter(Candidate::stableEnough).toList();
    }

    /**
     * Includes only immutable source identity/version fields; no chat text is placed in job metadata.
     */
    public static String fingerprint(List<Candidate> candidates) {
        String material = candidates.stream().flatMap(c -> c.observations().stream()).map(o -> String.join("|", o.getId(), String.valueOf(o.getInputHash()), String.valueOf(o.getUpdatedAt()), String.valueOf(o.getValidationStatus()))).collect(Collectors.joining("\n"));
        return ProcessingJobService.hashInput(material);
    }

    public record Candidate(String memoryKey, String memoryType, String polarity,
                            List<MemoryObservation> observations) {
        public long independentSessionCount() {
            return observations.stream().map(MemoryObservation::getSourceSessionId).filter(Objects::nonNull).distinct().count();
        }

        public boolean stableEnough() {
            if (memoryType == null) return false;
            return switch (memoryType) {
                case "FACT", "EVENT" -> !observations.isEmpty();
                case "PREFERENCE", "DISLIKE", "EMOTIONAL_PATTERN", "COMMUNICATION_PATTERN", "RELATIONSHIP_PATTERN",
                     "VALUE" -> independentSessionCount() >= 2;
                default -> false;
            };
        }
    }

    private record GroupKey(String key, String type, String polarity) {
    }
}
