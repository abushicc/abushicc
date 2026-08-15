package com.example.relationshipagent.analysis.feature;

import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.session.ConversationSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicFeatureServiceTest {

    private final DeterministicFeatureService service =
            new DeterministicFeatureService(null, null, null, null);

    @Test
    void shouldFillZeroMessageMonthsUsingSourceTimezone() {
        Message january = message("m1", "me", "2025-01-31T16:30:00Z"); // Feb 1 in Shanghai
        Message march = message("m2", "other", "2025-03-01T01:00:00Z");
        ConversationSession first = session("s1", january.getMessageTime(), january.getMessageTime(), 1);
        ConversationSession second = session("s2", march.getMessageTime(), march.getMessageTime(), 1);

        RelationshipFeatureSet features = service.compute(ZoneId.of("Asia/Shanghai"), List.of(january, march),
                List.of(first, second), Map.of("s1", List.of(january), "s2", List.of(march)));

        assertThat(features.monthly()).extracting(RelationshipFeatureSet.MonthlyFeature::month)
                .containsExactly("2025-02", "2025-03");
        assertThat(features.monthly().get(0).messageCount()).isEqualTo(1);
        assertThat(features.monthly().get(1).messageCount()).isEqualTo(1);
    }

    @Test
    void shouldUseNullReplyQuantilesWhenThereAreNoSpeakerSwitches() {
        Message first = message("m1", "me", "2025-01-01T00:00:00Z");
        Message second = message("m2", "me", "2025-01-01T00:01:00Z");
        ConversationSession session = session("s1", first.getMessageTime(), second.getMessageTime(), 2);

        RelationshipFeatureSet features = service.compute(ZoneId.of("UTC"), List.of(first, second), List.of(session),
                Map.of("s1", List.of(first, second)));

        RelationshipFeatureSet.ReplyMetric metric = features.monthly().get(0).replyBySpeaker().get("me");
        assertThat(metric.sampleCount()).isZero();
        assertThat(metric.p50Seconds()).isNull();
        assertThat(metric.p90Seconds()).isNull();
    }

    @Test
    void shouldCalculateReplyDelayForTheRespondingSpeakerOnly() {
        Message first = message("m1", "me", "2025-01-01T00:00:00Z");
        Message second = message("m2", "other", "2025-01-01T00:02:00Z");
        Message third = message("m3", "me", "2025-01-01T00:05:00Z");
        ConversationSession session = session("s1", first.getMessageTime(), third.getMessageTime(), 3);

        RelationshipFeatureSet features = service.compute(ZoneId.of("UTC"), List.of(first, second, third), List.of(session),
                Map.of("s1", List.of(first, second, third)));

        assertThat(features.monthly().get(0).replyBySpeaker().get("other").p50Seconds()).isEqualTo(120d);
        assertThat(features.monthly().get(0).replyBySpeaker().get("me").p50Seconds()).isEqualTo(180d);
    }

    private static Message message(String id, String speaker, String time) {
        Message message = new Message();
        message.setId(id);
        message.setSpeaker(speaker);
        message.setMessageTime(Instant.parse(time));
        message.setMessageType(Message.TYPE_TEXT);
        return message;
    }

    private static ConversationSession session(String id, Instant start, Instant end, int count) {
        ConversationSession session = new ConversationSession();
        session.setId(id);
        session.setStartTime(start);
        session.setEndTime(end);
        session.setDurationSeconds((int) (end.getEpochSecond() - start.getEpochSecond()));
        session.setMessageCount(count);
        return session;
    }
}
