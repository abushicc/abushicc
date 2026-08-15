package com.example.relationshipagent.persona.validation;

import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.persona.agent.PersonaDraft;
import com.example.relationshipagent.persona.agent.PersonaPromptFactory;
import com.example.relationshipagent.persona.input.PersonaBuildInput;
import com.example.relationshipagent.persona.input.PersonaFewShotCandidate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Enforces Memory provenance and server-selected few-shot references before Persona persistence.
 */
@Component
public class PersonaDraftValidator {
    private static final Pattern RISK = Pattern.compile("人格障碍|抑郁|焦虑症|出轨|违法|肯定|永远|从不|唯一");

    public ValidationResult validate(PersonaDraft draft, PersonaBuildInput input) {
        if (draft == null || !PersonaPromptFactory.SCHEMA_VERSION.equals(draft.schemaVersion()))
            return new ValidationResult(List.of(), List.of(), List.of("INVALID_SCHEMA_VERSION"));
        Set<String> allowed = input.memories().stream().filter(m -> MemoryItem.STATUS_ACTIVE.equals(m.getStatus()) && MemoryItem.REVIEW_APPROVED.equals(m.getReviewStatus())).map(MemoryItem::getId).collect(java.util.stream.Collectors.toSet());
        List<Feature> features = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        checkOptional("communicationStyle", draft.communicationStyle(), allowed, features, errors);
        checkAll("preferences", draft.preferences(), allowed, features, errors);
        checkAll("dislikes", draft.dislikes(), allowed, features, errors);
        checkAll("interactionPatterns", draft.interactionPatterns(), allowed, features, errors);
        checkAll("emotionalExpression", draft.emotionalExpression(), allowed, features, errors);
        checkAll("values", draft.values(), allowed, features, errors);
        checkAll("boundaries", draft.boundaries(), allowed, features, errors);
        Set<String> candidateKeys = input.fewShotCandidates().stream().map(this::key).collect(java.util.stream.Collectors.toSet());
        List<PersonaDraft.FewShotDraft> few = new ArrayList<>();
        for (var value : Optional.ofNullable(draft.fewShotExamples()).orElse(List.of())) {
            if (!candidateKeys.contains(key(value))) errors.add("INVALID_FEW_SHOT_REFERENCE");
            else few.add(value);
        }
        if (!input.fewShotCandidates().isEmpty() && few.isEmpty()) errors.add("MISSING_FEW_SHOT_EXAMPLE");
        if (few.size() > 3) errors.add("TOO_MANY_FEW_SHOT_EXAMPLES");
        return new ValidationResult(List.copyOf(features), List.copyOf(few), List.copyOf(errors));
    }

    private void checkAll(String path, List<PersonaDraft.FeatureDraft> values, Set<String> allowed, List<Feature> out, List<String> errors) {
        int i = 0;
        for (var value : Optional.ofNullable(values).orElse(List.of()))
            check(path + "[" + (i++) + "]", value, allowed, out, errors);
    }

    /**
     * communicationStyle is optional: style statistics alone cannot create a factual Persona feature.
     */
    private void checkOptional(String path, PersonaDraft.FeatureDraft value, Set<String> allowed, List<Feature> out, List<String> errors) {
        if (value == null || value.statement() == null || value.statement().isBlank()) return;
        check(path, value, allowed, out, errors);
    }

    private void check(String path, PersonaDraft.FeatureDraft value, Set<String> allowed, List<Feature> out, List<String> errors) {
        if (value == null || value.statement() == null || value.statement().isBlank()) {
            errors.add("EMPTY_FEATURE:" + path);
            return;
        }
        if (RISK.matcher(value.statement()).find()) {
            errors.add("HIGH_RISK_FEATURE:" + path);
            return;
        }
        List<String> refs = Optional.ofNullable(value.sourceMemoryIds()).orElse(List.of());
        if (refs.isEmpty() || refs.stream().anyMatch(id -> !allowed.contains(id))) {
            errors.add("INVALID_MEMORY_REFERENCE:" + path);
            return;
        }
        out.add(new Feature(path, value.statement(), List.copyOf(refs)));
    }

    private String key(PersonaFewShotCandidate value) {
        return value.sessionId() + "|" + String.join(",", value.contextMessageIds()) + "|" + String.join(",", value.targetMessageIds());
    }

    private String key(PersonaDraft.FewShotDraft value) {
        return value.sessionId() + "|" + String.join(",", Optional.ofNullable(value.contextMessageIds()).orElse(List.of())) + "|" + String.join(",", Optional.ofNullable(value.targetMessageIds()).orElse(List.of()));
    }

    public record Feature(String path, String statement, List<String> sourceMemoryIds) {
    }

    public record ValidationResult(List<Feature> features, List<PersonaDraft.FewShotDraft> fewShotExamples,
                                   List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }
}
