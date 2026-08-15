package com.example.relationshipagent.persona.input;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import com.example.relationshipagent.statistics.StatisticsCache;
import com.example.relationshipagent.statistics.StatisticsCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds Persona input only from approved Memory and original message/session tables.
 */
@Service
public class PersonaBuildInputService {
    private static final Pattern PII = Pattern.compile("(?:1\\d{10}|\\d{6,}|@|身份证|住址|银行卡)");
    private final MemoryItemRepository memories;
    private final StatisticsCacheRepository statistics;
    private final ConversationSessionRepository sessions;
    private final SessionMessageRepository links;
    private final MessageRepository messages;
    private final ObjectMapper json;

    public PersonaBuildInputService(MemoryItemRepository memories, StatisticsCacheRepository statistics, ConversationSessionRepository sessions, SessionMessageRepository links, MessageRepository messages, ObjectMapper json) {
        this.memories = memories;
        this.statistics = statistics;
        this.sessions = sessions;
        this.links = links;
        this.messages = messages;
        this.json = json;
    }

    public PersonaBuildInput build(String chatFileId, String target) {
        List<MemoryItem> source = memories.selectList(new LambdaQueryWrapper<MemoryItem>().eq(MemoryItem::getChatFileId, chatFileId).eq(MemoryItem::getTargetPerson, target).eq(MemoryItem::getStatus, MemoryItem.STATUS_ACTIVE).eq(MemoryItem::getReviewStatus, MemoryItem.REVIEW_APPROVED).orderByAsc(MemoryItem::getCreatedAt));
        StatisticsCache cache = statistics.selectOne(new LambdaQueryWrapper<StatisticsCache>().eq(StatisticsCache::getChatFileId, chatFileId));
        JsonNode fingerprint = fingerprint(cache, target);
        List<ConversationSession> all = sessions.selectList(new LambdaQueryWrapper<ConversationSession>().eq(ConversationSession::getChatFileId, chatFileId).orderByAsc(ConversationSession::getStartTime));
        Instant from = all.isEmpty() ? null : all.get(0).getStartTime(), to = all.isEmpty() ? null : all.get(all.size() - 1).getEndTime();
        return new PersonaBuildInput(chatFileId, target, List.copyOf(source), fingerprint, from, to, representative(all, target));
    }

    private JsonNode fingerprint(StatisticsCache cache, String target) {
        try {
            JsonNode root = cache == null ? json.createObjectNode() : json.readTree(cache.getStatsJson());
            return root.path("styleFingerprint").path(target);
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private List<PersonaFewShotCandidate> representative(List<ConversationSession> source, String target) {
        List<PersonaFewShotCandidate> out = new ArrayList<>();
        for (var session : source) {
            if (out.size() >= 12) break;
            List<SessionMessage> ordered = links.selectList(new LambdaQueryWrapper<SessionMessage>().eq(SessionMessage::getSessionId, session.getId()).orderByAsc(SessionMessage::getSeqInSession));
            if (ordered.size() < 2) continue;
            Map<String, Message> byId = messages.selectBatchIds(ordered.stream().map(SessionMessage::getMessageId).toList()).stream().collect(Collectors.toMap(Message::getId, m -> m));
            for (int i = 1; i < ordered.size() && out.size() < 12; i++) {
                Message current = byId.get(ordered.get(i).getMessageId()), previous = byId.get(ordered.get(i - 1).getMessageId());
                if (current == null || previous == null || !target.equals(current.getSpeaker()) || !usable(current) || !usable(previous) || current.getMessageTime().isBefore(previous.getMessageTime()))
                    continue;
                out.add(new PersonaFewShotCandidate(session.getId(), List.of(previous.getId()), List.of(current.getId())));
                break;
            }
        }
        return List.copyOf(out);
    }

    private boolean usable(Message m) {
        return m.getCleanedContent() != null && !m.getCleanedContent().isBlank() && !PII.matcher(m.getCleanedContent()).find();
    }
}
