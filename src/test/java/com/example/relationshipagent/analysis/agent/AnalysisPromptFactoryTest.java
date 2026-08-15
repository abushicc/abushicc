package com.example.relationshipagent.analysis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisPromptFactoryTest {
    @Test void shouldRequireExactlyTheNineFixedSections() {
        var schema = new AnalysisPromptFactory(new ObjectMapper()).schema().path("properties").path("sections");
        assertThat(schema.path("minItems").asInt()).isEqualTo(9);
        assertThat(schema.path("maxItems").asInt()).isEqualTo(9);
        assertThat(schema.path("items").path("properties").path("sectionKey").path("enum")).hasSize(9);
    }
}
