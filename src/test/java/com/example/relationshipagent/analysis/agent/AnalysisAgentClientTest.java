package com.example.relationshipagent.analysis.agent;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.analysis.evidence.EvidencePacket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisAgentClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldCreateStrictSchemaAndParseDraftFromModel() {
        AnalysisPromptFactory factory = new AnalysisPromptFactory(mapper);
        ResponsesApiClient responses = mock(ResponsesApiClient.class);
        when(responses.generateJson(any(), any(), any())).thenReturn(new ResponsesApiClient.ResponsesResult("r1", "model", """
                {"schemaVersion":"analysis-draft-v1","coverage":{"summary":"covered","uncoveredPacketIds":[]},"sections":[{"sectionKey":"OVERVIEW","summary":"summary","claims":[]}],"limitations":[]}
                """, mapper.createObjectNode()));
        AnalysisAgentClient client = new AnalysisAgentClient(responses, factory, mapper);

        var result = client.generate(List.<EvidencePacket>of(), "问题", Map.of());

        assertThat(result.draft().sections()).singleElement().extracting(AnalysisDraft.AnalysisSectionDraft::sectionKey).isEqualTo("OVERVIEW");
        verify(responses).generateJson(any(), contains("evidencePackets"), argThat(schema -> schema.path("properties").path("sections").isObject()));
        assertThat(factory.schema().path("additionalProperties").asBoolean()).isFalse();
        assertThat(factory.schema().path("properties").path("sections").path("minItems").asInt()).isEqualTo(9);
    }

    @Test
    void shouldRejectNonJsonModelOutput() {
        ResponsesApiClient responses = mock(ResponsesApiClient.class);
        when(responses.generateJson(any(), any(), any())).thenReturn(new ResponsesApiClient.ResponsesResult("r1", "model", "not-json", mapper.createObjectNode()));
        AnalysisAgentClient client = new AnalysisAgentClient(responses, new AnalysisPromptFactory(mapper), mapper);

        assertThatThrownBy(() -> client.generate(List.of(), null, Map.of())).isInstanceOf(AnalysisAgentClient.InvalidAnalysisDraftException.class);
    }
}
