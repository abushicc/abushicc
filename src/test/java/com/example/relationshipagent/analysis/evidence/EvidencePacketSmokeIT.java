package com.example.relationshipagent.analysis.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.model.RelationshipEvent;
import com.example.relationshipagent.analysis.repository.RelationshipEventRepository;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit read-only check against the current M3 candidate snapshot. */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "analysis.evidence.smoke", matches = "true")
class EvidencePacketSmokeIT {

    @Autowired private ChatFileRepository chatFileRepository;
    @Autowired private RelationshipEventRepository eventRepository;
    @Autowired private EvidencePacketBuilder evidencePacketBuilder;

    @Test
    void shouldBuildStableBudgetedPacketsFromCurrentCandidates() {
        ChatFile chatFile = chatFileRepository.selectOne(new LambdaQueryWrapper<ChatFile>()
                .eq(ChatFile::getStatus, ChatFile.STATUS_READY).last("LIMIT 1"));
        assertThat(chatFile).isNotNull();
        RelationshipEvent event = eventRepository.selectOne(new LambdaQueryWrapper<RelationshipEvent>()
                .eq(RelationshipEvent::getChatFileId, chatFile.getId()).eq(RelationshipEvent::getReviewStatus, "PENDING")
                .orderByDesc(RelationshipEvent::getCreatedAt).last("LIMIT 1"));
        assertThat(event).as("M3 PENDING event candidates are required").isNotNull();

        List<EvidencePacket> first = evidencePacketBuilder.build(chatFile.getId(), event.getInputHash(), "请分析联系减少的证据");
        List<EvidencePacket> second = evidencePacketBuilder.build(chatFile.getId(), event.getInputHash(), "请分析联系减少的证据");
        List<EvidenceRef> refs = first.stream().flatMap(packet -> java.util.stream.Stream.concat(
                packet.supportCandidates().stream(), packet.counterCandidates().stream())).toList();

        assertThat(first).extracting(EvidencePacket::packetType).contains("GLOBAL_OVERVIEW", "TERMINAL", "COMMUNICATION_STYLE", "FOCUS_QUESTION");
        assertThat(refs).isNotEmpty();
        assertThat(refs).allSatisfy(ref -> assertThat(ref.evidenceRefId()).matches("(MES|SES|STA|CHU)-\\d{6}"));
        assertThat(first).isEqualTo(second);
        System.out.printf("Evidence packet smoke test passed: packets=%d, refs=%d%n", first.size(), refs.size());
    }
}
