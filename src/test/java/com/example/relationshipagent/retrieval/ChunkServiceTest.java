package com.example.relationshipagent.retrieval;

import com.example.relationshipagent.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkServiceTest {

    @Test
    void restoresSessionMessageOrderAfterUnorderedInQuery() {
        Message first = message("m1");
        Message second = message("m2");
        Message third = message("m3");

        List<Message> ordered = ChunkService.orderMessages(
                List.of("m1", "m2", "m3"), List.of(third, first, second));

        assertThat(ordered).extracting(Message::getId).containsExactly("m1", "m2", "m3");
    }

    private Message message(String id) {
        Message message = new Message();
        message.setId(id);
        return message;
    }
}
