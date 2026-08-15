package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit real-data check. It creates only V9 derived candidates and never calls an LLM. */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "analysis.deterministic.smoke", matches = "true")
class DeterministicAnalysisSmokeIT {

    @Autowired
    private ChatFileRepository chatFileRepository;

    @Autowired
    private DeterministicAnalysisService deterministicAnalysisService;

    @Test
    void shouldGenerateIdempotentDeterministicArtifactsForReadyChatFile() {
        ChatFile chatFile = chatFileRepository.selectOne(new LambdaQueryWrapper<ChatFile>()
                .eq(ChatFile::getStatus, ChatFile.STATUS_READY).last("LIMIT 1"));
        assertThat(chatFile).as("a READY chat file is required for this opt-in smoke test").isNotNull();

        DeterministicAnalysisService.DeterministicAnalysisResult first =
                deterministicAnalysisService.generate(chatFile.getId());
        DeterministicAnalysisService.DeterministicAnalysisResult second =
                deterministicAnalysisService.generate(chatFile.getId());

        assertThat(first.inputHash()).isEqualTo(second.inputHash());
        assertThat(first.stagesCreated()).isLessThanOrEqualTo(first.stageCandidates());
        assertThat(first.eventsCreated()).isLessThanOrEqualTo(first.eventCandidates());
        assertThat(second.stagesCreated()).isZero();
        assertThat(second.eventsCreated()).isZero();
        System.out.printf("Deterministic analysis smoke test passed: stageCandidates=%d, eventCandidates=%d, hashPrefix=%s%n",
                first.stageCandidates(), first.eventCandidates(), first.inputHash().substring(0, 12));
    }
}
