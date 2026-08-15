package com.example.relationshipagent.companion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.companion.context.CompanionContext;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.repository.ChatMessageRepository;
import com.example.relationshipagent.companion.repository.ChatSessionRepository;
import com.example.relationshipagent.companion.repository.CompanionTurnRepository;
import com.example.relationshipagent.companion.safety.CompanionDraftValidator;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short database transactions around one long-running remote generation.
 */
@Service
public class CompanionTurnService {
    private final CompanionSessionService sessionService;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final CompanionTurnRepository turns;
    private final RelationshipAgentProperties properties;
    private final CompanionTurnClaimExecutor claimExecutor;

    public CompanionTurnService(CompanionSessionService sessionService, ChatSessionRepository sessions,
                                ChatMessageRepository messages, CompanionTurnRepository turns, RelationshipAgentProperties properties,
                                CompanionTurnClaimExecutor claimExecutor) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.messages = messages;
        this.turns = turns;
        this.properties = properties;
        this.claimExecutor = claimExecutor;
    }

    public Claim claim(String chatFileId, String sessionId, String clientRequestId, String content) {
        // 先做输入边界校验，再交给 claimExecutor 处理唯一键冲突和超时 turn 接管。
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 64)
            throw new BizException(ErrorCode.PARAM_INVALID, "clientRequestId is required and must be <= 64 chars");
        if (content == null || content.isBlank() || content.length() > properties.companion().maxUserChars())
            throw new BizException(ErrorCode.PARAM_INVALID, "content is empty or exceeds maxUserChars");
        try {
            return claimExecutor.claim(chatFileId, sessionId, clientRequestId, content);
        } catch (DataIntegrityViolationException ignored) {
            return claimExecutor.recover(chatFileId, sessionId, clientRequestId, content);
        }
    }

    @Transactional
    public boolean persistContext(CompanionTurn turn, CompanionContext context) {
        // attemptToken 是接管后的写权限；旧 worker 即使恢复，也不能覆盖新 worker 的上下文。
        return turns.update(null, new LambdaUpdateWrapper<CompanionTurn>().eq(CompanionTurn::getId, turn.getId()).eq(CompanionTurn::getStatus, CompanionTurn.STATUS_RUNNING)
                .eq(CompanionTurn::getAttemptToken, turn.getAttemptToken()).set(CompanionTurn::getInputHash, context.inputHash())
                .set(CompanionTurn::getContextRefsJson, context.contextRefsJson()).set(CompanionTurn::getRetrievalJson, context.retrievalJson())
                .set(CompanionTurn::getUpdatedAt, Instant.now())) == 1;
    }

    @Transactional
    public ChatMessage complete(CompanionTurn turn, CompanionContext context, CompanionDraftValidator.ValidatedReply reply,
                                String agentRunId, String model, String provider, String safetyJson) {
        // 助手消息插入与 turn -> SUCCESS 在同一短事务中完成，避免留下“成功但无消息”的状态。
        Instant now = Instant.now();
        ChatMessage assistant = new ChatMessage();
        assistant.setId(UUID.randomUUID().toString());
        assistant.setChatSessionId(turn.getChatSessionId());
        assistant.setRole(ChatMessage.ROLE_ASSISTANT);
        assistant.setProvenance(ChatMessage.PROVENANCE_GENERATED);
        assistant.setContent(reply.reply());
        assistant.setReplyToMessageId(turn.getUserMessageId());
        assistant.setUsedMemoryIds(jsonArray(reply.usedMemoryIds()));
        assistant.setUsedChunkIds(jsonArray(reply.usedChunkIds()));
        assistant.setUsedSessionIds(jsonArray(context.chunks().stream().map(CompanionContext.RetrievedChunk::sessionId).distinct().toList()));
        assistant.setInputHash(context.inputHash());
        assistant.setModelName(model);
        assistant.setProviderName(provider);
        assistant.setAgentRunId(agentRunId);
        assistant.setSafetyJson(safetyJson);
        assistant.setCreatedAt(now);
        messages.insert(assistant);
        int changed = turns.update(null, new LambdaUpdateWrapper<CompanionTurn>().eq(CompanionTurn::getId, turn.getId()).eq(CompanionTurn::getStatus, CompanionTurn.STATUS_RUNNING)
                .eq(CompanionTurn::getAttemptToken, turn.getAttemptToken()).eq(CompanionTurn::getInputHash, context.inputHash())
                .set(CompanionTurn::getAssistantMessageId, assistant.getId()).set(CompanionTurn::getAgentRunId, agentRunId).set(CompanionTurn::getModelName, model).set(CompanionTurn::getProviderName, provider)
                .set(CompanionTurn::getStatus, CompanionTurn.STATUS_SUCCESS).set(CompanionTurn::getFinishedAt, now).set(CompanionTurn::getUpdatedAt, now));
        // CAS 失败说明本轮已被其他 worker 接管，当前 worker 失去写权限。
        if (changed != 1) throw new StaleTurnException();
        sessions.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, turn.getChatSessionId()).eq(ChatSession::getStatus, ChatSession.STATUS_ACTIVE)
                .set(ChatSession::getCurrentTopic, context.topicTerms()).set(ChatSession::getTopicTerms, context.topicTerms()).set(ChatSession::getLastMessageAt, now)
                .set(ChatSession::getTurnsSinceLastSearch, "SEARCH".equals(context.retrievalDecision()) || "NO_EVIDENCE".equals(context.retrievalDecision()) ? 0 : (turnsSinceLastSearch(turn.getChatSessionId()) + 1))
                .set(ChatSession::getLastSearchAt, "SEARCH".equals(context.retrievalDecision()) || "NO_EVIDENCE".equals(context.retrievalDecision()) ? now : lastSearchAt(turn.getChatSessionId()))
                .set(ChatSession::getUpdatedAt, now).setSql("version = version + 1"));
        return assistant;
    }

    @Transactional
    public void fail(CompanionTurn turn, String category) {
        // 失败更新同样带 attemptToken，避免旧 worker 把已被接管的新尝试标记为失败。
        turns.update(null, new LambdaUpdateWrapper<CompanionTurn>().eq(CompanionTurn::getId, turn.getId()).eq(CompanionTurn::getStatus, CompanionTurn.STATUS_RUNNING).eq(CompanionTurn::getAttemptToken, turn.getAttemptToken())
                .set(CompanionTurn::getStatus, CompanionTurn.STATUS_FAILED).set(CompanionTurn::getErrorMessage, category.length() > 500 ? category.substring(0, 500) : category).set(CompanionTurn::getFinishedAt, Instant.now()).set(CompanionTurn::getUpdatedAt, Instant.now()));
    }

    public CompanionTurn get(String chatFileId, String sessionId, String turnId) {
        sessionService.get(chatFileId, sessionId);
        CompanionTurn turn = turns.selectById(turnId);
        if (turn == null || !sessionId.equals(turn.getChatSessionId()))
            throw new BizException(ErrorCode.COMPANION_TURN_NOT_FOUND);
        return turn;
    }

    public ChatMessage assistant(CompanionTurn turn) {
        return turn.getAssistantMessageId() == null ? null : messages.selectById(turn.getAssistantMessageId());
    }

    public List<ChatMessage> messages(String chatFileId, String sessionId, int size) {
        sessionService.get(chatFileId, sessionId);
        return messages.selectList(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getChatSessionId, sessionId).orderByDesc(ChatMessage::getCreatedAt).last("LIMIT " + Math.max(1, Math.min(size, 100))));
    }

    private int turnsSinceLastSearch(String sessionId) {
        ChatSession session = sessions.selectById(sessionId);
        return session == null || session.getTurnsSinceLastSearch() == null ? 0 : session.getTurnsSinceLastSearch();
    }

    private Instant lastSearchAt(String sessionId) {
        ChatSession session = sessions.selectById(sessionId);
        return session == null ? null : session.getLastSearchAt();
    }

    private static String jsonArray(List<String> values) {
        return "[" + values.stream().map(value -> "\\\"" + value.replace("\\\"", "\\\\\\\"") + "\\\"").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    public record Claim(ChatSession session, ChatMessage userMessage, CompanionTurn turn, ClaimKind kind) {
    }

    public enum ClaimKind {NEW, RECLAIMED, SUCCESS_REUSED, IN_PROGRESS}

    public static class StaleTurnException extends RuntimeException {
    }
}
