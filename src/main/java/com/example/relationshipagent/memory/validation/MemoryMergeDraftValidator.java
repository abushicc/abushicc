package com.example.relationshipagent.memory.validation;

import com.example.relationshipagent.memory.agent.MemoryMergeDraft;
import com.example.relationshipagent.memory.agent.MemoryMergePromptFactory;
import com.example.relationshipagent.memory.model.MemoryObservation;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Server-side validation for merge drafts; model similarity never bypasses deterministic gates.
 */
@Component
public class MemoryMergeDraftValidator {
    private static final Pattern HIGH_RISK = Pattern.compile("人格障碍|抑郁|焦虑症|出轨|违法|唯一原因|肯定|永远|从不");

    public ValidationResult validate(MemoryMergeDraft draft, List<MemoryObservation> source, String targetPerson) {
        if (draft == null || !MemoryMergePromptFactory.SCHEMA_VERSION.equals(draft.schemaVersion()))
            return new ValidationResult(List.of(), List.of("INVALID_SCHEMA_VERSION"));
        Map<String, MemoryObservation> byId = new HashMap<>();
        for (var o : source) byId.put(o.getId(), o);
        List<ValidatedMemory> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (var item : Optional.ofNullable(draft.items()).orElse(List.of())) {
            List<String> e = new ArrayList<>();
            List<MemoryObservation> refs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (String id : Optional.ofNullable(item.sourceObservationIds()).orElse(List.of())) {
                if (!ids.add(id)) e.add("DUPLICATE_OBSERVATION_REF:" + id);
                var o = byId.get(id);
                if (o == null) e.add("UNKNOWN_OBSERVATION_REF:" + id);
                else refs.add(o);
            }
            if (item.memoryKey() == null || item.memoryKey().isBlank()) e.add("EMPTY_MEMORY_KEY");
            if (item.content() == null || item.content().isBlank()) e.add("EMPTY_CONTENT");
            if (!MemoryMergePromptFactory.TYPES.contains(item.memoryType())) e.add("INVALID_TYPE");
            if (!MemoryMergePromptFactory.POLARITIES.contains(item.polarity())) e.add("INVALID_POLARITY");
            if (item.validFrom() != null && item.validTo() != null && item.validFrom().isAfter(item.validTo()))
                e.add("INVALID_TIME_RANGE");
            if (refs.isEmpty()) e.add("MISSING_SOURCE");
            for (var o : refs) {
                if (!Objects.equals(targetPerson, o.getTargetPerson())) e.add("TARGET_MISMATCH");
                if (!Objects.equals(item.memoryKey(), o.getObservationKey())) e.add("KEY_MISMATCH");
                if (!Objects.equals(item.memoryType(), o.getObservationType())) e.add("TYPE_MISMATCH");
                if (!Objects.equals(item.polarity(), o.getPolarity())) e.add("POLARITY_MISMATCH");
                if (!MemoryObservation.VALID.equals(o.getValidationStatus())) e.add("OBSERVATION_NOT_VALID");
            }
            long sessions = refs.stream().map(MemoryObservation::getSourceSessionId).filter(Objects::nonNull).distinct().count();
            boolean pattern = item.memoryType() != null && (item.memoryType().endsWith("PATTERN") || "VALUE".equals(item.memoryType()));
            boolean preference = "PREFERENCE".equals(item.memoryType()) || "DISLIKE".equals(item.memoryType());
            if ((pattern || preference) && sessions < 2) e.add("INSUFFICIENT_INDEPENDENT_SESSIONS");
            if (item.content() != null && HIGH_RISK.matcher(item.content()).find()) e.add("HIGH_RISK_CONTENT");
            String status = e.stream().anyMatch(x -> x.startsWith("UNKNOWN") || x.contains("MISMATCH") || x.startsWith("EMPTY") || x.startsWith("MISSING") || x.equals("OBSERVATION_NOT_VALID") || x.equals("INVALID_TYPE") || x.equals("INVALID_POLARITY") || x.equals("HIGH_RISK_CONTENT")) ? "REJECTED" : (!e.isEmpty() ? "REVIEW_REQUIRED" : "VALID");
            out.add(new ValidatedMemory(item, status, List.copyOf(refs), sessions, List.copyOf(e)));
        }
        return new ValidationResult(List.copyOf(out), List.copyOf(errors));
    }

    public record ValidatedMemory(MemoryMergeDraft.MemoryItemDraft draft, String status,
                                  List<MemoryObservation> observations, long independentSessions, List<String> errors) {
    }

    public record ValidationResult(List<ValidatedMemory> memories, List<String> errors) {
    }
}
