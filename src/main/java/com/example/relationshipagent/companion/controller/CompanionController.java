package com.example.relationshipagent.companion.controller;

import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.service.CompanionOrchestrator;
import com.example.relationshipagent.companion.service.CompanionSessionService;
import com.example.relationshipagent.companion.service.CompanionTurnService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * HTTP boundary for generated conversations. Internal context/audit payloads are deliberately not exposed.
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/companion")
public class CompanionController {
    private final CompanionSessionService sessions;
    private final CompanionTurnService turns;
    private final CompanionOrchestrator orchestrator;
    private final ObjectMapper json;

    public CompanionController(CompanionSessionService sessions, CompanionTurnService turns, CompanionOrchestrator orchestrator, ObjectMapper json) {
        this.sessions = sessions;
        this.turns = turns;
        this.orchestrator = orchestrator;
        this.json = json;
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<SessionResponse>> create(@PathVariable String chatFileId, @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(session(sessions.create(chatFileId, request.targetPerson()))));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionResponse>> list(@PathVariable String chatFileId,
                                                   @RequestParam(required = false) String targetPerson,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sessions.list(chatFileId, targetPerson, status, size).stream().map(this::session).toList());
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<SessionResponse> get(@PathVariable String chatFileId, @PathVariable String sessionId) {
        return ApiResponse.ok(session(sessions.get(chatFileId, sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/end")
    public ApiResponse<SessionResponse> end(@PathVariable String chatFileId, @PathVariable String sessionId) {
        return ApiResponse.ok(session(sessions.end(chatFileId, sessionId)));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<TurnResponse>> send(@PathVariable String chatFileId, @PathVariable String sessionId,
                                                          @Valid @RequestBody SendMessageRequest request) {
        CompanionOrchestrator.Result result = orchestrator.send(chatFileId, sessionId, request.clientRequestId(), request.content());
        HttpStatus status = result.inProgress() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(turn(result, chatFileId, sessionId)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<MessageResponse>> messages(@PathVariable String chatFileId, @PathVariable String sessionId,
                                                       @RequestParam(defaultValue = "50") int size) {
        List<ChatMessage> rows = turns.messages(chatFileId, sessionId, size);
        Collections.reverse(rows);
        return ApiResponse.ok(rows.stream().map(this::message).toList());
    }

    @GetMapping("/sessions/{sessionId}/turns/{turnId}")
    public ApiResponse<TurnStatusResponse> turn(@PathVariable String chatFileId, @PathVariable String sessionId, @PathVariable String turnId) {
        CompanionTurn value = turns.get(chatFileId, sessionId, turnId);
        return ApiResponse.ok(new TurnStatusResponse(value.getId(), value.getStatus(), value.getAssistantMessageId(), value.getAttemptCount(), value.getFinishedAt(), CompanionOrchestrator.SIMULATION_NOTICE));
    }

    private SessionResponse session(ChatSession value) {
        return new SessionResponse(value.getId(), value.getTargetPerson(), value.getPersonaProfileId(), value.getPersonaVersion(), value.getStatus(), value.getCreatedAt(), value.getEndedAt(), CompanionOrchestrator.SIMULATION_NOTICE);
    }

    private MessageResponse message(ChatMessage value) {
        return new MessageResponse(value.getId(), value.getRole(), value.getContent(), value.getCreatedAt());
    }

    private TurnResponse turn(CompanionOrchestrator.Result result, String fileId, String sessionId) {
        ChatMessage message = result.assistantMessage();
        List<String> memoryIds = List.of(), sessionIds = List.of(), chunkIds = List.of();
        if (message != null) {
            memoryIds = ids(message.getUsedMemoryIds());
            sessionIds = ids(message.getUsedSessionIds());
            chunkIds = ids(message.getUsedChunkIds());
        }
        return new TurnResponse(result.turnId(), result.userMessageId(), message == null ? null : message(message), result.status(), result.inProgress(), memoryIds, sessionIds, chunkIds, result.retrievalDecision(), result.safetyMode(), historyStance(message), result.simulationNotice());
    }

    private String historyStance(ChatMessage message) {
        if (message == null || message.getSafetyJson() == null) return null;
        try {
            return json.readTree(message.getSafetyJson()).path("historyStance").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> ids(String source) {
        if (source == null || source.length() < 2) return List.of();
        return List.of(source.substring(1, source.length() - 1).replace("\"", "").split(",")).stream().filter(value -> !value.isBlank()).toList();
    }

    public record CreateSessionRequest(@NotBlank @Size(max = 128) String targetPerson) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 64) String clientRequestId,
                                     @NotBlank @Size(max = 4000) String content) {
    }

    public record SessionResponse(String id, String targetPerson, String personaProfileId, String personaVersion,
                                  String status, Instant createdAt, Instant endedAt, String simulationNotice) {
    }

    public record MessageResponse(String id, String role, String content, Instant createdAt) {
    }

    public record TurnResponse(String turnId, String userMessageId, MessageResponse assistantMessage, String status,
                               boolean inProgress,
                               List<String> usedMemoryIds, List<String> usedSessionIds, List<String> usedChunkIds,
                               String retrievalDecision, String safety, String historyStance, String simulationNotice) {
    }

    public record TurnStatusResponse(String turnId, String status, String assistantMessageId, Integer attemptCount,
                                     Instant finishedAt, String simulationNotice) {
    }
}
