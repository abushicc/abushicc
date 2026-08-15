package com.example.relationshipagent.companion.service;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.analysis.model.AgentRun;
import com.example.relationshipagent.analysis.service.AgentRunAuditService;
import com.example.relationshipagent.companion.agent.CompanionAgentClient;
import com.example.relationshipagent.companion.agent.CompanionPromptFactory;
import com.example.relationshipagent.companion.context.CompanionContext;
import com.example.relationshipagent.companion.context.CompanionContextBuilder;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.safety.CompanionDraftValidator;
import com.example.relationshipagent.companion.safety.CompanionSafetyGate;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanionOrchestratorTest {
    @Test void persistsValidatedModelReplyAndReturnsPersistedSuccessStatus() {
        CompanionTurnService turns = mock(CompanionTurnService.class); CompanionContextBuilder contexts = mock(CompanionContextBuilder.class);
        CompanionAgentClient agent = mock(CompanionAgentClient.class); AgentRunAuditService audits = mock(AgentRunAuditService.class);
        RelationshipAgentProperties properties = mock(RelationshipAgentProperties.class);
        when(properties.companion()).thenReturn(new RelationshipAgentProperties.Companion(true, "她", "gpt-5.6-sol", "high", false, 800, 4000, 1200, 28000, 20, 4, 3, 3, 5, 5, 30, 360000, "context-v1", "prompt-v1", "safety-v1"));
        when(properties.analysis()).thenReturn(new RelationshipAgentProperties.Analysis(false, "test", "", "", "gpt-5.6-sol", "responses", "high", false, 12000, 0, 1, 1000, 1000, "analysis-v1", "prompt-v1"));
        ChatSession session = new ChatSession(); session.setId("session-1"); session.setChatFileId("file-1"); session.setTargetPerson("她");
        ChatMessage user = new ChatMessage(); user.setId("user-1"); user.setContent("你好呀");
        CompanionTurn turn = new CompanionTurn(); turn.setId("turn-1"); turn.setUserMessageId("user-1"); turn.setStatus(CompanionTurn.STATUS_RUNNING);
        when(turns.claim("file-1", "session-1", "request-1", "你好呀")).thenReturn(new CompanionTurnService.Claim(session, user, turn, CompanionTurnService.ClaimKind.NEW));
        CompanionContext context = new CompanionContext(session, null, user, List.of(), List.of(), List.of(), List.of(), new ObjectMapper().createObjectNode(), "SKIP_GREETING", List.of("GREETING"), "", "input-hash", "{}", "{}");
        when(contexts.build(session, user, false)).thenReturn(context); when(turns.persistContext(turn, context)).thenReturn(true);
        AgentRun audit = new AgentRun(); audit.setId("audit-1"); when(audits.start("file-1", "test", "gpt-5.6-sol", "COMPANION_REPLY", 0)).thenReturn(audit);
        when(agent.generate(any(CompanionPromptFactory.Prompt.class))).thenReturn(new ResponsesApiClient.ResponsesResult("response-1", "gpt-5.6-sol",
                "{\"schemaVersion\":\"companion-reply-v1\",\"reply\":\"你好呀，今天还好。\",\"usedMemoryIds\":[],\"usedChunkIds\":[],\"historyStance\":\"NOT_APPLICABLE\",\"safetyMode\":\"NORMAL\",\"limitations\":[]}", null));
        ChatMessage assistant = new ChatMessage(); assistant.setId("assistant-1"); assistant.setCreatedAt(Instant.now());
        when(turns.complete(any(), any(), any(), any(), any(), any(), any())).thenReturn(assistant);
        CompanionOrchestrator subject = new CompanionOrchestrator(turns, contexts, new CompanionSafetyGate(), new CompanionPromptFactory(new ObjectMapper()), agent, new CompanionDraftValidator(new ObjectMapper()), audits, properties);

        CompanionOrchestrator.Result result = subject.send("file-1", "session-1", "request-1", "你好呀");

        assertThat(result.status()).isEqualTo(CompanionTurn.STATUS_SUCCESS); assertThat(result.assistantMessage()).isSameAs(assistant); assertThat(result.inProgress()).isFalse();
        verify(agent).generate(any(CompanionPromptFactory.Prompt.class)); verify(audits).success(any(), any(), org.mockito.ArgumentMatchers.eq(0));
        ArgumentCaptor<String> safetyAudit = ArgumentCaptor.forClass(String.class);
        verify(turns).complete(any(), any(), any(), org.mockito.ArgumentMatchers.eq("audit-1"), org.mockito.ArgumentMatchers.eq("gpt-5.6-sol"), org.mockito.ArgumentMatchers.eq("test"), safetyAudit.capture());
        assertThat(safetyAudit.getValue()).contains("\"historyStance\":\"NOT_APPLICABLE\"");
    }
}
