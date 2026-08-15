package com.example.relationshipagent.analysis.evidence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRefTest {

    @Test
    void shouldUseChunkIdentityWhenChunkAlsoCarriesParentSession() {
        EvidenceRef ref = new EvidenceRef(null, EvidenceKind.CHUNK, EvidenceRole.SUPPORT,
                null, "session-1", "chunk-1", null, null, null,
                "text", null, null, "hybrid-event-support");

        assertThat(ref.sourceKey()).isEqualTo("CHK:chunk-1");
    }
}
