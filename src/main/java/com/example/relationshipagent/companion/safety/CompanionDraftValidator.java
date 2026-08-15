package com.example.relationshipagent.companion.safety;

import com.example.relationshipagent.companion.agent.CompanionReplyDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces grounding and safety before an assistant message can be persisted.
 */
@Component
public class CompanionDraftValidator {
    private final ObjectMapper json;

    public CompanionDraftValidator(ObjectMapper json) {
        this.json = json;
    }

    public ValidatedReply validate(String output, Set<String> memoryAllowlist, Set<String> chunkAllowlist, int maxReplyChars) {
        return validate(output, memoryAllowlist, chunkAllowlist, maxReplyChars, null);
    }

    /**
     * expectedHistoryStance is set only when retrieval has determined history availability.
     */
    public ValidatedReply validate(String output, Set<String> memoryAllowlist, Set<String> chunkAllowlist,
                                   int maxReplyChars, String expectedHistoryStance) {
        try {
            CompanionReplyDraft draft = json.readValue(output, CompanionReplyDraft.class);
            List<String> errors = errors(draft, memoryAllowlist, chunkAllowlist, maxReplyChars, expectedHistoryStance);
            if (!errors.isEmpty()) throw new InvalidDraftException(String.join(",", errors));
            return new ValidatedReply(draft.reply().trim(), List.copyOf(orEmpty(draft.usedMemoryIds())),
                    List.copyOf(orEmpty(draft.usedChunkIds())), draft.historyStance(), draft.safetyMode(), List.copyOf(orEmpty(draft.limitations())));
        } catch (InvalidDraftException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidDraftException("INVALID_JSON");
        }
    }

    private List<String> errors(CompanionReplyDraft draft, Set<String> memories, Set<String> chunks, int max,
                                String expectedHistoryStance) {
        if (draft == null || !CompanionReplyDraft.SCHEMA_VERSION.equals(draft.schemaVersion()))
            return List.of("INVALID_SCHEMA_VERSION");
        String reply = Optional.ofNullable(draft.reply()).orElse("").trim();
        if (reply.isEmpty() || reply.length() > max || reply.chars().anyMatch(Character::isISOControl))
            return List.of("INVALID_REPLY_LENGTH");
        Set<String> memoryIds = new LinkedHashSet<>(orEmpty(draft.usedMemoryIds()));
        Set<String> chunkIds = new LinkedHashSet<>(orEmpty(draft.usedChunkIds()));
        if (!memories.containsAll(memoryIds) || !chunks.containsAll(chunkIds)) return List.of("UNKNOWN_REFERENCE");
        if (!Set.of(CompanionReplyDraft.GROUNDED, CompanionReplyDraft.NO_EVIDENCE, CompanionReplyDraft.NOT_APPLICABLE).contains(draft.historyStance()))
            return List.of("INVALID_HISTORY_STANCE");
        if (expectedHistoryStance != null && !expectedHistoryStance.equals(draft.historyStance()))
            return List.of("HISTORY_STANCE_MISMATCH");
        if (CompanionReplyDraft.GROUNDED.equals(expectedHistoryStance) && !chunks.isEmpty() && chunkIds.isEmpty())
            return List.of("GROUNDED_WITHOUT_CHUNK");
        if (!Set.of(CompanionReplyDraft.NORMAL, CompanionReplyDraft.SAFE_COMPLETION, CompanionReplyDraft.REFUSAL).contains(draft.safetyMode()))
            return List.of("INVALID_SAFETY_MODE");
        if (CompanionReplyDraft.GROUNDED.equals(draft.historyStance()) && memoryIds.isEmpty() && chunkIds.isEmpty())
            return List.of("GROUNDED_WITHOUT_REFERENCE");
        if (CompanionReplyDraft.NO_EVIDENCE.equals(draft.historyStance()) && !chunkIds.isEmpty())
            return List.of("NO_EVIDENCE_WITH_CHUNK");
        if (reply.matches("(?s).*?(我是|我就是真实).*?(本人|她).*")) return List.of("IDENTITY_CLAIM");
        return List.of();
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record ValidatedReply(String reply, List<String> usedMemoryIds, List<String> usedChunkIds,
                                 String historyStance, String safetyMode, List<String> limitations) {
    }

    public static class InvalidDraftException extends RuntimeException {
        public InvalidDraftException(String code) {
            super(code);
        }
    }
}
