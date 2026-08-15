package com.example.relationshipagent.analysis.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.relationshipagent.analysis.detector.AnalysisContext;
import com.example.relationshipagent.analysis.detector.AnalysisContextFactory;
import com.example.relationshipagent.analysis.model.RelationshipEvent;
import com.example.relationshipagent.analysis.model.RelationshipEventEvidence;
import com.example.relationshipagent.analysis.model.RelationshipStage;
import com.example.relationshipagent.analysis.repository.RelationshipEventEvidenceRepository;
import com.example.relationshipagent.analysis.repository.RelationshipEventRepository;
import com.example.relationshipagent.analysis.repository.RelationshipStageRepository;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.rag.RetrievalService;
import com.example.relationshipagent.session.ConversationSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds stable, server-resolved packet evidence from the current M3 deterministic snapshot.
 */
@Service
public class EvidencePacketBuilder {

    private final AnalysisContextFactory contextFactory;
    private final RelationshipStageRepository stageRepository;
    private final RelationshipEventRepository eventRepository;
    private final RelationshipEventEvidenceRepository eventEvidenceRepository;
    private final RetrievalService retrievalService;
    private final boolean retrievalExpansionEnabled;
    private final EvidenceBudgeter budgeter = new EvidenceBudgeter();

    public EvidencePacketBuilder(AnalysisContextFactory contextFactory, RelationshipStageRepository stageRepository,
                                 RelationshipEventRepository eventRepository,
                                 RelationshipEventEvidenceRepository eventEvidenceRepository,
                                 RetrievalService retrievalService,
                                 @Value("${ra.analysis.evidence-retrieval-expansion-enabled:false}") boolean retrievalExpansionEnabled) {
        this.contextFactory = contextFactory;
        this.stageRepository = stageRepository;
        this.eventRepository = eventRepository;
        this.eventEvidenceRepository = eventEvidenceRepository;
        this.retrievalService = retrievalService;
        this.retrievalExpansionEnabled = retrievalExpansionEnabled;
    }

    public List<EvidencePacket> build(String chatFileId, String inputHash, String question) {
        // 证据包从当前分析快照和数据库引用实时解析，确保模型看到的内容与审计 ID 一一对应。
        AnalysisContext context = contextFactory.create(chatFileId);
        Map<String, Message> messages = context.messages().stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));
        Map<String, ConversationSession> sessions = context.sessions().stream()
                .collect(Collectors.toMap(ConversationSession::getId, Function.identity()));
        List<EvidencePacket> packets = new ArrayList<>();
        packets.add(globalPacket(context));
        packets.add(stylePacket(context));
        packets.add(terminalPacket(context));
        for (RelationshipStage stage : stageRepository.selectList(new LambdaQueryWrapper<RelationshipStage>()
                .eq(RelationshipStage::getChatFileId, chatFileId).eq(RelationshipStage::getInputHash, inputHash)
                .eq(RelationshipStage::getReviewStatus, "PENDING").orderByAsc(RelationshipStage::getStartTime))) {
            packets.add(stagePacket(stage, context));
        }
        List<RelationshipEvent> events = eventRepository.selectList(new LambdaQueryWrapper<RelationshipEvent>()
                .eq(RelationshipEvent::getChatFileId, chatFileId).eq(RelationshipEvent::getInputHash, inputHash)
                .eq(RelationshipEvent::getReviewStatus, "PENDING").ge(RelationshipEvent::getConfidence, .5)
                .orderByAsc(RelationshipEvent::getStartTime));
        Map<String, List<RelationshipEventEvidence>> evidenceByEvent = eventEvidenceRepository.selectList(
                        new LambdaQueryWrapper<RelationshipEventEvidence>().in(RelationshipEventEvidence::getEventId,
                                events.stream().map(RelationshipEvent::getId).toList())).stream()
                .collect(Collectors.groupingBy(RelationshipEventEvidence::getEventId, LinkedHashMap::new, Collectors.toList()));
        for (RelationshipEvent event : events) {
            packets.add(eventPacket(event, evidenceByEvent.getOrDefault(event.getId(), List.of()), messages, sessions, context));
        }
        if (question != null && !question.isBlank()) packets.add(focusPacket(question, context));
        return budgeter.budget(assignStableIds(packets), EvidenceBudgeter.DEFAULT_MAX_ITEMS, EvidenceBudgeter.DEFAULT_MAX_CHARS);
    }

    private EvidencePacket globalPacket(AnalysisContext context) {
        var coverage = context.features().coverage();
        List<EvidenceRef> refs = List.of(stat("coverage.messageCount", String.valueOf(coverage.messageCount()), EvidenceRole.SUPPORT),
                stat("coverage.sessionCount", String.valueOf(coverage.sessionCount()), EvidenceRole.SUPPORT),
                stat("coverage.timeRange", coverage.firstMessageTime() + " to " + coverage.lastMessageTime(), EvidenceRole.CONTEXT));
        return packet("GLOBAL-OVERVIEW", "GLOBAL_OVERVIEW", "global", coverage.firstMessageTime(), coverage.lastMessageTime(),
                Map.of("schemaVersion", context.features().schemaVersion()), refs, List.of(), false, List.of());
    }

    private EvidencePacket stylePacket(AnalysisContext context) {
        return packet("COMMUNICATION-STYLE", "COMMUNICATION_STYLE", "style", context.features().coverage().firstMessageTime(),
                context.features().coverage().lastMessageTime(), Map.of(),
                List.of(stat("styleFingerprint", "阶段 2 文体指纹统计可供引用。", EvidenceRole.SUPPORT)), List.of(), false,
                List.of("文体统计描述频率和形式，不等于人格结论。"));
    }

    private EvidencePacket terminalPacket(AnalysisContext context) {
        var terminal = context.features().terminal();
        List<EvidenceRef> support = new ArrayList<>();
        support.add(stat("terminal.windows", "末端 3/6/12 月窗口统计。", EvidenceRole.SUPPORT));
        for (var session : terminal.lastSessions().stream().skip(Math.max(0, terminal.lastSessions().size() - 3)).toList()) {
            support.add(sessionRef(session.sessionId(), session.startTime(), EvidenceRole.SUPPORT, "terminal-last-session"));
        }
        return packet("TERMINAL", "TERMINAL", "terminal", terminal.windows().isEmpty() ? terminal.anchorTime()
                        : terminal.windows().get(0).startTime(), terminal.anchorTime(), Map.of("anchorType", terminal.anchorType()), support,
                List.of(), true, List.of("最后一条消息或最后一次会话不能单独证明关系结束或归责。"));
    }

    private EvidencePacket stagePacket(RelationshipStage stage, AnalysisContext context) {
        List<ConversationSession> inStage = context.sessions().stream().filter(session -> overlaps(session, stage.getStartTime(), stage.getEndTime())).toList();
        List<EvidenceRef> support = new ArrayList<>(representativeSessionRefs(inStage, EvidenceRole.SUPPORT, "stage-boundary"));
        support.add(stat("stage." + stage.getStageKey() + ".metrics", "阶段规则指标。", EvidenceRole.SUPPORT));
        return packet("STAGE-" + stage.getStageKey(), "STAGE", stage.getStageKey(), stage.getStartTime(), stage.getEndTime(),
                Map.of("stageType", stage.getStageType(), "confidence", stage.getConfidence()), support, List.of(), false,
                List.of("阶段标签只描述可观察到的联系节奏。"));
    }

    private EvidencePacket eventPacket(RelationshipEvent event, List<RelationshipEventEvidence> seeds,
                                       Map<String, Message> messages, Map<String, ConversationSession> sessions,
                                       AnalysisContext context) {
        List<EvidenceRef> support = new ArrayList<>();
        List<EvidenceRef> counter = new ArrayList<>();
        for (RelationshipEventEvidence seed : seeds) {
            EvidenceRole role = EvidenceRole.valueOf(seed.getEvidenceRole());
            EvidenceRef ref = resolveSeed(seed, messages, sessions, role, "detector-direct");
            if (ref == null) continue;
            (role == EvidenceRole.COUNTER ? counter : support).add(ref);
        }
        if (counter.isEmpty()) {
            nearestDifferentSession(event, context).ifPresent(session -> counter.add(sessionRef(session.getId(), session.getStartTime(),
                    EvidenceRole.COUNTER, "time-neighbor-counter-search")));
        }
        if (retrievalExpansionEnabled) appendRetrievedEvidence(event, support, counter);
        return packet("EVENT-" + event.getEventKey(), "EVENT", event.getEventKey(), event.getStartTime(), event.getEndTime(),
                Map.of("eventType", event.getEventType(), "confidence", event.getConfidence(), "detectorVersion", event.getDetectorVersion()),
                support, counter, true, List.of("事件候选不解释动机；反证检索未命中不证明候选为真。"));
    }

    /**
     * Opt-in only: packet construction must not silently call the remote embedding provider.
     */
    private void appendRetrievedEvidence(RelationshipEvent event, List<EvidenceRef> support, List<EvidenceRef> counter) {
        // 扩展检索是显式开关，默认关闭，避免构建分析报告时意外触发远程 embedding 请求。
        RetrievalService.EvidenceRetrievalResponse direct = retrievalService.retrieveEvidence(event.getChatFileId(),
                new RetrievalService.EvidenceRetrievalRequest(eventQuery(event), null,
                        event.getStartTime().minus(Duration.ofDays(7)), event.getEndTime().plus(Duration.ofDays(7)),
                        null, 3, "score"));
        if (direct.answerable()) {
            direct.sessions().stream().flatMap(session -> session.chunks().stream()).limit(3)
                    .map(chunk -> chunkRef(chunk, EvidenceRole.SUPPORT, "hybrid-event-support"))
                    .forEach(support::add);
        }

        RetrievalService.EvidenceRetrievalResponse opposite = retrievalService.retrieveEvidence(event.getChatFileId(),
                new RetrievalService.EvidenceRetrievalRequest(counterQuery(event.getEventType()), null,
                        event.getEndTime().plus(Duration.ofSeconds(1)), event.getEndTime().plus(Duration.ofDays(30)),
                        null, 2, "score"));
        if (opposite.answerable()) {
            opposite.sessions().stream().flatMap(session -> session.chunks().stream()).limit(2)
                    .map(chunk -> chunkRef(chunk, EvidenceRole.COUNTER, "hybrid-counter-search"))
                    .forEach(counter::add);
        }
    }

    private static String eventQuery(RelationshipEvent event) {
        return event.getStatement() != null && !event.getStatement().isBlank() ? event.getStatement() : event.getEventType();
    }

    private static String counterQuery(String eventType) {
        return switch (eventType) {
            case "CONFLICT" -> "道歉 继续 回复 沟通";
            case "DISTANCING" -> "主动 联系 见面 回复";
            case "REPAIR" -> "再次 冲突 不满";
            default -> "联系 变化 回复";
        };
    }

    private EvidencePacket focusPacket(String question, AnalysisContext context) {
        return packet("FOCUS-QUESTION", "FOCUS_QUESTION", "user-question", context.features().coverage().firstMessageTime(),
                context.features().coverage().lastMessageTime(), Map.of("question", question),
                List.of(stat("focus.question", question, EvidenceRole.CONTEXT)), List.of(), false,
                List.of("用户问题不替代全局证据覆盖。"));
    }

    private EvidenceRef resolveSeed(RelationshipEventEvidence seed, Map<String, Message> messages,
                                    Map<String, ConversationSession> sessions, EvidenceRole role, String provenance) {
        if (seed.getMessageId() != null) {
            Message message = messages.get(seed.getMessageId());
            return message == null ? null : messageRef(message, role, provenance);
        }
        if (seed.getSessionId() != null) {
            ConversationSession session = sessions.get(seed.getSessionId());
            return session == null ? null : sessionRef(session.getId(), session.getStartTime(), role, provenance);
        }
        return seed.getStatisticPath() == null ? null : stat(seed.getStatisticPath(), "确定性统计引用。", role);
    }

    private List<EvidenceRef> representativeSessionRefs(List<ConversationSession> sessions, EvidenceRole role, String provenance) {
        if (sessions.isEmpty()) return List.of();
        List<EvidenceRef> result = new ArrayList<>();
        result.add(sessionRef(sessions.get(0).getId(), sessions.get(0).getStartTime(), role, provenance));
        if (sessions.size() > 1) {
            ConversationSession last = sessions.get(sessions.size() - 1);
            result.add(sessionRef(last.getId(), last.getStartTime(), role, provenance));
        }
        return result;
    }

    private EvidenceRef messageRef(Message message, EvidenceRole role, String provenance) {
        return new EvidenceRef(null, EvidenceKind.MESSAGE, role, message.getId(), null, null, null, message.getMessageTime(),
                message.getSpeaker(), message.getCleanedContent(), null, null, provenance);
    }

    private EvidenceRef sessionRef(String sessionId, Instant occurredAt, EvidenceRole role, String provenance) {
        return new EvidenceRef(null, EvidenceKind.SESSION, role, null, sessionId, null, null, occurredAt, null,
                "会话引用：" + sessionId, null, null, provenance);
    }

    private EvidenceRef chunkRef(RetrievalService.EvidenceChunk chunk, EvidenceRole role, String provenance) {
        return new EvidenceRef(null, EvidenceKind.CHUNK, role, null, chunk.sessionId(), chunk.chunkId(), null, null,
                null, chunk.retrievalText(), "messages " + chunk.startMessageId(), "messages " + chunk.endMessageId(),
                provenance + ":" + chunk.retrievalChannel());
    }

    private EvidenceRef stat(String path, String text, EvidenceRole role) {
        return new EvidenceRef(null, EvidenceKind.STATISTIC, role, null, null, null, path, null, null, text, null, null,
                "deterministic-statistic");
    }

    private EvidencePacket packet(String id, String type, String subject, Instant start, Instant end, Map<String, Object> metrics,
                                  List<EvidenceRef> support, List<EvidenceRef> counter, boolean searched, List<String> cautions) {
        return new EvidencePacket(id, type, subject, start, end, metrics, List.copyOf(support), List.copyOf(counter),
                new CoverageNote(0, 0, false, List.of(), 0, searched, !counter.isEmpty()), List.copyOf(cautions));
    }

    private List<EvidencePacket> assignStableIds(List<EvidencePacket> packets) {
        List<EvidenceRef> all = packets.stream().flatMap(packet -> java.util.stream.Stream.concat(packet.supportCandidates().stream(),
                packet.counterCandidates().stream())).collect(Collectors.toMap(EvidenceRef::sourceKey, Function.identity(), (left, ignored) -> left,
                LinkedHashMap::new)).values().stream().sorted(Comparator.comparing(EvidenceRef::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ref -> ref.kind().name()).thenComparing(EvidenceRef::sourceKey)).toList();
        Map<String, String> ids = new LinkedHashMap<>();
        Map<EvidenceKind, Integer> counters = new LinkedHashMap<>();
        for (EvidenceRef ref : all) {
            int number = counters.merge(ref.kind(), 1, Integer::sum);
            ids.put(ref.sourceKey(), ref.kind().name().substring(0, 3) + "-" + String.format("%06d", number));
        }
        return packets.stream().map(packet -> new EvidencePacket(packet.packetId(), packet.packetType(), packet.subjectKey(),
                packet.startTime(), packet.endTime(), packet.metrics(), withIds(packet.supportCandidates(), ids),
                withIds(packet.counterCandidates(), ids), packet.coverage(), packet.cautions())).toList();
    }

    private static List<EvidenceRef> withIds(List<EvidenceRef> refs, Map<String, String> ids) {
        return refs.stream().map(ref -> new EvidenceRef(ids.get(ref.sourceKey()), ref.kind(), ref.suggestedRole(), ref.messageId(),
                ref.sessionId(), ref.chunkId(), ref.statisticPath(), ref.occurredAt(), ref.speaker(), ref.text(), ref.contextBefore(),
                ref.contextAfter(), ref.provenance())).toList();
    }

    private static boolean overlaps(ConversationSession session, Instant start, Instant end) {
        return !session.getEndTime().isBefore(start) && !session.getStartTime().isAfter(end);
    }

    private static java.util.Optional<ConversationSession> nearestDifferentSession(RelationshipEvent event, AnalysisContext context) {
        return context.sessions().stream().filter(session -> session.getStartTime().isAfter(event.getStartTime().minus(Duration.ofDays(30)))
                        && session.getStartTime().isBefore(event.getStartTime().plus(Duration.ofDays(30))))
                .filter(session -> !session.getStartTime().equals(event.getStartTime()))
                .min(Comparator.comparing(session -> Duration.between(session.getStartTime(), event.getStartTime()).abs()));
    }
}
