package com.example.relationshipagent.analysis.client;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in integration check for the configured Responses API provider.
 *
 * <p>Run explicitly with {@code -Danalysis.smoke=true}; the request contains only fictional
 * evidence and never reads chat content.
 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "analysis.smoke", matches = "true")
class AnalysisModelSmokeIT {

    private static final String EVIDENCE_REF_ID = "smoke-evidence-001";

    @Autowired
    private ResponsesApiClient responsesApiClient;

    @Autowired
    private RelationshipAgentProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnStructuredDraftReferencingProvidedEvidence() throws Exception {
        JsonNode schema = objectMapper.readTree("""
                {
                  "type": "object",
                  "additionalProperties": false,
                  "properties": {
                    "claims": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "properties": {
                          "evidenceRefIds": {
                            "type": "array",
                            "items": {"type": "string"}
                          }
                        },
                        "required": ["evidenceRefIds"]
                      }
                    }
                  },
                  "required": ["claims"]
                }
                """);
        String developerPrompt = "Return only a JSON object conforming to the supplied schema.";
        String userPrompt = "Fictional evidence only. Return one claim whose evidenceRefIds contains "
                + EVIDENCE_REF_ID + ".";

        long startedAt = System.nanoTime();
        ResponsesApiClient.ResponsesResult result =
                responsesApiClient.generateJson(developerPrompt, userPrompt, schema);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

        JsonNode draft = objectMapper.readTree(result.outputText());
        assertThat(properties.analysis().enabled()).isTrue();
        assertThat(properties.analysis().store()).isFalse();
        assertThat(properties.analysis().reasoningEffort()).isEqualTo("high");
        assertThat(draft.path("claims").isArray()).isTrue();
        assertThat(draft.path("claims")).anySatisfy(claim ->
                assertThat(claim.path("evidenceRefIds"))
                        .anySatisfy(ref -> assertThat(ref.asText()).isEqualTo(EVIDENCE_REF_ID)));

        System.out.printf("Analysis model smoke test passed: provider=%s, model=%s, durationMs=%d, totalTokens=%s%n",
                properties.analysis().provider(), result.model(), durationMs,
                result.usage().path("total_tokens").asText("unknown"));
    }
}
