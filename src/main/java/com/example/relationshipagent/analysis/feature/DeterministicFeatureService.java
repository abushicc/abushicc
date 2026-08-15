package com.example.relationshipagent.analysis.feature;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.message.MessageRepository;
import com.example.relationshipagent.session.ConversationSession;
import com.example.relationshipagent.session.ConversationSessionRepository;
import com.example.relationshipagent.session.SessionMessage;
import com.example.relationshipagent.session.SessionMessageRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Computes complete time-series features from stored facts. It deliberately contains no prompt,
 * model call, sentiment score, or causal interpretation.
 */
@Service
public class DeterministicFeatureService {

    private static final String SCHEMA_VERSION = "relationship-features-v1";
    private static final int TERMINAL_SESSION_COUNT = 20;
    private static final List<Integer> TERMINAL_WINDOWS_MONTHS = List.of(3, 6, 12);

    private final ChatFileRepository chatFileRepository;
    private final MessageRepository messageRepository;
    private final ConversationSessionRepository sessionRepository;
    private final SessionMessageRepository sessionMessageRepository;

    public DeterministicFeatureService(ChatFileRepository chatFileRepository,
                                       MessageRepository messageRepository,
                                       ConversationSessionRepository sessionRepository,
                                       SessionMessageRepository sessionMessageRepository) {
        this.chatFileRepository = chatFileRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.sessionMessageRepository = sessionMessageRepository;
    }

    public RelationshipFeatureSet compute(String chatFileId) {
        ChatFile file = chatFileRepository.selectById(chatFileId);
        if (file == null) throw new IllegalArgumentException("Chat file does not exist: " + chatFileId);
        ZoneId zoneId = ZoneId.of(file.getSourceTimezone() == null || file.getSourceTimezone().isBlank()
                ? "Asia/Shanghai" : file.getSourceTimezone());
        List<Message> messages = messageRepository.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatFileId, chatFileId)
                .orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId));
        List<ConversationSession> sessions = sessionRepository.selectList(new LambdaQueryWrapper<ConversationSession>()
                .eq(ConversationSession::getChatFileId, chatFileId)
                .orderByAsc(ConversationSession::getStartTime).orderByAsc(ConversationSession::getId));
        return compute(zoneId, messages, sessions, loadSessionMessages(sessions));
    }

    /**
     * Package-visible pure function for unit tests and later replay verification.
     */
    RelationshipFeatureSet compute(ZoneId zoneId, List<Message> messages,
                                   List<ConversationSession> sessions,
                                   Map<String, List<Message>> messagesBySession) {
        List<Message> orderedMessages = messages.stream().filter(m -> m.getMessageTime() != null)
                .sorted(Comparator.comparing(Message::getMessageTime)
                        .thenComparing(m -> m.getSourceLocalId() == null ? Long.MIN_VALUE : m.getSourceLocalId()))
                .toList();
        List<ConversationSession> orderedSessions = sessions.stream().filter(s -> s.getStartTime() != null)
                .sorted(Comparator.comparing(ConversationSession::getStartTime).thenComparing(ConversationSession::getId))
                .toList();
        Map<String, Long> typeCounts = orderedMessages.stream().collect(Collectors.groupingBy(
                m -> m.getMessageType() == null ? "UNKNOWN" : m.getMessageType(), LinkedHashMap::new, Collectors.counting()));
        long mediaCount = orderedMessages.stream().filter(this::isMedia).count();
        RelationshipFeatureSet.Coverage coverage = new RelationshipFeatureSet.Coverage(
                orderedMessages.isEmpty() ? null : orderedMessages.get(0).getMessageTime(),
                orderedMessages.isEmpty() ? null : orderedMessages.get(orderedMessages.size() - 1).getMessageTime(),
                orderedMessages.size(), orderedSessions.size(), typeCounts, mediaCount, 0L);
        if (orderedMessages.isEmpty()) {
            return new RelationshipFeatureSet(SCHEMA_VERSION, zoneId.getId(), coverage, List.of(),
                    new RelationshipFeatureSet.TerminalFeature("NO_MESSAGES", null, null, null, null,
                            false, List.of(), List.of()));
        }

        List<String> speakers = orderedMessages.stream().filter(this::isHumanMessage).map(Message::getSpeaker)
                .filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
        TreeMap<YearMonth, MutableMonth> months = buildMonthRange(orderedMessages, speakers, zoneId);
        Map<String, Message> messagesById = orderedMessages.stream()
                .collect(Collectors.toMap(Message::getId, m -> m, (left, ignored) -> left, LinkedHashMap::new));
        List<SessionFacts> sessionFacts = toSessionFacts(orderedSessions, messagesBySession, messagesById, speakers);

        for (Message message : orderedMessages) {
            MutableMonth month = months.get(YearMonth.from(message.getMessageTime().atZone(zoneId)));
            month.messageCount++;
            month.activeDays.add(LocalDate.from(message.getMessageTime().atZone(zoneId)));
            if (isHumanMessage(message) && message.getSpeaker() != null) {
                month.speakerMessageCounts.merge(message.getSpeaker(), 1L, Long::sum);
            }
        }
        Instant priorEnd = null;
        for (SessionFacts facts : sessionFacts) {
            MutableMonth month = months.get(YearMonth.from(facts.session().getStartTime().atZone(zoneId)));
            month.sessionCount++;
            month.sessionMessageCounts.add((long) facts.messages().size());
            month.sessionDurations.add((long) Math.max(0, facts.session().getDurationSeconds() == null
                    ? Duration.between(facts.session().getStartTime(), facts.session().getEndTime()).getSeconds()
                    : facts.session().getDurationSeconds()));
            facts.turnCounts().forEach((speaker, count) -> month.turnCounts.get(speaker).add(count));
            if (facts.firstSpeaker() != null) month.sessionStarts.merge(facts.firstSpeaker(), 1L, Long::sum);
            if (facts.humanMessageCount() == 1 && facts.firstSpeaker() != null) {
                month.singleMessageUnanswered.merge(facts.firstSpeaker(), 1L, Long::sum);
            }
            if (priorEnd != null) {
                long gap = Math.max(0, Duration.between(priorEnd, facts.session().getStartTime()).getSeconds());
                month.interSessionGaps.add(gap);
            }
            if (facts.session().getEndTime() != null) priorEnd = facts.session().getEndTime();
            collectReplies(facts.messages(), zoneId, months);
        }
        long globalGapP90 = percentile(sessionFacts.stream().skip(1)
                .map(f -> Duration.between(previousEnd(sessionFacts, f), f.session().getStartTime()).getSeconds())
                .filter(value -> value >= 0).sorted().toList(), 0.90d);
        List<RelationshipFeatureSet.MonthlyFeature> monthly = months.values().stream()
                .map(month -> month.toFeature(speakers, globalGapP90)).toList();
        return new RelationshipFeatureSet(SCHEMA_VERSION, zoneId.getId(), coverage, monthly,
                terminalFeature(orderedMessages, sessionFacts, speakers));
    }

    private Map<String, List<Message>> loadSessionMessages(List<ConversationSession> sessions) {
        if (sessions.isEmpty()) return Map.of();
        List<String> ids = sessions.stream().map(ConversationSession::getId).toList();
        List<SessionMessage> links = sessionMessageRepository.selectList(new LambdaQueryWrapper<SessionMessage>()
                .in(SessionMessage::getSessionId, ids).orderByAsc(SessionMessage::getSessionId)
                .orderByAsc(SessionMessage::getSeqInSession));
        if (links.isEmpty()) return Map.of();
        List<String> messageIds = links.stream().map(SessionMessage::getMessageId).toList();
        List<Message> loaded = messageRepository.selectBatchIds(messageIds);
        Map<String, Message> byId = loaded.stream().collect(Collectors.toMap(Message::getId, message -> message));
        Map<String, List<Message>> result = new LinkedHashMap<>();
        for (SessionMessage link : links) {
            Message message = byId.get(link.getMessageId());
            if (message != null) result.computeIfAbsent(link.getSessionId(), ignored -> new ArrayList<>()).add(message);
        }
        return result;
    }

    private TreeMap<YearMonth, MutableMonth> buildMonthRange(List<Message> messages, List<String> speakers, ZoneId zoneId) {
        TreeMap<YearMonth, MutableMonth> result = new TreeMap<>();
        YearMonth cursor = YearMonth.from(messages.get(0).getMessageTime().atZone(zoneId));
        YearMonth last = YearMonth.from(messages.get(messages.size() - 1).getMessageTime().atZone(zoneId));
        while (!cursor.isAfter(last)) {
            result.put(cursor, new MutableMonth(cursor.toString(), speakers));
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private List<SessionFacts> toSessionFacts(List<ConversationSession> sessions,
                                              Map<String, List<Message>> messagesBySession,
                                              Map<String, Message> messagesById, List<String> speakers) {
        List<SessionFacts> result = new ArrayList<>();
        for (ConversationSession session : sessions) {
            List<Message> messages = new ArrayList<>(messagesBySession.getOrDefault(session.getId(), List.of()));
            if (messages.isEmpty()) {
                messages = messagesById.values().stream().filter(message -> message.getMessageTime() != null
                                && !message.getMessageTime().isBefore(session.getStartTime())
                                && (session.getEndTime() == null || !message.getMessageTime().isAfter(session.getEndTime())))
                        .sorted(Comparator.comparing(Message::getMessageTime)).toList();
            }
            Map<String, Long> turns = speakers.stream().collect(Collectors.toMap(s -> s, s -> 0L,
                    (left, ignored) -> left, LinkedHashMap::new));
            String firstSpeaker = null;
            String lastSpeaker = null;
            long humanCount = 0;
            String previousSpeaker = null;
            for (Message message : messages) {
                if (!isHumanMessage(message) || message.getSpeaker() == null) continue;
                humanCount++;
                if (firstSpeaker == null) firstSpeaker = message.getSpeaker();
                lastSpeaker = message.getSpeaker();
                if (!message.getSpeaker().equals(previousSpeaker)) {
                    turns.merge(message.getSpeaker(), 1L, Long::sum);
                    previousSpeaker = message.getSpeaker();
                }
            }
            result.add(new SessionFacts(session, messages, turns, firstSpeaker, lastSpeaker, humanCount));
        }
        return result;
    }

    private void collectReplies(List<Message> messages, ZoneId zoneId, TreeMap<YearMonth, MutableMonth> months) {
        Message lastHuman = null;
        for (Message message : messages) {
            if (!isHumanMessage(message) || message.getSpeaker() == null || message.getMessageTime() == null) continue;
            if (lastHuman != null && !message.getSpeaker().equals(lastHuman.getSpeaker())) {
                long seconds = Duration.between(lastHuman.getMessageTime(), message.getMessageTime()).getSeconds();
                if (seconds >= 0) {
                    YearMonth month = YearMonth.from(message.getMessageTime().atZone(zoneId));
                    months.get(month).replyDelays.get(message.getSpeaker()).add(seconds);
                }
            }
            lastHuman = message;
        }
    }

    private RelationshipFeatureSet.TerminalFeature terminalFeature(List<Message> messages, List<SessionFacts> sessions,
                                                                   List<String> speakers) {
        SessionFacts lastSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        Message lastMessage = messages.get(messages.size() - 1);
        Instant anchor = lastSession == null ? lastMessage.getMessageTime() : lastSession.session().getEndTime();
        List<RelationshipFeatureSet.TerminalWindow> windows = new ArrayList<>();
        for (Integer length : TERMINAL_WINDOWS_MONTHS) {
            Instant start = anchor.minus(Duration.ofDays(30L * length));
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, Long> starts = new LinkedHashMap<>();
            speakers.forEach(speaker -> {
                counts.put(speaker, 0L);
                starts.put(speaker, 0L);
            });
            messages.stream().filter(message -> !message.getMessageTime().isBefore(start))
                    .filter(this::isHumanMessage).forEach(message -> counts.merge(message.getSpeaker(), 1L, Long::sum));
            long sessionCount = sessions.stream().filter(session -> !session.session().getStartTime().isBefore(start)).count();
            sessions.stream().filter(session -> !session.session().getStartTime().isBefore(start))
                    .filter(session -> session.firstSpeaker() != null)
                    .forEach(session -> starts.merge(session.firstSpeaker(), 1L, Long::sum));
            windows.add(new RelationshipFeatureSet.TerminalWindow(length, start,
                    counts.values().stream().mapToLong(Long::longValue).sum(), sessionCount, counts, starts));
        }
        List<RelationshipFeatureSet.TerminalSession> lastSessions = sessions.stream()
                .skip(Math.max(0, sessions.size() - TERMINAL_SESSION_COUNT))
                .map(session -> new RelationshipFeatureSet.TerminalSession(session.session().getId(),
                        session.session().getStartTime(), session.session().getEndTime(), session.messages().size(),
                        session.firstSpeaker(), session.lastSpeaker())).toList();
        return new RelationshipFeatureSet.TerminalFeature("LAST_SESSION_FALLBACK", anchor,
                lastSession == null ? null : lastSession.session().getId(),
                lastSession == null ? null : lastSession.firstSpeaker(), lastMessage.getSpeaker(), false,
                windows, lastSessions);
    }

    private static Instant previousEnd(List<SessionFacts> sessions, SessionFacts current) {
        int index = sessions.indexOf(current);
        return index <= 0 ? current.session().getStartTime() : sessions.get(index - 1).session().getEndTime();
    }

    private boolean isHumanMessage(Message message) {
        return message.getMessageType() == null || !Message.TYPE_SYSTEM.equals(message.getMessageType());
    }

    private boolean isMedia(Message message) {
        return switch (message.getMessageType() == null ? "" : message.getMessageType()) {
            case Message.TYPE_IMAGE, Message.TYPE_VOICE, Message.TYPE_VIDEO, Message.TYPE_FILE, Message.TYPE_LOCATION ->
                    true;
            default -> false;
        };
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0L;
        double rank = percentile * (values.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        return Math.round(values.get(low) + (values.get(high) - values.get(low)) * (rank - low));
    }

    private record SessionFacts(ConversationSession session, List<Message> messages, Map<String, Long> turnCounts,
                                String firstSpeaker, String lastSpeaker, long humanMessageCount) {
    }

    private static final class MutableMonth {
        private final String month;
        private long messageCount;
        private long sessionCount;
        private final Set<LocalDate> activeDays = new LinkedHashSet<>();
        private final Map<String, Long> speakerMessageCounts = new LinkedHashMap<>();
        private final Map<String, List<Long>> replyDelays = new LinkedHashMap<>();
        private final Map<String, Long> sessionStarts = new LinkedHashMap<>();
        private final Map<String, Long> singleMessageUnanswered = new LinkedHashMap<>();
        private final List<Long> sessionMessageCounts = new ArrayList<>();
        private final List<Long> sessionDurations = new ArrayList<>();
        private final Map<String, List<Long>> turnCounts = new LinkedHashMap<>();
        private final List<Long> interSessionGaps = new ArrayList<>();

        private MutableMonth(String month, List<String> speakers) {
            this.month = month;
            for (String speaker : speakers) {
                speakerMessageCounts.put(speaker, 0L);
                replyDelays.put(speaker, new ArrayList<>());
                sessionStarts.put(speaker, 0L);
                singleMessageUnanswered.put(speaker, 0L);
                turnCounts.put(speaker, new ArrayList<>());
            }
        }

        private RelationshipFeatureSet.MonthlyFeature toFeature(List<String> speakers, long globalGapP90) {
            Map<String, RelationshipFeatureSet.ReplyMetric> replies = new LinkedHashMap<>();
            Map<String, Double> turnP50 = new LinkedHashMap<>();
            Map<String, Double> turnP90 = new LinkedHashMap<>();
            for (String speaker : speakers) {
                List<Long> reply = replyDelays.get(speaker).stream().sorted().toList();
                replies.put(speaker, reply.isEmpty()
                        ? new RelationshipFeatureSet.ReplyMetric(null, null, 0)
                        : new RelationshipFeatureSet.ReplyMetric((double) percentile(reply, .5), (double) percentile(reply, .9), reply.size()));
                List<Long> turns = turnCounts.get(speaker).stream().sorted().toList();
                turnP50.put(speaker, turns.isEmpty() ? null : (double) percentile(turns, .5));
                turnP90.put(speaker, turns.isEmpty() ? null : (double) percentile(turns, .9));
            }
            List<Long> counts = sessionMessageCounts.stream().sorted().toList();
            List<Long> durations = sessionDurations.stream().sorted().toList();
            double gapRatio = globalGapP90 <= 0 || interSessionGaps.isEmpty() ? 0d
                    : (double) interSessionGaps.stream().filter(gap -> gap >= globalGapP90).count() / interSessionGaps.size();
            return new RelationshipFeatureSet.MonthlyFeature(month, messageCount, sessionCount, activeDays.size(),
                    Map.copyOf(speakerMessageCounts), Map.copyOf(replies), Map.copyOf(sessionStarts),
                    Map.copyOf(singleMessageUnanswered), new RelationshipFeatureSet.SessionIntensity(
                    counts.isEmpty() ? null : (double) percentile(counts, .5),
                    counts.isEmpty() ? null : (double) percentile(counts, .9),
                    durations.isEmpty() ? null : (double) percentile(durations, .5),
                    durations.isEmpty() ? null : (double) percentile(durations, .9),
                    readonlyAllowingNulls(turnP50), readonlyAllowingNulls(turnP90), counts.size()), gapRatio);
        }

        private static <K, V> Map<K, V> readonlyAllowingNulls(Map<K, V> values) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
