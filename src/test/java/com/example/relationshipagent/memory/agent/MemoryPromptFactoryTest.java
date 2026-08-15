package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.memory.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryPromptFactoryTest {
    @Test void shouldExposeStrictObservationSchema() {
        var factory=new MemoryPromptFactory(new ObjectMapper().findAndRegisterModules());
        var packet=new ObservationEvidencePacket("SES-000001","s",Instant.EPOCH,Instant.EPOCH,"她",1,false,List.of(new ObservationEvidenceRef("MES-000001","m","s",Instant.EPOCH,"她","好呀","TEXT")),List.of());
        var prompt=factory.create(new ObservationBatch("b",List.of(packet),2),"她");
        assertThat(prompt.jsonSchema().path("additionalProperties").asBoolean()).isFalse();
        assertThat(prompt.userPrompt()).contains("MES-000001").contains("targetPerson");
    }
}
