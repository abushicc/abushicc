package com.example.relationshipagent.analysis.detector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.feature.DeterministicFeatureService;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads one complete factual context once, so individual detectors stay pure and testable.
 */
@Service
public class AnalysisContextFactory {

    private final DeterministicFeatureService featureService;
    private final MessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;

    public AnalysisContextFactory(DeterministicFeatureService featureService,
                                  MessageRepository messageRepository,
                                  ConversationSessionRepository sessionRepository,
                                  SessionMessageRepository sessionMessageRepository) {
        this.featureService = featureService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
    }

    public AnalysisContext create(String chatFileId) {
        List<Message> messages = messageRepository.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatFileId, chatFileId)
                .orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId));
        List<ConversationSession> sessions = sessionRepository.selectList(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId)
                .orderByAsc(ConversationSession::getStartTime).orderByAsc(ConversationSession::getId));
        return new AnalysisContext(featureService.compute(chatFileId), messages, sessions, loadSessionMessages(sessions));
    }

    private Map<String, List<Message>> loadSessionMessages(List<ConversationSession> sessions) {
        if (sessions.isEmpty()) return Map.of();
        List<SessionMessage> links = sessionMessageRepository.selectList(new LambdaQueryWrapper<SessionMessage>()
                .in(SessionMessage::getSessionId, sessions.stream().map(ConversationSession::getId).toList())
                .orderByAsc(SessionMessage::getSessionId).orderByAsc(SessionMessage::getSeqInSession));
        if (links.isEmpty()) return Map.of();
        Map<String, Message> byId = messageRepository.selectBatchIds(links.stream().map(SessionMessage::getMessageId).toList())
                .stream().collect(Collectors.toMap(Message::getId, message -> message));
        Map<String, List<Message>> result = new LinkedHashMap<>();
        for (SessionMessage link : links) {
            Message message = byId.get(link.getMessageId());
            if (message != null) result.computeIfAbsent(link.getSessionId(), ignored -> new ArrayList<>()).add(message);
        }
        result.values().forEach(list -> list.sort(Comparator.comparing(Message::getMessageTime)
                .thenComparing(message -> message.getSourceLocalId() == null ? Long.MIN_VALUE : message.getSourceLocalId())));
        return result;
    }
}
