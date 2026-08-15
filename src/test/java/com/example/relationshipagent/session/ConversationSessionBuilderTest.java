package com.example.relationshipagent.session;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSessionBuilderTest {

    private ConversationSessionBuilder builder;
    private Instant baseTime = Instant.parse("2021-01-07T08:00:00Z");

    @BeforeEach
    void setUp() {
        RelationshipAgentProperties props = new RelationshipAgentProperties(
                new RelationshipAgentProperties.Session(5, 200, 60),
                new RelationshipAgentProperties.Chunk(45, 8),
                new RelationshipAgentProperties.Job(1000, 3, 1800000),
                new RelationshipAgentProperties.Retrieval(5, 3),
                new RelationshipAgentProperties.Embedding("text-embedding-v3", "dashscope", 1024, 25, 3, 2000),
                new RelationshipAgentProperties.Statistics(java.util.List.of()));
        builder = new ConversationSessionBuilder(props);
    }

    @Test
    @DisplayName("密集聊天 → 归为同一会话")
    void shouldGroupDenseMessagesIntoSingleSession() {
        List<Message> messages = createMessages(
                msg("kiwi", "Hi", 0), msg("耳朵小", "Hello", 60),
                msg("kiwi", "你好吗？", 120), msg("耳朵小", "很好", 180));
        List<ConversationSession> sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getMessageCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("间隔超过阈值 → 切为新会话")
    void shouldSplitOnLongGap() {
        List<Message> messages = createMessages(
                msg("kiwi", "Message 1", 0), msg("耳朵小", "Message 2", 60),
                msg("kiwi", "Much later", 600), msg("耳朵小", "Reply", 660));
        List<ConversationSession> sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getMessageCount()).isEqualTo(2);
        assertThat(sessions.get(1).getMessageCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("formatted_text 格式正确")
    void shouldProduceCorrectFormattedText() {
        List<Message> messages = createMessages(
                msg("kiwi", "你好", 0), msg("耳朵小", "你好呀", 30));
        List<ConversationSession> sessions = builder.build(messages, "Asia/Shanghai");
        String text = sessions.get(0).getFormattedText();
        assertThat(text).contains("时间：").contains("参与人：").contains("消息数：")
                .contains("[").contains("kiwi：").contains("耳朵小：");
    }

    @Test
    @DisplayName("speaker_stats 正确统计（JSON 字符串）")
    void shouldComputeSpeakerStats() {
        List<Message> messages = createMessages(
                msg("kiwi", "A", 0), msg("kiwi", "B", 10), msg("耳朵小", "C", 20));
        List<ConversationSession> sessions = builder.build(messages, "Asia/Shanghai");
        String stats = sessions.get(0).getSpeakerStats();
        assertThat(stats).contains("\"kiwi\":2").contains("\"耳朵小\":1");
    }

    @Test
    @DisplayName("非首个会话包含冲突关键词 → CONFLICT")
    void shouldDetectConflictSession() {
        List<Message> messages = createMessages(
                msg("kiwi", "你好", 0), msg("耳朵小", "你好呀", 30),
                msg("kiwi", "随便吧", 6000), msg("耳朵小", "算了不说了", 6030));
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(1).getSessionType()).isEqualTo(ConversationSession.TYPE_CONFLICT);
    }

    @Test
    @DisplayName("非首个普通会话 → GENERAL(首个会被标 FIRST_MEET)")
    void shouldClassifyGeneralSession() {
        // 间隔超过阈值切为两段;首段 GENERAL→FIRST_MEET,第二段非首个保持 GENERAL
        List<Message> messages = createMessages(
                msg("kiwi", "今天天气不错", 0), msg("耳朵小", "是啊挺好的", 60),
                msg("kiwi", "后来又聊", 6000), msg("耳朵小", "嗯", 6060));
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getSessionType()).isEqualTo(ConversationSession.TYPE_FIRST_MEET);
        assertThat(sessions.get(1).getSessionType()).isEqualTo(ConversationSession.TYPE_GENERAL);
    }

    @Test
    @DisplayName("首个会话 → FIRST_MEET（设计文档 7.4.2）")
    void shouldMarkFirstSessionAsFirstMeet() {
        List<Message> messages = createMessages(
                msg("kiwi", "今天天气不错", 0), msg("耳朵小", "是啊挺好的", 60));
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSessionType()).isEqualTo(ConversationSession.TYPE_FIRST_MEET);
    }

    @Test
    @DisplayName("超长首会话仍优先标记为 FIRST_MEET")
    void shouldPrioritizeFirstMeetOverEmotionalLengthHeuristic() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 81; i++) {
            messages.add(createMessages(msg(i % 2 == 0 ? "kiwi" : "耳朵小", "普通消息" + i, i * 10L)).get(0));
        }

        var sessions = builder.build(messages, "Asia/Shanghai");

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getSessionType()).isEqualTo(ConversationSession.TYPE_FIRST_MEET);
    }

    @Test
    @DisplayName("超过 200 条上限 → forced_split=true")
    void shouldForceSplitAtMaxMessages() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            Message m = new Message();
            m.setId(UUID.randomUUID().toString());
            m.setSpeaker("kiwi");
            m.setCleanedContent("m" + i);
            m.setContent("m" + i);
            m.setMessageTime(baseTime.plus(i * 10, ChronoUnit.SECONDS)); // 10s 间隔,不触发 gap 切分
            m.setMessageType("TEXT");
            messages.add(m);
        }
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getForcedSplit()).isTrue();
        assertThat(sessions.get(0).getMessageCount()).isEqualTo(200);
        assertThat(sessions.get(1).getForcedSplit()).isFalse();
    }

    @Test
    @DisplayName("单条消息 → 独立短会话")
    void shouldCreateSingleMessageSession() {
        List<Message> messages = createMessages(
                msg("kiwi", "深夜消息", 0), msg("耳朵小", "第二天回复", 36000));
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getMessageCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("duration_seconds 正确计算")
    void shouldComputeDuration() {
        List<Message> messages = createMessages(
                msg("kiwi", "开始", 0), msg("耳朵小", "结束", 120));
        var sessions = builder.build(messages, "Asia/Shanghai");
        assertThat(sessions.get(0).getDurationSeconds()).isEqualTo(120);
    }

    private List<Message> createMessages(CreateMessageArg... args) {
        List<Message> messages = new ArrayList<>();
        for (CreateMessageArg arg : args) {
            Message m = new Message();
            m.setId(UUID.randomUUID().toString());
            m.setSpeaker(arg.speaker);
            m.setCleanedContent(arg.content);
            m.setContent(arg.content);
            m.setMessageTime(baseTime.plus(arg.offsetSeconds, ChronoUnit.SECONDS));
            m.setMessageType("TEXT");
            messages.add(m);
        }
        return messages;
    }

    private CreateMessageArg msg(String speaker, String content, long offsetSeconds) {
        return new CreateMessageArg(speaker, content, offsetSeconds);
    }

    private record CreateMessageArg(String speaker, String content, long offsetSeconds) {}
}
