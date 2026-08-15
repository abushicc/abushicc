package com.example.relationshipagent.analysis.detector;

import com.example.relationshipagent.analysis.feature.RelationshipFeatureSet;
import com.example.relationshipagent.message.Message;
import com.example.relationshipagent.session.ConversationSession;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the six M3 rule detectors and de-duplicates their stable event keys.
 */
@Service
public class RuleBasedRelationshipEventService {

    public static final String VERSION = "event-rules-v3";
    private final List<RelationshipEventDetector> detectors = List.of(
            new LongGapDetector(), new FrequencyShiftDetector(), new ReconnectionDetector(),
            new ConflictLexiconDetector(), new ApologyDetector(), new TerminalChangeDetector());

    public List<EventCandidate> detect(AnalysisContext context) {
        Map<String, EventCandidate> unique = new LinkedHashMap<>();
        for (RelationshipEventDetector detector : detectors) {
            for (EventCandidate candidate : detector.detect(context))
                unique.putIfAbsent(candidate.eventKey(), candidate);
        }
        return unique.values().stream().sorted(Comparator.comparing(EventCandidate::startTime)
                .thenComparing(EventCandidate::eventKey)).toList();
    }

    private static final class LongGapDetector implements RelationshipEventDetector {
        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            List<ConversationSession> sessions = context.sessions();
            if (sessions.size() < 4) return List.of();
            List<Long> gaps = new ArrayList<>();
            for (int index = 1; index < sessions.size(); index++) {
                gaps.add(Math.max(0, Duration.between(sessions.get(index - 1).getEndTime(), sessions.get(index).getStartTime()).getSeconds()));
            }
            long threshold = percentile(gaps.stream().sorted().toList(), .9);
            if (threshold < Duration.ofDays(7).toSeconds()) threshold = Duration.ofDays(7).toSeconds();
            List<EventCandidate> result = new ArrayList<>();
            for (int index = 1; index < sessions.size(); index++) {
                ConversationSession previous = sessions.get(index - 1);
                ConversationSession next = sessions.get(index);
                long gap = Duration.between(previous.getEndTime(), next.getStartTime()).getSeconds();
                if (gap < threshold) continue;
                Map<String, Object> metrics = metrics("gapSeconds", gap, "personalP90GapSeconds", threshold,
                        "detectorVersion", VERSION);
                result.add(candidate("distancing-gap-" + next.getId(), "DISTANCING", previous.getEndTime(),
                        next.getStartTime(), "两次会话之间出现显著长于此聊天记录常见间隔的联系空档。", .55,
                        metrics, List.of(new EvidenceSeed("SUPPORT", null, previous.getId(), null),
                                new EvidenceSeed("SUPPORT", null, next.getId(), null))));
            }
            return result;
        }
    }

    private static final class FrequencyShiftDetector implements RelationshipEventDetector {
        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            List<RelationshipFeatureSet.MonthlyFeature> months = context.features().monthly();
            if (months.size() < 4) return List.of();
            List<EventCandidate> result = new ArrayList<>();
            for (int index = 2; index < months.size() - 1; index++) {
                double before = averageVolume(months.subList(index - 2, index));
                double after = averageVolume(months.subList(index, index + 2));
                if (before < 1 || after / before > .45d) continue;
                RelationshipFeatureSet.MonthlyFeature pivot = months.get(index);
                Map<String, Object> metrics = metrics("previousTwoMonthAverageVolume", before,
                        "nextTwoMonthAverageVolume", after, "ratio", after / before, "detectorVersion", VERSION);
                result.add(candidate("turning-frequency-" + pivot.month(), "TURNING_POINT", monthStart(pivot.month(), context),
                        monthEnd(months.get(index + 1).month(), context), "连续两个自然月的消息与会话量显著低于此前两个月。", .60,
                        metrics, List.of(new EvidenceSeed("SUPPORT", null, null, "monthly.messageAndSessionTrend." + pivot.month()))));
            }
            return result;
        }
    }

    private static final class ReconnectionDetector implements RelationshipEventDetector {
        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            List<RelationshipFeatureSet.MonthlyFeature> months = context.features().monthly();
            if (months.size() < 4) return List.of();
            double baseline = months.stream().mapToDouble(RuleBasedRelationshipEventService::volume).filter(value -> value > 0)
                    .average().orElse(0);
            List<EventCandidate> result = new ArrayList<>();
            for (int index = 2; index < months.size() - 1; index++) {
                if (volume(months.get(index - 2)) > baseline * .25 || volume(months.get(index - 1)) > baseline * .25)
                    continue;
                if (volume(months.get(index)) < baseline * .60 || volume(months.get(index + 1)) < baseline * .60)
                    continue;
                RelationshipFeatureSet.MonthlyFeature recovered = months.get(index);
                result.add(candidate("reconnection-" + recovered.month(), "RECONCILIATION", monthStart(recovered.month(), context),
                        monthEnd(months.get(index + 1).month(), context), "连续低频月份后，消息与会话频率出现持续恢复。", .60,
                        metrics("baselineVolume", baseline, "detectorVersion", VERSION),
                        List.of(new EvidenceSeed("SUPPORT", null, null, "monthly.messageAndSessionTrend." + recovered.month()))));
            }
            return result;
        }
    }

    private static final class ConflictLexiconDetector implements RelationshipEventDetector {
        private static final List<String> STRONG_CUES = List.of("分手", "别联系", "不要联系", "拉黑");
        private static final List<String> SUPPORTING_CUES = List.of("烦死", "失望", "讨厌", "算了");

        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            List<EventCandidate> result = new ArrayList<>();
            for (ConversationSession session : context.sessions()) {
                List<Message> messages = context.sessionMessages().getOrDefault(session.getId(), List.of());
                List<Message> strongHits = messages.stream().filter(message -> containsCue(message, STRONG_CUES)).toList();
                List<Message> supportingHits = messages.stream().filter(message -> containsCue(message, SUPPORTING_CUES)).toList();
                int switches = speakerSwitches(messages);
                if (strongHits.isEmpty() && (supportingHits.size() < 2 || switches < 4)) continue;
                Set<String> speakers = humanSpeakers(messages);
                if (speakers.size() < 2) continue;
                List<Message> hits = new ArrayList<>(strongHits);
                hits.addAll(supportingHits);
                double confidence = strongHits.isEmpty() ? .45 : .60;
                result.add(candidate("conflict-lexicon-" + session.getId(), "CONFLICT", session.getStartTime(), session.getEndTime(),
                        "该会话包含冲突词形和双方交替发言，需结合上下文人工复核。", confidence,
                        metrics("strongCueCount", strongHits.size(), "supportingCueCount", supportingHits.size(),
                                "speakerSwitches", switches, "detectorVersion", VERSION),
                        hits.stream().limit(3).map(message -> new EvidenceSeed("SUPPORT", message.getId(), session.getId(), null)).toList()));
            }
            return result;
        }
    }

    private static final class ApologyDetector implements RelationshipEventDetector {
        private static final List<String> CUES = List.of("对不起", "抱歉", "不好意思", "是我不好", "我错了");
        private static final List<String> CONFLICT_CUES = List.of("分手", "别联系", "不要联系", "拉黑", "烦死", "失望", "讨厌");

        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            List<EventCandidate> result = new ArrayList<>();
            for (ConversationSession session : context.sessions()) {
                List<Message> messages = context.sessionMessages().getOrDefault(session.getId(), List.of());
                for (int index = 0; index < messages.size(); index++) {
                    Message apology = messages.get(index);
                    if (!containsCue(apology, CUES)) continue;
                    boolean conflictBefore = messages.subList(0, index).stream().anyMatch(message -> containsCue(message, CONFLICT_CUES));
                    if (!conflictBefore) continue;
                    boolean responded = messages.subList(index + 1, messages.size()).stream()
                            .anyMatch(reply -> reply.getSpeaker() != null && !reply.getSpeaker().equals(apology.getSpeaker()));
                    if (!responded) continue;
                    result.add(candidate("repair-apology-" + apology.getId(), "REPAIR", apology.getMessageTime(), session.getEndTime(),
                            "同一会话中出现道歉词，之后存在对方继续回应。", .50,
                            metrics("detectorVersion", VERSION, "hasPriorConflictCue", true, "hasSubsequentOtherSpeakerReply", true),
                            List.of(new EvidenceSeed("SUPPORT", apology.getId(), session.getId(), null),
                                    new EvidenceSeed("CONTEXT", null, session.getId(), null))));
                    break;
                }
            }
            return result;
        }
    }

    private static final class TerminalChangeDetector implements RelationshipEventDetector {
        @Override
        public List<EventCandidate> detect(AnalysisContext context) {
            RelationshipFeatureSet.TerminalFeature terminal = context.features().terminal();
            if (terminal.windows().size() < 2 || terminal.anchorTime() == null) return List.of();
            RelationshipFeatureSet.TerminalWindow shortWindow = terminal.windows().stream()
                    .min(Comparator.comparingInt(RelationshipFeatureSet.TerminalWindow::months)).orElseThrow();
            RelationshipFeatureSet.TerminalWindow longWindow = terminal.windows().stream()
                    .max(Comparator.comparingInt(RelationshipFeatureSet.TerminalWindow::months)).orElseThrow();
            double shortRate = (double) shortWindow.messageCount() / shortWindow.months();
            double longRate = (double) longWindow.messageCount() / longWindow.months();
            if (longRate == 0 || shortRate / longRate > .10d) return List.of();
            return List.of(candidate("terminal-low-frequency-" + terminal.anchorTime().toEpochMilli(), "DISTANCING",
                    shortWindow.startTime(), terminal.anchorTime(), "末端窗口的消息频率低于此前较长窗口；该趋势不能单独说明关系已结束。", .55,
                    metrics("shortWindowMonths", shortWindow.months(), "shortWindowMonthlyMessages", shortRate,
                            "longWindowMonths", longWindow.months(), "longWindowMonthlyMessages", longRate,
                            "ratio", shortRate / longRate, "anchorType", terminal.anchorType(), "detectorVersion", VERSION),
                    List.of(new EvidenceSeed("SUPPORT", null, terminal.lastSessionId(), "terminal.windows"))));
        }
    }

    private static EventCandidate candidate(String key, String type, Instant start, Instant end, String statement,
                                            double confidence, Map<String, Object> metrics, List<EvidenceSeed> evidence) {
        return new EventCandidate(key, type, start, end, statement, confidence, metrics, evidence);
    }

    private static boolean containsCue(Message message, List<String> cues) {
        if (message.getCleanedContent() == null || message.getCleanedContent().isBlank()) return false;
        return cues.stream().anyMatch(message.getCleanedContent()::contains);
    }

    private static int speakerSwitches(List<Message> messages) {
        String previous = null;
        int switches = 0;
        for (Message message : messages) {
            if (message.getSpeaker() == null || Message.TYPE_SYSTEM.equals(message.getMessageType())) continue;
            if (previous != null && !previous.equals(message.getSpeaker())) switches++;
            previous = message.getSpeaker();
        }
        return switches;
    }

    private static Set<String> humanSpeakers(List<Message> messages) {
        Set<String> result = new LinkedHashSet<>();
        for (Message message : messages)
            if (message.getSpeaker() != null && !Message.TYPE_SYSTEM.equals(message.getMessageType()))
                result.add(message.getSpeaker());
        return result;
    }

    private static double averageVolume(List<RelationshipFeatureSet.MonthlyFeature> months) {
        return months.stream().mapToDouble(RuleBasedRelationshipEventService::volume).average().orElse(0);
    }

    private static double volume(RelationshipFeatureSet.MonthlyFeature month) {
        return month.messageCount() + 5d * month.sessionCount();
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static Instant monthStart(String month, AnalysisContext context) {
        return YearMonth.parse(month).atDay(1).atStartOfDay(ZoneId.of(context.features().sourceTimezone())).toInstant();
    }

    private static Instant monthEnd(String month, AnalysisContext context) {
        return YearMonth.parse(month).atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.of(context.features().sourceTimezone())).toInstant();
    }

    private static Map<String, Object> metrics(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return Map.copyOf(result);
    }
}
