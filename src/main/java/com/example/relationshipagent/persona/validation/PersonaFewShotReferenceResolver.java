package com.example.relationshipagent.persona.validation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.persona.agent.PersonaDraft;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Last-write database rehydration for Persona few-shot IDs. Model and user JSON never supply the text.
 */
@Component
public class PersonaFewShotReferenceResolver {
    private final ConversationSessionRepository sessions;
    private final SessionMessageRepository links;
    private final MessageRepository messages;

    public PersonaFewShotReferenceResolver(ConversationSessionRepository sessions, SessionMessageRepository links, MessageRepository messages) {
        this.sessions = sessions;
        this.links = links;
        this.messages = messages;
    }

    public boolean valid(String chatFileId, String target, List<PersonaDraft.FewShotDraft> source) {
        for (var draft : source) {
            ConversationSession session = sessions.selectById(draft.sessionId());
            if (session == null || !chatFileId.equals(session.getChatFileId())) return false;
            List<SessionMessage> sessionLinks = links.selectList(new QueryWrapper<SessionMessage>().eq("session_id", session.getId()).orderByAsc("seq_in_session"));
            Map<String, Integer> order = new HashMap<>();
            for (var link : sessionLinks) order.put(link.getMessageId(), link.getSeqInSession());
            Set<String> ids = new HashSet<>();
            ids.addAll(draft.contextMessageIds());
            ids.addAll(draft.targetMessageIds());
            if (ids.isEmpty() || !order.keySet().containsAll(ids)) return false;
            Map<String, Message> byId = messages.selectBatchIds(ids).stream().collect(Collectors.toMap(Message::getId, m -> m));
            if (byId.size() != ids.size()) return false;
            int lastContext = -1;
            for (String id : draft.contextMessageIds()) {
                Message m = byId.get(id);
                if (m == null || !chatFileId.equals(m.getChatFileId()) || m.getCleanedContent() == null || m.getCleanedContent().isBlank())
                    return false;
                lastContext = Math.max(lastContext, order.get(id));
            }
            for (String id : draft.targetMessageIds()) {
                Message m = byId.get(id);
                if (m == null || !chatFileId.equals(m.getChatFileId()) || !target.equals(m.getSpeaker()) || m.getCleanedContent() == null || m.getCleanedContent().isBlank() || order.get(id) <= lastContext)
                    return false;
            }
        }
        return true;
    }
}
