package com.example.relationshipagent.analysis.evidence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceBudgeterTest {

    @Test
    void shouldDropWholeReferencesAndExposeTruncation() {
        EvidencePacket focus = packet("FOCUS", "FOCUS_QUESTION", List.of(ref("MSG-000001", "m1", "12345")));
        EvidencePacket global = packet("GLOBAL", "GLOBAL_OVERVIEW", List.of(ref("MSG-000002", "m2", "67890")));

        List<EvidencePacket> result = new EvidenceBudgeter().budget(List.of(global, focus), 1, 10);

        assertThat(result).filteredOn(packet -> packet.packetId().equals("FOCUS")).singleElement().satisfies(packet ->
                assertThat(packet.supportCandidates()).hasSize(1));
        assertThat(result).filteredOn(packet -> packet.packetId().equals("GLOBAL")).singleElement().satisfies(packet ->
                assertThat(packet.supportCandidates()).isEmpty());
        assertThat(result.get(0).coverage().truncated()).isTrue();
        assertThat(result.get(0).coverage().omittedPacketIds()).contains("GLOBAL");
    }

    private static EvidencePacket packet(String id, String type, List<EvidenceRef> support) {
        return new EvidencePacket(id, type, id, Instant.EPOCH, Instant.EPOCH, Map.of(), support, List.of(),
                new CoverageNote(0, 0, false, List.of(), 0, false, false), List.of());
    }

    private static EvidenceRef ref(String id, String messageId, String text) {
        return new EvidenceRef(id, EvidenceKind.MESSAGE, EvidenceRole.SUPPORT, messageId, null, null, null,
                Instant.EPOCH, "me", text, null, null, "test");
    }
}
