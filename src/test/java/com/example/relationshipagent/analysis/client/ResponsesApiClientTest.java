package com.example.relationshipagent.analysis.client;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class ResponsesApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSendResponsesApiRequestAndExtractOutputText() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://relay.example/v1")
                .defaultHeader("Authorization", "Bearer relay-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResponsesApiClient client = new ResponsesApiClient(builder.build(), properties(), objectMapper);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\",\"properties\":{}}");

        server.expect(once(), requestTo("https://relay.example/v1/responses"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer relay-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"model":"gpt-5.6-sol","reasoning":{"effort":"high"},"store":false,
                         "max_output_tokens":12000,"text":{"format":{"type":"json_schema","name":"analysis_draft","strict":true}}}
                        """, false))
                .andRespond(withSuccess("""
                        {"id":"resp_123","model":"gpt-5.6-sol","usage":{"total_tokens":42},
                         "output":[{"type":"message","content":[{"type":"output_text","text":"{\\\"sections\\\":[]}"}]}]}
                        """, MediaType.APPLICATION_JSON));

        ResponsesApiClient.ResponsesResult result = client.generateJson("system", "input", schema);

        assertThat(result.responseId()).isEqualTo("resp_123");
        assertThat(result.model()).isEqualTo("gpt-5.6-sol");
        assertThat(result.outputText()).isEqualTo("{\"sections\":[]}");
        assertThat(result.usage().path("total_tokens").asInt()).isEqualTo(42);
        server.verify();
    }

    @Test
    void shouldRejectBlankPromptsBeforeCallingProvider() throws Exception {
        ResponsesApiClient client = new ResponsesApiClient(RestClient.create(), properties(), objectMapper);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\"}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.generateJson("", "input", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void shouldRetryGenericTransportFailure() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://relay.example/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResponsesApiClient client = new ResponsesApiClient(builder.build(), properties(0), objectMapper);
        JsonNode schema = objectMapper.readTree("{\"type\":\"object\"}");

        server.expect(once(), requestTo("https://relay.example/v1/responses"))
                .andRespond(request -> {
                    throw new RestClientException("connection reset");
                });
        server.expect(once(), requestTo("https://relay.example/v1/responses"))
                .andRespond(withSuccess("""
                        {"id":"resp_retry","model":"gpt-5.6-sol",
                         "output":[{"content":[{"type":"output_text","text":"{}"}]}]}
                        """, MediaType.APPLICATION_JSON));

        ResponsesApiClient.ResponsesResult result = client.generateJson("system", "input", schema);

        assertThat(result.responseId()).isEqualTo("resp_retry");
        server.verify();
    }

    private static RelationshipAgentProperties.Analysis properties() {
        return properties(2000);
    }

    private static RelationshipAgentProperties.Analysis properties(long backoffMs) {
        return new RelationshipAgentProperties.Analysis(true, "juai", "https://relay.example/v1", "relay-key",
                "gpt-5.6-sol", "responses", "high", false, 12000, 2, backoffMs,
                10000, 180000, "analysis-v1", "analysis-prompt-v1");
    }
}
