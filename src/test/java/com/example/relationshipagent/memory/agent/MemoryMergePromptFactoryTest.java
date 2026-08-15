package com.example.relationshipagent.memory.agent;

import com.example.relationshipagent.memory.model.MemoryObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryMergePromptFactoryTest {
    @Test void shouldSerializeNullableTimeAndExposeStrictSchema() {
        MemoryObservation o=new MemoryObservation(); o.setId("o-1"); o.setObservationKey("study"); o.setObservationType("FACT");
        o.setStatement("她在学习"); o.setPolarity("NEUTRAL"); o.setConfidence(BigDecimal.valueOf(.5));
        var p=new MemoryMergePromptFactory(new ObjectMapper().findAndRegisterModules()).create(java.util.List.of(o),"她");
        assertThat(p.userPrompt()).contains("o-1");
        assertThat(p.jsonSchema().path("properties").path("items").path("items").path("properties").path("memoryKey").path("type").asText()).isEqualTo("string");
        assertThat(p.jsonSchema().path("additionalProperties").asBoolean()).isFalse();
    }
}
