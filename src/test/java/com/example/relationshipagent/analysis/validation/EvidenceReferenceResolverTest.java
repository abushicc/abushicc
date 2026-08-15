package com.example.relationshipagent.analysis.validation;
import com.example.relationshipagent.analysis.evidence.*;
import com.example.relationshipagent.message.*;
import com.example.relationshipagent.retrieval.*;
import com.example.relationshipagent.session.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EvidenceReferenceResolverTest {
    @Test void shouldRehydrateOnlySameChatFileMessage() {
        MessageRepository messages = mock(MessageRepository.class); Message message = new Message(); message.setId("m"); message.setChatFileId("cf"); message.setCleanedContent("数据库原文");
        when(messages.selectById("m")).thenReturn(message);
        EvidenceReferenceResolver resolver = new EvidenceReferenceResolver(messages, mock(ConversationSessionRepository.class), mock(RetrievalChunkRepository.class));
        EvidenceRef ref = new EvidenceRef("MES-1", EvidenceKind.MESSAGE, EvidenceRole.SUPPORT, "m", null, null, null, null, null, "模型不能决定的原文", null, null, "x");
        assertThat(resolver.resolve("cf", ref).quoteText()).isEqualTo("数据库原文");
        assertThat(resolver.resolve("other", ref)).isNull();
    }
}
