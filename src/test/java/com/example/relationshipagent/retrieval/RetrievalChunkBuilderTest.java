package com.example.relationshipagent.retrieval;

import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.session.ConversationSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalChunkBuilderTest {

    private RetrievalChunkBuilder builder;
    private ConversationSession session;
    private ZoneId zoneId = ZoneId.of("Asia/Shanghai");
    private int mergeSeconds = 60;

    @BeforeEach
    void setUp() {
        RelationshipAgentProperties props = new RelationshipAgentProperties(
                new RelationshipAgentProperties.Session(45, 200, 60),
                new RelationshipAgentProperties.Chunk(45, 8),
                new RelationshipAgentProperties.Job(1000, 3, 1800000),
                new RelationshipAgentProperties.Retrieval(5, 3),
                new RelationshipAgentProperties.Embedding("text-embedding-v3", "dashscope", 1024, 25, 3, 2000),
                new RelationshipAgentProperties.Statistics(java.util.List.of()));
        builder = new RetrievalChunkBuilder(props);

        session = new ConversationSession();
        session.setId(UUID.randomUUID().toString());
        session.setChatFileId(UUID.randomUUID().toString());
        session.setStartTime(Instant.parse("2021-01-07T08:00:00Z"));
        session.setEndTime(Instant.parse("2021-01-07T10:00:00Z"));
    }

    private List<Message> createMessages(int count) {
        List<Message> msgs = new ArrayList<>();
        Instant base = Instant.parse("2021-01-07T08:00:00Z");
        for (int i = 0; i < count; i++) {
            Message m = new Message();
            m.setId(UUID.randomUUID().toString());
            m.setSpeaker("kiwi");
            m.setCleanedContent("msg " + (i + 1));
            m.setMessageTime(base.plus(i * 30L, ChronoUnit.SECONDS));
            m.setMessageType("TEXT");
            msgs.add(m);
        }
        return msgs;
    }

    @Test
    @DisplayName("30 messages -> 1 chunk, no partial info suffix")
    void shouldReturnSingleChunkForSmallSession() {
        List<Message> msgs = createMessages(30);
        List<RetrievalChunk> chunks = builder.build(session, msgs, zoneId, mergeSeconds);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getSequenceNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly 45 messages -> 1 chunk")
    void shouldReturnSingleChunkAtTargetBoundary() {
        List<Message> msgs = createMessages(45);
        List<RetrievalChunk> chunks = builder.build(session, msgs, zoneId, mergeSeconds);
        assertThat(chunks).hasSize(1);
    }

    @Test
    @DisplayName("46 messages -> 2 chunks, tail window = last 45")
    void shouldReturnTwoChunksForSlightlyOverTarget() {
        List<Message> msgs = createMessages(46);
        List<RetrievalChunk> chunks = builder.build(session, msgs, zoneId, mergeSeconds);
        assertThat(chunks).hasSize(2);
    }

    @Test
    @DisplayName("200 messages -> 6 chunks, overlap = 8")
    void shouldSplitLargeSessionWithCorrectOverlap() {
        List<Message> msgs = createMessages(200);
        List<RetrievalChunk> chunks = builder.build(session, msgs, zoneId, mergeSeconds);
        assertThat(chunks).hasSize(6);
    }

    @Test
    @DisplayName("same input -> stable text_hash")
    void shouldProduceStableTextHash() {
        List<Message> msgs = createMessages(10);
        List<RetrievalChunk> c1 = builder.build(session, msgs, zoneId, mergeSeconds);
        List<RetrievalChunk> c2 = builder.build(session, msgs, zoneId, mergeSeconds);
        assertThat(c1.get(0).getTextHash()).isEqualTo(c2.get(0).getTextHash());
    }
}
