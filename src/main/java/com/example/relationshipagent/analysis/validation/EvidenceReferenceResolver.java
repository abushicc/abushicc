package com.example.relationshipagent.analysis.validation;

import com.example.relationshipagent.analysis.evidence.EvidenceKind;
import com.example.relationshipagent.analysis.evidence.EvidenceRef;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.retrieval.RetrievalChunk;
import com.example.relationshipagent.retrieval.RetrievalChunkRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import org.springframework.stereotype.Service;

/**
 * Rehydrates an evidence reference from database state immediately before persistence.
 */
@Service
public class EvidenceReferenceResolver {
    private final MessageRepository messages;
    private final ConversationSessionRepository sessions;
    private final RetrievalChunkRepository chunks;

    public EvidenceReferenceResolver(MessageRepository messages, ConversationSessionRepository sessions, RetrievalChunkRepository chunks) {
        this.messages = messages;
        this.sessions = sessions;
        this.chunks = chunks;
    }

    public ResolvedEvidence resolve(String chatFileId, EvidenceRef ref) {
        if (ref.kind() == EvidenceKind.STATISTIC)
            return new ResolvedEvidence(ref, null, null, null, ref.statisticPath(), ref.text(), ref.occurredAt());
        if (ref.kind() == EvidenceKind.MESSAGE) {
            Message m = messages.selectById(ref.messageId());
            return m != null && chatFileId.equals(m.getChatFileId()) ? new ResolvedEvidence(ref, m.getId(), null, null, null, m.getCleanedContent(), m.getMessageTime()) : null;
        }
        if (ref.kind() == EvidenceKind.SESSION) {
            ConversationSession s = sessions.selectById(ref.sessionId());
            return s != null && chatFileId.equals(s.getChatFileId()) ? new ResolvedEvidence(ref, null, s.getId(), null, null, null, s.getStartTime()) : null;
        }
        RetrievalChunk c = chunks.selectById(ref.chunkId());
        return c != null && chatFileId.equals(c.getChatFileId()) ? new ResolvedEvidence(ref, null, c.getParentSessionId(), c.getId(), null, c.getRetrievalText(), null) : null;
    }

    public record ResolvedEvidence(EvidenceRef source, String messageId, String sessionId, String chunkId,
                                   String statisticPath, String quoteText, java.time.Instant messageTime) {
    }
}
