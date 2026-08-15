package com.example.relationshipagent.memory.validation;

import com.example.relationshipagent.memory.agent.MemoryPromptFactory;
import com.example.relationshipagent.memory.agent.ObservationDraft;
import com.example.relationshipagent.memory.evidence.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Applies evidence scope, type and safety checks before an Observation becomes a derived asset.
 */
@Component
public class ObservationDraftValidator {
    private static final Pattern HIGH_RISK = Pattern.compile("人格障碍|抑郁|焦虑症|出轨|违法|唯一原因|肯定|永远|从不");
    private static final Pattern SESSION_SCOPED_KEY = Pattern.compile("(?i)^(?:ses|session)[-_]?[0-9]");
    private static final Pattern REST_ENCOURAGEMENT_KEY = Pattern.compile("^encourages(?:_food_and)?_rest_when_other_is_tired$");
    private static final Pattern SLEEP_CONCERN_KEY = Pattern.compile(
            "^(?:checks_on_other(?:s)?_sleep(?:_and_schedule)?|shows_concern_about_others_poor_sleep)$");
    private static final Pattern HEALTH_CONCERN_KEY = Pattern.compile(
            "^(?:encourages_other_to_care_for_(?:health|stomach)|urges_other_to_seek_medical_care)$");
    private final MemoryConfidenceCalibrator calibrator = new MemoryConfidenceCalibrator();

    public ValidationResult validate(ObservationDraft draft, List<ObservationEvidencePacket> packets) {
        Map<String, ObservationEvidencePacket> bySession = new HashMap<>();
        for (var p : packets) bySession.put(p.sessionRefId(), p);
        List<ValidatedObservation> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (draft == null || !MemoryPromptFactory.SCHEMA_VERSION.equals(draft.schemaVersion()))
            return new ValidationResult(List.of(), List.of("INVALID_SCHEMA_VERSION"));
        Set<String> seenSessions = new HashSet<>();
        for (var group : Optional.ofNullable(draft.sessions()).orElse(List.of())) {
            var packet = bySession.get(group.sessionRefId());
            if (packet == null) {
                errors.add("UNKNOWN_SESSION_REF:" + group.sessionRefId());
                continue;
            }
            if (!seenSessions.add(group.sessionRefId())) {
                errors.add("DUPLICATE_SESSION_REF:" + group.sessionRefId());
                continue;
            }
            Map<String, ObservationEvidenceRef> refs = new HashMap<>();
            for (var ref : packet.messages()) refs.put(ref.evidenceRefId(), ref);
            for (var item : Optional.ofNullable(group.observations()).orElse(List.of()))
                out.add(validateItem(item, packet, refs));
        }
        return new ValidationResult(List.copyOf(out), List.copyOf(errors));
    }

    private ValidatedObservation validateItem(ObservationDraft.ObservationItemDraft item, ObservationEvidencePacket packet, Map<String, ObservationEvidenceRef> refs) {
        item = normalize(item);
        List<String> errors = new ArrayList<>();
        List<ObservationEvidenceRef> support = resolve(item.supportEvidenceRefIds(), refs, errors, "SUPPORT");
        List<ObservationEvidenceRef> counter = resolve(item.counterEvidenceRefIds(), refs, errors, "COUNTER");
        Set<String> overlap = new HashSet<>(Optional.ofNullable(item.supportEvidenceRefIds()).orElse(List.of()));
        overlap.retainAll(Optional.ofNullable(item.counterEvidenceRefIds()).orElse(List.of()));
        if (!overlap.isEmpty()) errors.add("SUPPORT_COUNTER_OVERLAP");
        if (item.observationKey() == null || item.observationKey().isBlank()) errors.add("EMPTY_OBSERVATION_KEY");
        else if (SESSION_SCOPED_KEY.matcher(item.observationKey()).find()) errors.add("SESSION_SCOPED_OBSERVATION_KEY");
        if (!MemoryPromptFactory.TYPES.contains(item.observationType())) errors.add("INVALID_TYPE");
        if (item.statement() == null || item.statement().isBlank()) errors.add("EMPTY_STATEMENT");
        if (support.isEmpty()) errors.add("MISSING_SUPPORT");
        if (item.statement() != null && HIGH_RISK.matcher(item.statement()).find())
            errors.add("HIGH_RISK_OR_ABSOLUTE_WORDING");
        boolean pattern = item.observationType() != null && item.observationType().endsWith("PATTERN") || "VALUE".equals(item.observationType());
        String status = errors.stream().anyMatch(e -> e.startsWith("UNKNOWN") || e.equals("MISSING_SUPPORT") || e.equals("INVALID_TYPE") || e.equals("EMPTY_STATEMENT") || e.equals("EMPTY_OBSERVATION_KEY") || e.equals("SESSION_SCOPED_OBSERVATION_KEY") || e.equals("SUPPORT_COUNTER_OVERLAP")) ? "REJECTED" : (!errors.isEmpty() || pattern && support.size() < 2 ? "REVIEW_REQUIRED" : "VALID");
        double confidence = calibrator.calibrate(item.observationType(), item.confidence(), support.size(), !counter.isEmpty());
        return new ValidatedObservation(item, packet, status, confidence, List.copyOf(support), List.copyOf(counter), List.copyOf(errors));
    }

    /**
     * Maps a verified synonymous key family to one conservative, mergeable vocabulary entry.
     */
    private ObservationDraft.ObservationItemDraft normalize(ObservationDraft.ObservationItemDraft item) {
        if (item != null && item.observationKey() != null && REST_ENCOURAGEMENT_KEY.matcher(item.observationKey()).matches()) {
            return new ObservationDraft.ObservationItemDraft("encourages_other_to_rest", "COMMUNICATION_PATTERN", item.statement(), "POSITIVE", item.confidence(), item.supportEvidenceRefIds(), item.counterEvidenceRefIds(), item.uncertaintyNote());
        }
        if (item != null && item.observationKey() != null && SLEEP_CONCERN_KEY.matcher(item.observationKey()).matches()) {
            return new ObservationDraft.ObservationItemDraft("checks_on_others_sleep", "COMMUNICATION_PATTERN", item.statement(), "POSITIVE", item.confidence(), item.supportEvidenceRefIds(), item.counterEvidenceRefIds(), item.uncertaintyNote());
        }
        if (item != null && item.observationKey() != null && HEALTH_CONCERN_KEY.matcher(item.observationKey()).matches()) {
            return new ObservationDraft.ObservationItemDraft("encourages_other_to_care_for_health", "COMMUNICATION_PATTERN", item.statement(), "POSITIVE", item.confidence(), item.supportEvidenceRefIds(), item.counterEvidenceRefIds(), item.uncertaintyNote());
        }
        return item;
    }

    private List<ObservationEvidenceRef> resolve(List<String> ids, Map<String, ObservationEvidenceRef> refs, List<String> errors, String role) {
        List<ObservationEvidenceRef> out = new ArrayList<>();
        for (String id : Optional.ofNullable(ids).orElse(List.of())) {
            var ref = refs.get(id);
            if (ref == null) errors.add("UNKNOWN_" + role + "_REF:" + id);
            else out.add(ref);
        }
        return out;
    }

    public record ValidatedObservation(ObservationDraft.ObservationItemDraft draft, ObservationEvidencePacket packet,
                                       String status, double confidence, List<ObservationEvidenceRef> support,
                                       List<ObservationEvidenceRef> counter, List<String> errors) {
    }

    public record ValidationResult(List<ValidatedObservation> observations, List<String> reportErrors) {
    }
}
