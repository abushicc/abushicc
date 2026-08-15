package com.example.relationshipagent.memory.validation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.memory.evidence.ObservationEvidenceRef;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import org.springframework.stereotype.Service;

/**
 * Re-reads evidence immediately before persistence and verifies file and session ownership.
 */
@Service
public class ObservationEvidenceResolver {
    private final MessageRepository messages;
    private final ConversationSessionRepository sessions;
    private final SessionMessageRepository links;

    public ObservationEvidenceResolver(MessageRepository messages, ConversationSessionRepository sessions, SessionMessageRepository links) {
        this.messages = messages;
        this.sessions = sessions;
        this.links = links;
    }

    public ResolvedObservationEvidence resolve(String chatFileId, String expectedSessionId, ObservationEvidenceRef ref) {
        Message message = messages.selectById(ref.messageId());
        ConversationSession session = sessions.selectById(expectedSessionId);
        if (message == null || session == null || !chatFileId.equals(message.getChatFileId()) || !chatFileId.equals(session.getChatFileId()))
            return null;
        long membership = links.selectCount(new LambdaQueryWrapper<SessionMessage>().eq(SessionMessage::getSessionId, expectedSessionId).eq(SessionMessage::getMessageId, message.getId()));
        if (membership != 1) return null;
        return new ResolvedObservationEvidence(message.getId(), session.getId(), message.getCleanedContent(), message.getMessageTime(), message.getSpeaker());
    }

    public record ResolvedObservationEvidence(String messageId, String sessionId, String quoteText,
                                              java.time.Instant messageTime, String speaker) {
    }
}
