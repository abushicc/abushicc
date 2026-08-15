package com.example.relationshipagent.analysis.detector;

import com.example.relationshipagent.analysis.feature.RelationshipFeatureSet;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.session.ConversationSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRelationshipEventServiceTest {

    private final RuleBasedRelationshipEventService service = new RuleBasedRelationshipEventService();

    @Test
    void shouldNotTreatSingleSupportingConflictCueAsAnEvent() {
        Message first = message("m1", "me", "我有点失望", "2025-01-01T00:00:00Z");
        Message second = message("m2", "other", "我听到了", "2025-01-01T00:01:00Z");
        Message third = message("m3", "me", "我们慢慢说", "2025-01-01T00:02:00Z");
        ConversationSession session = session("s1", first, third);

        List<EventCandidate> events = service.detect(context(List.of(first, second, third), List.of(session),
                Map.of("s1", List.of(first, second, third))));

        assertThat(events).noneMatch(event -> event.eventType().equals("CONFLICT"));
    }

    @Test
    void shouldRequireSubsequentOtherSpeakerReplyForRepairCandidate() {
        Message conflict = message("m1", "other", "我很失望", "2025-01-01T00:00:00Z");
        Message apology = message("m2", "me", "对不起，是我不好", "2025-01-01T00:01:00Z");
        Message reply = message("m3", "other", "没事，我们再聊", "2025-01-01T00:02:00Z");
        ConversationSession session = session("s1", conflict, reply);

        List<EventCandidate> events = service.detect(context(List.of(conflict, apology, reply), List.of(session),
                Map.of("s1", List.of(conflict, apology, reply))));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("REPAIR");
            assertThat(event.confidence()).isEqualTo(.50d);
        });
    }

    private static AnalysisContext context(List<Message> messages, List<ConversationSession> sessions,
                                           Map<String, List<Message>> bySession) {
        RelationshipFeatureSet features = new RelationshipFeatureSet("relationship-features-v1", "UTC",
                new RelationshipFeatureSet.Coverage(messages.get(0).getMessageTime(),
                        messages.get(messages.size() - 1).getMessageTime(), messages.size(), sessions.size(), Map.of(), 0, 0),
                List.of(), new RelationshipFeatureSet.TerminalFeature("LAST_SESSION_FALLBACK",
                messages.get(messages.size() - 1).getMessageTime(), sessions.get(0).getId(), "me", "other", false,
                List.of(), List.of()));
        return new AnalysisContext(features, messages, sessions, bySession);
    }

    private static Message message(String id, String speaker, String content, String time) {
        Message message = new Message();
        message.setId(id);
        message.setSpeaker(speaker);
        message.setCleanedContent(content);
        message.setMessageType(Message.TYPE_TEXT);
        message.setMessageTime(Instant.parse(time));
        return message;
    }

    private static ConversationSession session(String id, Message first, Message last) {
        ConversationSession session = new ConversationSession();
        session.setId(id);
        session.setStartTime(first.getMessageTime());
        session.setEndTime(last.getMessageTime());
        return session;
    }
}
