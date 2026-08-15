package com.example.relationshipagent.memory.validation;

import com.example.relationshipagent.memory.agent.MemoryMergeDraft;
import com.example.relationshipagent.memory.agent.MemoryMergePromptFactory;
import com.example.relationshipagent.memory.model.MemoryObservation;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryMergeDraftValidatorTest {
    @Test void shouldRequireTwoSessionsForPattern() {
        MemoryObservation o=newObservation("o-1","s-1");
        var d=new MemoryMergeDraft(MemoryMergePromptFactory.SCHEMA_VERSION,List.of(new MemoryMergeDraft.MemoryItemDraft(
                "p","COMMUNICATION_PATTERN","她经常简短回复","NEUTRAL",.7,List.of("o-1"),Instant.EPOCH,Instant.EPOCH,"")),List.of());
        var result=new MemoryMergeDraftValidator().validate(d,List.of(o),"她");
        assertThat(result.memories()).singleElement().satisfies(v->assertThat(v.status()).isEqualTo("REVIEW_REQUIRED"));
    }
    @Test void shouldRejectKeyMismatch() {
        MemoryObservation o=newObservation("o-1","s-1");
        var d=new MemoryMergeDraft(MemoryMergePromptFactory.SCHEMA_VERSION,List.of(new MemoryMergeDraft.MemoryItemDraft(
                "other","FACT","事实","NEUTRAL",.7,List.of("o-1"),null,null,"")),List.of());
        assertThat(new MemoryMergeDraftValidator().validate(d,List.of(o),"她").memories()).singleElement().satisfies(v->assertThat(v.status()).isEqualTo("REJECTED"));
    }
    private MemoryObservation newObservation(String id,String session){MemoryObservation o=new MemoryObservation();o.setId(id);o.setTargetPerson("她");o.setObservationKey("p");o.setObservationType("COMMUNICATION_PATTERN");o.setStatement("她简短回复");o.setPolarity("NEUTRAL");o.setValidationStatus(MemoryObservation.VALID);o.setStatus(MemoryObservation.STATUS_ACTIVE);o.setSourceSessionId(session);o.setCreatedAt(Instant.EPOCH);return o;}
}
