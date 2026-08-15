package com.example.relationshipagent.analysis.service;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.analysis.model.AgentRun;
import com.example.relationshipagent.analysis.repository.AgentRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes minimal agent-call audit metadata without retaining prompt or completion text.
 */
@Service
public class AgentRunAuditService {
    private final AgentRunRepository runs;
    private final ObjectMapper json;

    public AgentRunAuditService(AgentRunRepository runs, ObjectMapper json) {
        this.runs = runs;
        this.json = json;
    }

    public AgentRun start(String chatFileId, String provider, String model, int packetCount) {
        return start(chatFileId, provider, model, "ANALYSIS_REPORT", packetCount);
    }

    public AgentRun start(String chatFileId, String provider, String model, String agentType, int packetCount) {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setChatFileId(chatFileId);
        run.setAgentType(agentType);
        run.setInputSummary("evidencePackets=" + packetCount);
        run.setModelName(model);
        run.setProviderName(provider);
        run.setStatus(AgentRun.STATUS_RUNNING);
        run.setStartedAt(Instant.now());
        runs.insert(run);
        return run;
    }

    public void success(AgentRun run, ResponsesApiClient.ResponsesResult response, int validClaims) {
        run.setStatus(AgentRun.STATUS_SUCCESS);
        run.setFinishedAt(Instant.now());
        run.setDurationMs(run.getFinishedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
        run.setOutputSummary("responseId=" + safe(response.responseId()) + ",validClaims=" + validClaims);
        run.setModelName(response.model());
        run.setTokenUsage(asJson(response.usage()));
        runs.updateById(run);
    }

    public void failed(AgentRun run, Exception failure) {
        if (run == null) return;
        run.setStatus(AgentRun.STATUS_FAILED);
        run.setFinishedAt(Instant.now());
        run.setDurationMs(run.getFinishedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
        String message = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        run.setErrorMessage(message.length() > 500 ? message.substring(0, 500) : message);
        runs.updateById(run);
    }

    private String asJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
