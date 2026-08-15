package com.example.relationshipagent.companion.service;

import com.example.relationshipagent.analysis.client.ResponsesApiClient;
import com.example.relationshipagent.analysis.model.AgentRun;
import com.example.relationshipagent.analysis.service.AgentRunAuditService;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.companion.agent.CompanionAgentClient;
import com.example.relationshipagent.companion.agent.CompanionReplyDraft;
import com.example.relationshipagent.companion.agent.CompanionPromptFactory;
import com.example.relationshipagent.companion.context.CompanionContext;
import com.example.relationshipagent.companion.context.CompanionContextBuilder;
import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.CompanionTurn;
import com.example.relationshipagent.companion.safety.CompanionDraftValidator;
import com.example.relationshipagent.companion.safety.CompanionSafetyGate;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Coordinates an online turn without holding a database transaction during the remote request.
 */
@Service
public class CompanionOrchestrator {
    public static final String SIMULATION_NOTICE = "这是基于历史文本生成的风格化模拟，不是真实本人。";
    private static final Logger log = LoggerFactory.getLogger(CompanionOrchestrator.class);

    private final CompanionTurnService turns;
    private final CompanionContextBuilder contexts;
    private final CompanionSafetyGate safety;
    private final CompanionPromptFactory prompts;
    private final CompanionAgentClient agent;
    private final CompanionDraftValidator validator;
    private final AgentRunAuditService audits;
    private final RelationshipAgentProperties properties;

    public CompanionOrchestrator(CompanionTurnService turns, CompanionContextBuilder contexts, CompanionSafetyGate safety,
                                 CompanionPromptFactory prompts, CompanionAgentClient agent, CompanionDraftValidator validator,
                                 AgentRunAuditService audits, RelationshipAgentProperties properties) {
        this.turns = turns;
        this.contexts = contexts;
        this.safety = safety;
        this.prompts = prompts;
        this.agent = agent;
        this.validator = validator;
        this.audits = audits;
        this.properties = properties;
    }

    public Result send(String chatFileId, String sessionId, String clientRequestId, String content) {
        long startedAt = System.nanoTime();
        if (properties.companion() == null || !properties.companion().enabled())
            throw new BizException(ErrorCode.COMPANION_DISABLED);
        log.info("Companion turn received: chatFileId={}, sessionId={}, requestId={}, contentChars={}",
                chatFileId, sessionId, clientRequestId, content == null ? 0 : content.length());
        CompanionTurnService.Claim claim = turns.claim(chatFileId, sessionId, clientRequestId, content);
        log.info("Companion turn claimed: turnId={}, kind={}, attempt={}",
                claim.turn().getId(), claim.kind(), claim.turn().getAttemptCount());
        // claim 通过唯一键和 CAS 实现幂等；重复请求直接复用结果，并发请求则返回 IN_PROGRESS。
        if (claim.kind() == CompanionTurnService.ClaimKind.SUCCESS_REUSED) {
            log.info("Companion turn reused: turnId={}, elapsedMs={}", claim.turn().getId(), elapsedMillis(startedAt));
            return completed(claim.turn(), turns.assistant(claim.turn()), null, null);
        }
        if (claim.kind() == CompanionTurnService.ClaimKind.IN_PROGRESS) {
            log.info("Companion turn already in progress: turnId={}, elapsedMs={}",
                    claim.turn().getId(), elapsedMillis(startedAt));
            return inProgress(claim.turn());
        }
        CompanionSafetyGate.Decision safetyDecision = safety.evaluate(content);
        CompanionContext context = contexts.build(claim.session(), claim.userMessage(), safetyDecision.handled());
        log.info("Companion context built: turnId={}, retrieval={}, history={}, memories={}, chunks={}, fewShots={}, safetyHandled={}",
                claim.turn().getId(), context.retrievalDecision(), context.history().size(),
                context.memories().size(), context.chunks().size(), context.fewShots().size(), safetyDecision.handled());
        // 上下文先落库再调用远程模型，使失败重试仍能复核本轮实际使用的证据版本。
        if (!turns.persistContext(claim.turn(), context)) {
            log.warn("Companion context ownership lost: turnId={}", claim.turn().getId());
            return inProgress(claim.turn());
        }
        if (safetyDecision.handled()) {
            CompanionDraftValidator.ValidatedReply fixed = new CompanionDraftValidator.ValidatedReply(safetyDecision.reply(), List.of(), List.of(),
                    CompanionReplyDraft.NOT_APPLICABLE, safetyDecision.safetyMode(), List.of(safetyDecision.ruleCode()));
            ChatMessage assistant = turns.complete(claim.turn(), context, fixed, null, null, null,
                    safetyAuditJson(safetyDecision));
            log.info("Companion turn completed by safety gate: turnId={}, mode={}, elapsedMs={}",
                    claim.turn().getId(), safetyDecision.safetyMode(), elapsedMillis(startedAt));
            return completed(claim.turn(), assistant, context.retrievalDecision(), safetyDecision.safetyMode());
        }
        AgentRun audit = null;
        try {
            // 远程请求故意放在事务之外，避免网络延迟长期占用数据库连接。
            audit = audits.start(chatFileId, properties.analysis().provider(), properties.companion().model(), "COMPANION_REPLY", context.chunks().size());
            String requiredHistoryStance = requiredHistoryStance(context);
            ResponsesApiClient.ResponsesResult response = agent.generate(prompts.create(context));
            CompanionDraftValidator.ValidatedReply reply;
            try {
                reply = validator.validate(response.outputText(), context.memoryIds(), context.chunkIds(), properties.companion().maxReplyChars(), requiredHistoryStance);
            } catch (CompanionDraftValidator.InvalidDraftException invalid) {
                // 只允许一次针对 stance/citation 的纠正重试，防止模型反复生成或绕过证据约束。
                if (!"HISTORY_STANCE_MISMATCH".equals(invalid.getMessage()) && !"GROUNDED_WITHOUT_CHUNK".equals(invalid.getMessage()))
                    throw invalid;
                log.warn("Companion draft requires one correction: turnId={}, reason={}",
                        claim.turn().getId(), invalid.getMessage());
                response = agent.generate(prompts.create(context, "上一版 JSON 的 historyStance 与 historyAvailability 不一致；请严格按 historyAvailability 重写。"));
                reply = validator.validate(response.outputText(), context.memoryIds(), context.chunkIds(), properties.companion().maxReplyChars(), requiredHistoryStance);
            }
            audits.success(audit, response, reply.usedMemoryIds().size() + reply.usedChunkIds().size());
            ChatMessage assistant = turns.complete(claim.turn(), context, reply, audit.getId(), response.model(), properties.analysis().provider(),
                    auditJson(reply));
            log.info("Companion turn completed: turnId={}, assistantMessageId={}, retrieval={}, stance={}, memoryRefs={}, chunkRefs={}, elapsedMs={}",
                    claim.turn().getId(), assistant.getId(), context.retrievalDecision(), reply.historyStance(),
                    reply.usedMemoryIds().size(), reply.usedChunkIds().size(), elapsedMillis(startedAt));
            return completed(claim.turn(), assistant, context.retrievalDecision(), reply.safetyMode());
        } catch (CompanionTurnService.StaleTurnException ignored) {
            log.warn("Companion turn became stale while executing: turnId={}, elapsedMs={}",
                    claim.turn().getId(), elapsedMillis(startedAt));
            return inProgress(claim.turn());
        } catch (Exception failure) {
            audits.failed(audit, failure);
            turns.fail(claim.turn(), safeCategory(failure));
            log.error("Companion turn failed: turnId={}, category={}, elapsedMs={}",
                    claim.turn().getId(), safeCategory(failure), elapsedMillis(startedAt));
            if (failure instanceof BizException biz) throw biz;
            throw new BizException(ErrorCode.COMPANION_UPSTREAM_FAILED, safeCategory(failure));
        }
    }

    private Result completed(CompanionTurn turn, ChatMessage assistant, String retrievalDecision, String safetyMode) {
        if (assistant == null) return inProgress(turn);
        // The claim object belongs to the pre-completion transaction and therefore still says RUNNING.
        return new Result(turn.getId(), turn.getUserMessageId(), assistant, CompanionTurn.STATUS_SUCCESS, false, retrievalDecision, safetyMode, SIMULATION_NOTICE);
    }

    private Result inProgress(CompanionTurn turn) {
        return new Result(turn.getId(), turn.getUserMessageId(), null, turn.getStatus(), true, null, null, SIMULATION_NOTICE);
    }

    private String requiredHistoryStance(CompanionContext context) {
        // 有检索证据必须声明 GROUNDED；明确无证据时必须声明 NO_EVIDENCE，避免语气自然但事实越界。
        if (!context.chunks().isEmpty()) return CompanionReplyDraft.GROUNDED;
        return "NO_EVIDENCE".equals(context.retrievalDecision()) ? CompanionReplyDraft.NO_EVIDENCE : null;
    }

    private String auditJson(CompanionDraftValidator.ValidatedReply reply) {
        // Both values passed here have already been checked against fixed enum allowlists.
        return "{\"version\":\"" + properties.companion().safetyVersion() + "\",\"mode\":\"" + reply.safetyMode()
                + "\",\"historyStance\":\"" + reply.historyStance() + "\"}";
    }

    private String safetyAuditJson(CompanionSafetyGate.Decision decision) {
        return "{\"version\":\"" + properties.companion().safetyVersion() + "\",\"rule\":\"" + decision.ruleCode()
                + "\",\"mode\":\"" + decision.safetyMode() + "\",\"historyStance\":\"" + CompanionReplyDraft.NOT_APPLICABLE + "\"}";
    }

    private static String safeCategory(Exception failure) {
        String value = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && failure.getMessage().matches("[A-Z_]{3,80}"))
            value += ": " + failure.getMessage();
        return value;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record Result(String turnId, String userMessageId, ChatMessage assistantMessage, String status,
                         boolean inProgress,
                         String retrievalDecision, String safetyMode, String simulationNotice) {
    }
}
