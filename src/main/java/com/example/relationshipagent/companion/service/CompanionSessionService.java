package com.example.relationshipagent.companion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.companion.repository.ChatSessionRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.example.relationshipagent.persona.repository.PersonaProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns ChatSession lifecycle and the fixed-Persona invariant.
 */
@Service
public class CompanionSessionService {
    private static final Logger log = LoggerFactory.getLogger(CompanionSessionService.class);

    private final ChatFileRepository files;
    private final ChatSessionRepository sessions;
    private final PersonaProfileRepository personas;
    private final RelationshipAgentProperties properties;

    public CompanionSessionService(ChatFileRepository files, ChatSessionRepository sessions,
                                   PersonaProfileRepository personas, RelationshipAgentProperties properties) {
        this.files = files;
        this.sessions = sessions;
        this.personas = personas;
        this.properties = properties;
    }

    @Transactional
    public ChatSession create(String chatFileId, String requestedTarget) {
        requireEnabled();
        ChatFile file = files.selectById(chatFileId);
        if (file == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
        if (!ChatFile.STATUS_READY.equals(file.getStatus())) throw new BizException(ErrorCode.CHAT_FILE_NOT_READY);
        String target = target(requestedTarget);
        List<PersonaProfile> active = personas.selectList(new LambdaQueryWrapper<PersonaProfile>()
                .eq(PersonaProfile::getChatFileId, chatFileId).eq(PersonaProfile::getTargetPerson, target)
                .eq(PersonaProfile::getStatus, PersonaProfile.STATUS_ACTIVE).last("LIMIT 2"));
        if (active.size() != 1) throw new BizException(ErrorCode.COMPANION_PERSONA_UNAVAILABLE);
        PersonaProfile persona = active.get(0);
        Instant now = Instant.now();
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setChatFileId(chatFileId);
        session.setTargetPerson(target);
        session.setPersonaProfileId(persona.getId());
        session.setPersonaVersion(persona.getVersion());
        session.setStatus(ChatSession.STATUS_ACTIVE);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setLastMessageAt(now);
        session.setTurnsSinceLastSearch(0);
        session.setContextVersion(properties.companion().contextVersion());
        session.setVersion(0);
        sessions.insert(session);
        log.info("Companion session created: chatFileId={}, sessionId={}, targetPerson={}, personaId={}, personaVersion={}",
                chatFileId, session.getId(), target, persona.getId(), persona.getVersion());
        return session;
    }

    public ChatSession get(String chatFileId, String sessionId) {
        ChatSession session = sessions.selectById(sessionId);
        if (session == null || !chatFileId.equals(session.getChatFileId()))
            throw new BizException(ErrorCode.COMPANION_SESSION_NOT_FOUND);
        return session;
    }

    public List<ChatSession> list(String chatFileId, String target, String status, int size) {
        LambdaQueryWrapper<ChatSession> query = new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getChatFileId, chatFileId);
        if (target != null && !target.isBlank()) query.eq(ChatSession::getTargetPerson, target);
        if (status != null && !status.isBlank()) query.eq(ChatSession::getStatus, status);
        return sessions.selectList(query.orderByDesc(ChatSession::getUpdatedAt).last("LIMIT " + Math.max(1, Math.min(size, 100))));
    }

    @Transactional
    public ChatSession requireActive(String chatFileId, String sessionId) {
        ChatSession session = get(chatFileId, sessionId);
        Instant now = Instant.now();
        Instant activity = session.getLastMessageAt() != null ? session.getLastMessageAt() : session.getCreatedAt();
        if (ChatSession.STATUS_ACTIVE.equals(session.getStatus()) && activity != null
                && activity.plusSeconds(properties.companion().sessionIdleMinutes() * 60L).isBefore(now)) {
            sessions.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, sessionId).eq(ChatSession::getStatus, ChatSession.STATUS_ACTIVE)
                    .set(ChatSession::getStatus, ChatSession.STATUS_ENDED).set(ChatSession::getEndedAt, now).set(ChatSession::getUpdatedAt, now));
            log.info("Companion session expired: chatFileId={}, sessionId={}, idleMinutes={}",
                    chatFileId, sessionId, properties.companion().sessionIdleMinutes());
            throw new BizException(ErrorCode.COMPANION_SESSION_ENDED);
        }
        if (!ChatSession.STATUS_ACTIVE.equals(session.getStatus()))
            throw new BizException(ErrorCode.COMPANION_SESSION_ENDED);
        PersonaProfile persona = personas.selectById(session.getPersonaProfileId());
        if (persona == null || !PersonaProfile.STATUS_ACTIVE.equals(persona.getStatus()))
            throw new BizException(ErrorCode.COMPANION_PERSONA_UNAVAILABLE);
        return session;
    }

    @Transactional
    public ChatSession end(String chatFileId, String sessionId) {
        ChatSession session = get(chatFileId, sessionId);
        Instant now = Instant.now();
        if (ChatSession.STATUS_ACTIVE.equals(session.getStatus()))
            sessions.update(null, new LambdaUpdateWrapper<ChatSession>().eq(ChatSession::getId, sessionId)
                    .set(ChatSession::getStatus, ChatSession.STATUS_ENDED).set(ChatSession::getEndedAt, now).set(ChatSession::getUpdatedAt, now));
        log.info("Companion session ended: chatFileId={}, sessionId={}", chatFileId, sessionId);
        return get(chatFileId, sessionId);
    }

    private void requireEnabled() {
        if (properties.companion() == null || !properties.companion().enabled())
            throw new BizException(ErrorCode.COMPANION_DISABLED);
    }

    private String target(String requested) {
        String value = requested == null || requested.isBlank() ? properties.companion().defaultTargetPerson() : requested.trim();
        if (value == null || value.isBlank())
            throw new BizException(ErrorCode.PARAM_INVALID, "targetPerson is required");
        return value;
    }
}
