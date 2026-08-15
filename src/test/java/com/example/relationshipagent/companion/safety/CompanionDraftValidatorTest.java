package com.example.relationshipagent.companion.safety;

import com.example.relationshipagent.companion.agent.CompanionReplyDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanionDraftValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CompanionDraftValidator validator = new CompanionDraftValidator(json);

    @Test void acceptsGroundedReplyUsingOnlyAllowlist() throws Exception {
        String output = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "我记得那件事。", "GROUNDED", List.of("m1"), List.of("c1"), "NORMAL", List.of()));
        var result = validator.validate(output, Set.of("m1"), Set.of("c1"), 1200);
        assertThat(result.usedMemoryIds()).containsExactly("m1"); assertThat(result.usedChunkIds()).containsExactly("c1");
    }

    @Test void rejectsUnknownOrUngroundedReferences() throws Exception {
        String unknown = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "嗯。", "GROUNDED", List.of("other"), List.of(), "NORMAL", List.of()));
        assertThatThrownBy(() -> validator.validate(unknown, Set.of("m1"), Set.of(), 1200)).hasMessage("UNKNOWN_REFERENCE");
        String ungrounded = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "嗯。", "GROUNDED", List.of(), List.of(), "NORMAL", List.of()));
        assertThatThrownBy(() -> validator.validate(ungrounded, Set.of(), Set.of(), 1200)).hasMessage("GROUNDED_WITHOUT_REFERENCE");
    }

    @Test void rejectsNoEvidenceClaimThatCitesChunk() throws Exception {
        String output = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "我不记得。", "NO_EVIDENCE", List.of(), List.of("c1"), "NORMAL", List.of()));
        assertThatThrownBy(() -> validator.validate(output, Set.of(), Set.of("c1"), 1200)).hasMessage("NO_EVIDENCE_WITH_CHUNK");
    }

    @Test void rejectsHistoryStanceThatConflictsWithRetrievedEvidence() throws Exception {
        String output = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "我不记得。", "NO_EVIDENCE", List.of(), List.of(), "NORMAL", List.of()));
        assertThatThrownBy(() -> validator.validate(output, Set.of(), Set.of("c1"), 1200, "GROUNDED"))
                .hasMessage("HISTORY_STANCE_MISMATCH");
    }

    @Test void requiresAChunkCitationWhenTheServerProvidesHistoryChunks() throws Exception {
        String output = json.writeValueAsString(new CompanionReplyDraft("companion-reply-v1", "我记得。", "GROUNDED", List.of("m1"), List.of(), "NORMAL", List.of()));
        assertThatThrownBy(() -> validator.validate(output, Set.of("m1"), Set.of("c1"), 1200, "GROUNDED"))
                .hasMessage("GROUNDED_WITHOUT_CHUNK");
    }
}
