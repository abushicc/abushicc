package com.example.relationshipagent.memory.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import com.example.relationshipagent.processing.ProcessingJobService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds evidence only from original message/session tables; generated chat tables are never queried.
 */
@Service
public class ObservationEvidencePacketBuilder {
    private final ConversationSessionRepository sessions;
    private final SessionMessageRepository sessionMessages;
    private final MessageRepository messages;

    public ObservationEvidencePacketBuilder(ConversationSessionRepository sessions, SessionMessageRepository sessionMessages, MessageRepository messages) {
        this.sessions = sessions;
        this.sessionMessages = sessionMessages;
        this.messages = messages;
    }

    public List<ObservationEvidencePacket> build(String chatFileId, String targetPerson, int maxInputChars) {
        if (maxInputChars <= 0) throw new IllegalArgumentException("Memory input limit must be positive");
        List<ConversationSession> source = sessions.selectList(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId).orderByAsc(ConversationSession::getStartTime));
        return buildPackets(source, targetPerson, maxInputChars);
    }

    /**
     * Bounded benchmark/review mode: only caller-selected sessions are read and later eligible for replacement.
     */
    public List<ObservationEvidencePacket> buildSelected(String chatFileId, String targetPerson, int maxInputChars, Collection<String> selectedSessionIds) {
        if (selectedSessionIds == null || selectedSessionIds.isEmpty())
            return build(chatFileId, targetPerson, maxInputChars);
        Set<String> requested = new HashSet<>(selectedSessionIds);
        List<ConversationSession> source = sessions.selectList(new LambdaQueryWrapper<ConversationSession>().eq(ConversationSession::getChatFileId, chatFileId).in(ConversationSession::getId, requested).orderByAsc(ConversationSession::getStartTime));
        if (source.size() != requested.size())
            throw new IllegalArgumentException("Selected session does not belong to chat file");
        return buildPackets(source, targetPerson, maxInputChars);
    }

    private List<ObservationEvidencePacket> buildPackets(List<ConversationSession> source, String targetPerson, int maxInputChars) {
        if (maxInputChars <= 0) throw new IllegalArgumentException("Memory input limit must be positive");
        List<ObservationEvidencePacket> packets = new ArrayList<>();
        int number = 0;
        for (ConversationSession session : source) {
            List<Message> rows = messagesFor(session.getId());
            long targetCount = rows.stream().filter(m -> targetPerson.equals(m.getSpeaker())).filter(this::hasUsableText).count();
            if (targetCount == 0) continue;
            List<ObservationEvidenceRef> refs = new ArrayList<>();
            int refNo = 0;
            for (Message row : rows) {
                if (!hasUsableText(row)) continue;
                refs.add(new ObservationEvidenceRef("MES-" + String.format("%06d", ++refNo), row.getId(), session.getId(), row.getMessageTime(), row.getSpeaker(), row.getCleanedContent(), row.getMessageType()));
            }
            if (refs.isEmpty()) continue;
            int chars = refs.stream().mapToInt(ObservationEvidenceRef::characterCount).sum();
            packets.add(new ObservationEvidencePacket("SES-" + String.format("%06d", ++number), session.getId(), session.getStartTime(), session.getEndTime(), targetPerson,
                    Math.toIntExact(targetCount), chars > maxInputChars, List.copyOf(refs), List.of("该包仅支持本会话局部观察；不得升级为长期人格。")));
        }
        return List.copyOf(packets);
    }

    public List<ObservationBatch> batch(List<ObservationEvidencePacket> packets, int maxSessions, int maxChars, String inputHash, String targetPerson) {
        if (maxSessions <= 0 || maxChars <= 0)
            throw new IllegalArgumentException("Memory batch limits must be positive");
        List<ObservationBatch> result = new ArrayList<>();
        List<ObservationEvidencePacket> current = new ArrayList<>();
        int chars = 0;
        for (ObservationEvidencePacket packet : packets) {
            boolean full = !current.isEmpty() && (current.size() >= maxSessions || chars + packet.characterCount() > maxChars);
            if (full) {
                result.add(toBatch(current, chars, inputHash, targetPerson));
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(packet);
            chars += packet.characterCount();
            if (packet.characterCount() > maxChars) {
                result.add(toBatch(current, chars, inputHash, targetPerson));
                current = new ArrayList<>();
                chars = 0;
            }
        }
        if (!current.isEmpty()) result.add(toBatch(current, chars, inputHash, targetPerson));
        return List.copyOf(result);
    }

    private ObservationBatch toBatch(List<ObservationEvidencePacket> packets, int chars, String inputHash, String targetPerson) {
        String ids = packets.stream().map(ObservationEvidencePacket::sessionId).collect(Collectors.joining(","));
        return new ObservationBatch(ProcessingJobService.hashInput(inputHash, targetPerson, ids), List.copyOf(packets), chars);
    }

    private List<Message> messagesFor(String sessionId) {
        List<SessionMessage> links = sessionMessages.selectList(new LambdaQueryWrapper<SessionMessage>().eq(SessionMessage::getSessionId, sessionId).orderByAsc(SessionMessage::getSeqInSession));
        if (links.isEmpty()) return List.of();
        Map<String, Message> byId = messages.selectBatchIds(links.stream().map(SessionMessage::getMessageId).toList()).stream().collect(Collectors.toMap(Message::getId, m -> m));
        return links.stream().map(link -> byId.get(link.getMessageId())).filter(Objects::nonNull).toList();
    }

    private boolean hasUsableText(Message message) {
        return message.getCleanedContent() != null && !message.getCleanedContent().isBlank();
    }
}
