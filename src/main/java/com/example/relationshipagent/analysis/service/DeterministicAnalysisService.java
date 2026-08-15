package com.example.relationshipagent.analysis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.detector.AnalysisContext;
import com.example.relationshipagent.analysis.detector.AnalysisContextFactory;
import com.example.relationshipagent.analysis.detector.EventCandidate;
import com.example.relationshipagent.analysis.detector.EvidenceSeed;
import com.example.relationshipagent.analysis.detector.RelationshipStageDetector;
import com.example.relationshipagent.analysis.detector.RuleBasedRelationshipEventService;
import com.example.relationshipagent.analysis.detector.StageCandidate;
import com.example.relationshipagent.analysis.feature.AnalysisSnapshot;
import com.example.relationshipagent.analysis.feature.AnalysisSnapshotService;
import com.example.relationshipagent.analysis.model.RelationshipEvent;
import com.example.relationshipagent.analysis.model.RelationshipEventEvidence;
import com.example.relationshipagent.analysis.model.RelationshipStage;
import com.example.relationshipagent.analysis.repository.RelationshipEventEvidenceRepository;
import com.example.relationshipagent.analysis.repository.RelationshipEventRepository;
import com.example.relationshipagent.analysis.repository.RelationshipStageRepository;
import com.example.relationshipagent.config.RelationshipAgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists only deterministic M3 artifacts. Report generation is intentionally a later milestone.
 */
@Service
public class DeterministicAnalysisService {

    private final AnalysisSnapshotService snapshotService;
    private final AnalysisContextFactory contextFactory;
    private final RelationshipStageDetector stageDetector;
    private final RuleBasedRelationshipEventService eventService;
    private final RelationshipStageRepository stageRepository;
    private final RelationshipEventRepository eventRepository;
    private final RelationshipEventEvidenceRepository eventEvidenceRepository;
    private final RelationshipAgentProperties properties;
    private final ObjectMapper objectMapper;

    public DeterministicAnalysisService(AnalysisSnapshotService snapshotService, AnalysisContextFactory contextFactory,
                                        RelationshipStageDetector stageDetector, RuleBasedRelationshipEventService eventService,
                                        RelationshipStageRepository stageRepository, RelationshipEventRepository eventRepository,
                                        RelationshipEventEvidenceRepository eventEvidenceRepository,
                                        RelationshipAgentProperties properties, ObjectMapper objectMapper) {
        this.snapshotService = snapshotService;
        this.contextFactory = contextFactory;
        this.stageDetector = stageDetector;
        this.eventService = eventService;
        this.stageRepository = stageRepository;
        this.eventRepository = eventRepository;
        this.eventEvidenceRepository = eventEvidenceRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DeterministicAnalysisResult generate(String chatFileId) {
        // 先固定当前数据快照，再运行规则检测；所有产物带同一 inputHash，重跑时可精确幂等和废弃旧版本。
        AnalysisSnapshot snapshot = snapshotService.create(chatFileId);
        String inputHash = inputHash(snapshot);
        AnalysisContext context = contextFactory.create(chatFileId);
        List<StageCandidate> stages = stageDetector.detect(context.features());
        List<EventCandidate> events = eventService.detect(context);
        supersedePreviousRuleCandidates(chatFileId, inputHash);
        int stagesCreated = persistStages(chatFileId, inputHash, stages);
        int eventsCreated = persistEvents(chatFileId, inputHash, events);
        return new DeterministicAnalysisResult(snapshot, inputHash, stages.size(), events.size(), stagesCreated, eventsCreated);
    }

    private int persistStages(String chatFileId, String inputHash, List<StageCandidate> candidates) {
        // 规则候选只追加当前快照且避免重复，不覆盖人工审核状态；旧快照由 supersede 标记。
        int created = 0;
        for (StageCandidate candidate : candidates) {
            boolean exists = stageRepository.exists(new LambdaQueryWrapper<RelationshipStage>()
                    .eq(RelationshipStage::getChatFileId, chatFileId).eq(RelationshipStage::getStageKey, candidate.stageKey())
                    .eq(RelationshipStage::getInputHash, inputHash));
            if (exists) continue;
            RelationshipStage stage = new RelationshipStage();
            stage.setId(UUID.randomUUID().toString());
            stage.setChatFileId(chatFileId);
            stage.setStageKey(candidate.stageKey());
            stage.setStageType(candidate.stageType());
            stage.setStartTime(candidate.startTime());
            stage.setEndTime(candidate.endTime());
            stage.setMetricsJson(json(candidate.metrics()));
            stage.setSummary(candidate.summary());
            stage.setConfidence(BigDecimal.valueOf(candidate.confidence()));
            stage.setSource("RULE");
            stage.setReviewStatus("PENDING");
            stage.setDetectorVersion(RelationshipStageDetector.VERSION);
            stage.setInputHash(inputHash);
            stage.setCreatedAt(Instant.now());
            stageRepository.insertWithJsonb(stage);
            created++;
        }
        return created;
    }

    private int persistEvents(String chatFileId, String inputHash, List<EventCandidate> candidates) {
        // 事件和证据引用在同一事务中落库，事件本身不保存原始长文本，只保存可回读的 message/session ID。
        int created = 0;
        for (EventCandidate candidate : candidates) {
            boolean exists = eventRepository.exists(new LambdaQueryWrapper<RelationshipEvent>()
                    .eq(RelationshipEvent::getChatFileId, chatFileId).eq(RelationshipEvent::getEventKey, candidate.eventKey())
                    .eq(RelationshipEvent::getInputHash, inputHash));
            if (exists) continue;
            Instant now = Instant.now();
            RelationshipEvent event = new RelationshipEvent();
            event.setId(UUID.randomUUID().toString());
            event.setChatFileId(chatFileId);
            event.setEventType(candidate.eventType());
            event.setStartTime(candidate.startTime());
            event.setEndTime(candidate.endTime());
            event.setStatement(candidate.statement());
            event.setEvidence("deterministic refs: " + candidate.evidence().size());
            event.setConfidence(BigDecimal.valueOf(candidate.confidence()));
            event.setSource("RULE");
            event.setReviewStatus("PENDING");
            event.setCreatedAt(now);
            event.setEventKey(candidate.eventKey());
            event.setDetectorVersion(RuleBasedRelationshipEventService.VERSION);
            event.setInputHash(inputHash);
            event.setMetricsJson(json(candidate.metrics()));
            event.setUpdatedAt(now);
            eventRepository.insertWithJsonb(event);
            persistEvidence(event.getId(), candidate.evidence());
            created++;
        }
        return created;
    }

    private void persistEvidence(String eventId, List<EvidenceSeed> seeds) {
        int ordinal = 0;
        for (EvidenceSeed seed : seeds) {
            RelationshipEventEvidence evidence = new RelationshipEventEvidence();
            evidence.setId(UUID.randomUUID().toString());
            evidence.setEventId(eventId);
            evidence.setEvidenceRole(seed.role());
            evidence.setMessageId(seed.messageId());
            evidence.setSessionId(seed.sessionId());
            evidence.setStatisticPath(seed.statisticPath());
            evidence.setOrdinal(ordinal++);
            eventEvidenceRepository.insert(evidence);
        }
    }

    private String inputHash(AnalysisSnapshot snapshot) {
        String canonical = String.join("\n", snapshot.chatFileId(), snapshot.sourceSha256(), String.valueOf(snapshot.firstMessageTime()),
                String.valueOf(snapshot.lastMessageTime()), String.valueOf(snapshot.messageCount()), String.valueOf(snapshot.sessionCount()),
                String.valueOf(snapshot.chunkCount()), snapshot.statisticsHash(), snapshot.chunkVersion(), snapshot.embeddingModel(),
                // Deterministic candidates are independent of the LLM/prompt.  Including provider or
                // prompt versions would create duplicate rule rows on every prompt iteration.
                snapshot.analysisVersion(), RelationshipStageDetector.VERSION, RuleBasedRelationshipEventService.VERSION);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize deterministic analysis metrics", e);
        }
    }

    private void supersedePreviousRuleCandidates(String chatFileId, String currentInputHash) {
        stageRepository.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RelationshipStage>()
                .eq(RelationshipStage::getChatFileId, chatFileId).eq(RelationshipStage::getSource, "RULE")
                .ne(RelationshipStage::getInputHash, currentInputHash).set(RelationshipStage::getReviewStatus, "SUPERSEDED"));
        eventRepository.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RelationshipEvent>()
                .eq(RelationshipEvent::getChatFileId, chatFileId).eq(RelationshipEvent::getSource, "RULE")
                .ne(RelationshipEvent::getInputHash, currentInputHash).set(RelationshipEvent::getReviewStatus, "SUPERSEDED")
                .set(RelationshipEvent::getUpdatedAt, Instant.now()));
    }

    public record DeterministicAnalysisResult(AnalysisSnapshot snapshot, String inputHash,
                                              int stageCandidates, int eventCandidates,
                                              int stagesCreated, int eventsCreated) {
    }
}
