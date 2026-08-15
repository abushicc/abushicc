package com.example.relationshipagent.session;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class ConversationSessionBuilder {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Set<String> CONFLICT_KEYWORDS = Set.of(
            "不想理", "随便", "算了", "别说了", "烦不烦", "够了",
            "无所谓", "不用了", "就这样吧", "行吧", "呵呵");
    private static final Set<String> EMOTIONAL_KEYWORDS = Set.of(
            "哈哈哈哈", "呜呜", "哭了", "笑死", "哈哈哈", "嘎嘎嘎", "绝了");

    private final RelationshipAgentProperties properties;

    public ConversationSessionBuilder(RelationshipAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建会话及其归属消息(M5):一次性产出会话+消息,service 不再二次对齐。
     */
    public SessionBuildResult buildWithMessages(List<Message> messages, String timezone) {
        if (messages == null || messages.isEmpty()) {
            return new SessionBuildResult(Collections.emptyList());
        }

        long gapThreshold = properties.session().gapThresholdMinutes();
        int maxMessages = properties.session().maxMessagesPerSession();
        int mergeSeconds = properties.session().displayMergeSeconds();
        ZoneId zoneId = ZoneId.of(timezone != null ? timezone : "Asia/Shanghai");

        List<SessionWithMessages> result = new ArrayList<>();
        SessionAccumulator current = new SessionAccumulator(messages.get(0));

        // 会话按相邻消息时间间隔切分，并以最大消息数作为硬上限，避免超长会话吞噬检索上下文预算。
        for (int i = 1; i < messages.size(); i++) {
            Message msg = messages.get(i);
            long gapSeconds = Duration.between(messages.get(i - 1).getMessageTime(), msg.getMessageTime()).getSeconds();
            boolean forcedSplit = current.messages.size() >= maxMessages;
            if (gapSeconds > gapThreshold * 60L || forcedSplit) {
                result.add(current.finalize(forcedSplit, mergeSeconds, zoneId));
                current = new SessionAccumulator(msg);
            } else {
                current.messages.add(msg);
            }
        }
        result.add(current.finalize(false, mergeSeconds, zoneId));

        // 最早会话是时间线中的初识，不应被情绪/消息数分类规则覆盖。
        if (!result.isEmpty()) {
            ConversationSession first = result.get(0).session();
            first.setSessionType(ConversationSession.TYPE_FIRST_MEET);
        }
        return new SessionBuildResult(result);
    }

    /**
     * 仅返回会话列表(委托 {@link #buildWithMessages},测试在用)。
     */
    public List<ConversationSession> build(List<Message> messages, String timezone) {
        return buildWithMessages(messages, timezone).sessions().stream()
                .map(SessionWithMessages::session).toList();
    }

    public List<SessionMessage> buildSessionMessages(ConversationSession session, List<Message> messages) {
        List<SessionMessage> list = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            SessionMessage sm = new SessionMessage();
            sm.setSessionId(session.getId());
            sm.setMessageId(messages.get(i).getId());
            sm.setSeqInSession(i + 1);
            list.add(sm);
        }
        return list;
    }

    /**
     * 构建格式化文本（阶段 2 CHUNK 复用）。
     *
     * @param partialInfo 可空；非空时在头部"消息数"行追加（如"（会话内第 1-45 条 / 共 200 条）"）
     */
    public static String buildFormattedText(ZoneId zoneId, List<Message> messages,
                                            int mergeSeconds, String partialInfo) {
        StringBuilder sb = new StringBuilder();
        Instant start = messages.get(0).getMessageTime();
        Instant end = messages.get(messages.size() - 1).getMessageTime();
        Set<String> speakers = new LinkedHashSet<>();
        for (Message m : messages) speakers.add(m.getSpeaker());

        sb.append("时间：").append(start.atZone(zoneId).format(DATE_FMT)).append(" ")
                .append(start.atZone(zoneId).format(TIME_FMT)).append(" - ")
                .append(end.atZone(zoneId).format(DATE_FMT)).append(" ")
                .append(end.atZone(zoneId).format(TIME_FMT)).append("\n");
        sb.append("参与人：").append(String.join(", ", speakers)).append("\n");
        sb.append("消息数：").append(messages.size()).append(" 条");
        if (partialInfo != null) sb.append(partialInfo);
        sb.append("\n\n");

        // 同一说话人在短时间内连续发送的消息合并到一行，降低 chunk 噪声但保留时间和 speaker 边界。
        int i = 0;
        while (i < messages.size()) {
            Message m = messages.get(i);
            String time = m.getMessageTime().atZone(zoneId).format(TIME_FMT);
            String content = m.getCleanedContent() != null ? m.getCleanedContent() : "";
            StringBuilder line = new StringBuilder();
            line.append("[").append(time).append("] ").append(m.getSpeaker()).append("：").append(content);
            int j = i + 1;
            while (j < messages.size()) {
                Message next = messages.get(j);
                long gap = Duration.between(messages.get(j - 1).getMessageTime(), next.getMessageTime()).getSeconds();
                if (gap < mergeSeconds && next.getSpeaker().equals(m.getSpeaker())) {
                    line.append(" ").append(next.getCleanedContent() != null ? next.getCleanedContent() : "");
                    j++;
                } else break;
            }
            sb.append(line).append("\n");
            i = j;
        }
        return sb.toString();
    }

    static String classifySession(List<Message> messages) {
        // 会话类型是可解释的规则标签：冲突词优先，其次是情绪表达和超长会话，最后归为 GENERAL。
        if (messages.isEmpty()) return ConversationSession.TYPE_GENERAL;
        StringBuilder allText = new StringBuilder();
        for (Message m : messages)
            if (m.getCleanedContent() != null) allText.append(m.getCleanedContent());
        String text = allText.toString();
        for (String kw : CONFLICT_KEYWORDS)
            if (text.contains(kw)) return ConversationSession.TYPE_CONFLICT;
        int emoCount = 0;
        for (String kw : EMOTIONAL_KEYWORDS) {
            int idx = 0;
            while ((idx = text.indexOf(kw, idx)) != -1) {
                emoCount++;
                idx += kw.length();
            }
        }
        if (emoCount >= 3) return ConversationSession.TYPE_EMOTIONAL;
        if (messages.size() > 80) return ConversationSession.TYPE_EMOTIONAL;
        return ConversationSession.TYPE_GENERAL;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class SessionAccumulator {
        final List<Message> messages = new ArrayList<>();

        SessionAccumulator(Message first) {
            messages.add(first);
        }

        SessionWithMessages finalize(boolean forcedSplit, int mergeSeconds, ZoneId zoneId) {
            ConversationSession session = new ConversationSession();
            session.setId(UUID.randomUUID().toString());
            session.setStartTime(messages.get(0).getMessageTime());
            session.setEndTime(messages.get(messages.size() - 1).getMessageTime());
            session.setMessageCount(messages.size());
            session.setDurationSeconds((int) Duration.between(
                    messages.get(0).getMessageTime(), messages.get(messages.size() - 1).getMessageTime()).getSeconds());
            session.setForcedSplit(forcedSplit);
            session.setCreatedAt(Instant.now());
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (Message m : messages) stats.merge(m.getSpeaker(), 1, Integer::sum);
            try {
                session.setSpeakerStats(MAPPER.writeValueAsString(stats));
            } catch (JsonProcessingException e) {
                session.setSpeakerStats("{}");
            }
            session.setFormattedText(ConversationSessionBuilder.buildFormattedText(zoneId, messages, mergeSeconds, null));
            session.setSessionType(ConversationSessionBuilder.classifySession(messages));
            return new SessionWithMessages(session, new ArrayList<>(messages));
        }
    }
}
