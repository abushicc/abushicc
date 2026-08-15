package com.example.relationshipagent.companion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.repository.ChatMessageRepository;
import com.example.relationshipagent.companion.repository.ChatSessionRepository;
import com.example.relationshipagent.companion.repository.CompanionTurnRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.example.relationshipagent.companion.context.CompanionContextBuilder.sha256;

/**
 * Isolates the claim transaction so a unique-index race can be recovered outside its rollback.
 */
@Component
class CompanionTurnClaimExecutor {
    private final CompanionSessionService sessionService;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final CompanionTurnRepository turns;
    private final RelationshipAgentProperties properties;

    CompanionTurnClaimExecutor(CompanionSessionService sessionService, ChatSessionRepository sessions,
                               ChatMessageRepository messages, CompanionTurnRepository turns,
                               RelationshipAgentProperties properties) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.messages = messages;
        this.turns = turns;
        this.properties = properties;
    }

    @Transactional
    public CompanionTurnService.Claim claim(String chatFileId, String sessionId, String clientRequestId, String content) {
        ChatSession session = sessionService.requireActive(chatFileId, sessionId);
        String requestHash = requestHash(sessionId, clientRequestId, content);
        CompanionTurn existing = byRequest(sessionId, clientRequestId);
        if (existing != null) return existingClaim(existing, requestHash);
        CompanionTurn running = running(sessionId);
        if (running != null) throw new BizException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        Instant now = Instant.now();
        ChatMessage user = new ChatMessage();
        user.setId(UUID.randomUUID().toString());
        user.setChatSessionId(sessionId);
        user.setRole(ChatMessage.ROLE_USER);
        user.setProvenance(ChatMessage.PROVENANCE_USER_INPUT);
        user.setContent(content);
        user.setClientRequestId(clientRequestId);
        user.setCreatedAt(now);
        messages.insert(user);
        CompanionTurn turn = new CompanionTurn();
        turn.setId(UUID.randomUUID().toString());
        turn.setChatSessionId(sessionId);
        turn.setClientRequestId(clientRequestId);
        turn.setUserMessageId(user.getId());
        turn.setRequestHash(requestHash);
        turn.setStatus(CompanionTurn.STATUS_RUNNING);
        turn.setAttemptCount(1);
        turn.setAttemptToken(UUID.randomUUID().toString());
        turn.setStartedAt(now);
        turn.setCreatedAt(now);
        turn.setUpdatedAt(now);
        turns.insert(turn);
        return new CompanionTurnService.Claim(session, user, turn, CompanionTurnService.ClaimKind.NEW);
    }

    @Transactional
    public CompanionTurnService.Claim recover(String chatFileId, String sessionId, String clientRequestId, String content) {
        ChatSession session = sessionService.requireActive(chatFileId, sessionId);
        String requestHash = requestHash(sessionId, clientRequestId, content);
        CompanionTurn existing = byRequest(sessionId, clientRequestId);
        if (existing != null) return existingClaim(existing, requestHash);
        CompanionTurn running = running(sessionId);
        if (running != null) {
            ChatMessage user = messages.selectById(running.getUserMessageId());
            if (user != null)
                return new CompanionTurnService.Claim(session, user, running, CompanionTurnService.ClaimKind.IN_PROGRESS);
        }
        throw new BizException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
    }

    private CompanionTurnService.Claim existingClaim(CompanionTurn existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash()))
            throw new BizException(ErrorCode.COMPANION_REQUEST_CONFLICT);
        ChatMessage user = messages.selectById(existing.getUserMessageId());
        ChatSession session = sessions.selectById(existing.getChatSessionId());
        if (user == null || session == null) throw new BizException(ErrorCode.COMPANION_TURN_NOT_FOUND);
        if (CompanionTurn.STATUS_SUCCESS.equals(existing.getStatus()))
            return new CompanionTurnService.Claim(session, user, existing, CompanionTurnService.ClaimKind.SUCCESS_REUSED);
        if (CompanionTurn.STATUS_RUNNING.equals(existing.getStatus())) {
            Instant staleBefore = Instant.now().minusMillis(properties.companion().staleTurnMs());
            if (existing.getStartedAt() == null || existing.getStartedAt().isAfter(staleBefore))
                return new CompanionTurnService.Claim(session, user, existing, CompanionTurnService.ClaimKind.IN_PROGRESS);
        }
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        int attempts = (existing.getAttemptCount() == null ? 0 : existing.getAttemptCount()) + 1;
        int changed = turns.update(null, new LambdaUpdateWrapper<CompanionTurn>().eq(CompanionTurn::getId, existing.getId())
                .in(CompanionTurn::getStatus, CompanionTurn.STATUS_FAILED, CompanionTurn.STATUS_RUNNING)
                .set(CompanionTurn::getStatus, CompanionTurn.STATUS_RUNNING).set(CompanionTurn::getAttemptToken, token).set(CompanionTurn::getAttemptCount, attempts)
                .set(CompanionTurn::getStartedAt, now).set(CompanionTurn::getFinishedAt, null).set(CompanionTurn::getErrorMessage, null)
                .set(CompanionTurn::getInputHash, null).set(CompanionTurn::getContextRefsJson, null).set(CompanionTurn::getRetrievalJson, null).set(CompanionTurn::getUpdatedAt, now));
        if (changed != 1) throw new BizException(ErrorCode.COMPANION_TURN_IN_PROGRESS);
        existing.setAttemptToken(token);
        existing.setStatus(CompanionTurn.STATUS_RUNNING);
        existing.setAttemptCount(attempts);
        return new CompanionTurnService.Claim(session, user, existing, CompanionTurnService.ClaimKind.RECLAIMED);
    }

    private CompanionTurn byRequest(String sessionId, String clientRequestId) {
        return turns.selectOne(new LambdaQueryWrapper<CompanionTurn>().eq(CompanionTurn::getChatSessionId, sessionId).eq(CompanionTurn::getClientRequestId, clientRequestId).last("LIMIT 1"));
    }

    private CompanionTurn running(String sessionId) {
        return turns.selectOne(new LambdaQueryWrapper<CompanionTurn>().eq(CompanionTurn::getChatSessionId, sessionId).eq(CompanionTurn::getStatus, CompanionTurn.STATUS_RUNNING).last("LIMIT 1"));
    }

    private static String requestHash(String sessionId, String clientRequestId, String content) {
        return sha256(sessionId + "|" + clientRequestId + "|" + sha256(content));
    }
}
