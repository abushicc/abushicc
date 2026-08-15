package com.example.relationshipagent.analysis.detector;

import com.example.relationshipagent.analysis.feature.RelationshipFeatureSet;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explainable monthly stage detector. A label is emitted only when it persists for at least two
 * consecutive months, so a single high or low month remains available for event detection instead.
 */
@Component
public class RelationshipStageDetector {

    public static final String VERSION = "stage-rules-v1";
    public static final String DENSE = "DENSE";
    public static final String STABLE = "STABLE";
    public static final String COOLING = "COOLING";
    public static final String SPORADIC = "SPORADIC";
    public static final String REBOUND = "REBOUND";

    public List<StageCandidate> detect(RelationshipFeatureSet features) {
        List<RelationshipFeatureSet.MonthlyFeature> months = features.monthly();
        if (months.isEmpty()) return List.of();
        Baseline baseline = Baseline.from(months);
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < months.size(); index++) {
            labels.add(classify(months, index, baseline));
        }
        List<Run> runs = stableRuns(labels);
        if (runs.isEmpty()) runs = List.of(new Run(0, months.size() - 1, STABLE));

        ZoneId zone = ZoneId.of(features.sourceTimezone());
        List<StageCandidate> candidates = new ArrayList<>();
        for (Run run : runs) {
            List<RelationshipFeatureSet.MonthlyFeature> slice = months.subList(run.start(), run.end() + 1);
            RelationshipFeatureSet.MonthlyFeature first = slice.get(0);
            RelationshipFeatureSet.MonthlyFeature last = slice.get(slice.size() - 1);
            double averageMessages = slice.stream().mapToLong(RelationshipFeatureSet.MonthlyFeature::messageCount).average().orElse(0);
            double averageSessions = slice.stream().mapToLong(RelationshipFeatureSet.MonthlyFeature::sessionCount).average().orElse(0);
            boolean aligned = sameDirection(run.type(), averageMessages, averageSessions, baseline);
            boolean replyEvidence = slice.stream().anyMatch(month -> month.replyBySpeaker().values().stream()
                    .anyMatch(reply -> reply.sampleCount() > 0));
            boolean initiativeEvidence = slice.stream().anyMatch(month -> month.sessionStartsBySpeaker().values().stream()
                    .mapToLong(Long::longValue).sum() > 0);
            double confidence = confidence(slice.size(), aligned, replyEvidence, initiativeEvidence);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("detectorVersion", VERSION);
            metrics.put("months", slice.stream().map(RelationshipFeatureSet.MonthlyFeature::month).toList());
            metrics.put("averageMessageCount", averageMessages);
            metrics.put("averageSessionCount", averageSessions);
            metrics.put("messageMedianLog1p", baseline.messageMedian());
            metrics.put("messageMadLog1p", baseline.messageMad());
            metrics.put("sessionMedianLog1p", baseline.sessionMedian());
            metrics.put("sessionMadLog1p", baseline.sessionMad());
            metrics.put("thresholdMode", baseline.quantileFallback() ? "QUANTILE_FALLBACK" : "MEDIAN_MAD");
            metrics.put("messageAndSessionAligned", aligned);
            metrics.put("replyEvidenceAvailable", replyEvidence);
            metrics.put("initiativeEvidenceAvailable", initiativeEvidence);
            String startMonth = first.month();
            String endMonth = last.month();
            candidates.add(new StageCandidate(run.type().toLowerCase() + "-" + startMonth + "-" + endMonth,
                    run.type(), atStart(startMonth, zone), atEnd(endMonth, zone), Map.copyOf(metrics),
                    summary(run.type()), confidence));
        }
        return candidates;
    }

    private static String classify(List<RelationshipFeatureSet.MonthlyFeature> months, int index, Baseline baseline) {
        RelationshipFeatureSet.MonthlyFeature current = months.get(index);
        double message = Math.log1p(current.messageCount());
        double sessions = Math.log1p(current.sessionCount());
        boolean low = current.messageCount() == 0 || (baseline.isLowMessage(message) && baseline.isLowSession(sessions));
        boolean high = baseline.isHighMessage(message) && baseline.isHighSession(sessions);
        if (low) return current.messageCount() == 0 ? SPORADIC : COOLING;
        if (high && wasLowBefore(months, index, baseline)) return REBOUND;
        if (high) return DENSE;
        return STABLE;
    }

    private static boolean wasLowBefore(List<RelationshipFeatureSet.MonthlyFeature> months, int index, Baseline baseline) {
        if (index < 2) return false;
        for (int candidate = index - 2; candidate < index; candidate++) {
            RelationshipFeatureSet.MonthlyFeature month = months.get(candidate);
            if (!(month.messageCount() == 0 || (baseline.isLowMessage(Math.log1p(month.messageCount()))
                    && baseline.isLowSession(Math.log1p(month.sessionCount()))))) return false;
        }
        return true;
    }

    private static List<Run> stableRuns(List<String> labels) {
        List<Run> result = new ArrayList<>();
        int start = 0;
        while (start < labels.size()) {
            int end = start;
            while (end + 1 < labels.size() && labels.get(end + 1).equals(labels.get(start))) end++;
            if (end - start + 1 >= 2) result.add(new Run(start, end, labels.get(start)));
            start = end + 1;
        }
        return result;
    }

    private static boolean sameDirection(String type, double messages, double sessions, Baseline baseline) {
        return switch (type) {
            case DENSE, REBOUND -> messages >= Math.expm1(baseline.messageMedian())
                    && sessions >= Math.expm1(baseline.sessionMedian());
            case COOLING, SPORADIC -> messages <= Math.expm1(baseline.messageMedian())
                    && sessions <= Math.expm1(baseline.sessionMedian());
            default -> true;
        };
    }

    private static double confidence(int monthCount, boolean aligned, boolean replyEvidence, boolean initiativeEvidence) {
        double value = .50d;
        if (monthCount >= 3) value += .15d;
        if (aligned) value += .10d;
        if (replyEvidence) value += .10d;
        if (initiativeEvidence) value += .10d;
        if (monthCount < 2) value -= .15d;
        return Math.max(0d, Math.min(.95d, value));
    }

    private static String summary(String type) {
        return switch (type) {
            case DENSE -> "该时段的消息和会话频率持续高于此聊天记录的个人基线。";
            case COOLING -> "该时段的消息和会话频率持续低于个人基线。";
            case SPORADIC -> "该时段存在连续的低频或无消息月份。";
            case REBOUND -> "低频时段后，消息和会话频率出现持续恢复。";
            default -> "该时段的消息和会话频率接近此聊天记录的个人基线。";
        };
    }

    private static Instant atStart(String month, ZoneId zone) {
        return YearMonth.parse(month).atDay(1).atStartOfDay(zone).toInstant();
    }

    private static Instant atEnd(String month, ZoneId zone) {
        return YearMonth.parse(month).atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant();
    }

    private record Run(int start, int end, String type) {
    }

    private record Baseline(double messageMedian, double messageMad, double sessionMedian, double sessionMad,
                            boolean quantileFallback, double messageLowQuantile, double messageHighQuantile,
                            double sessionLowQuantile, double sessionHighQuantile) {
        static Baseline from(List<RelationshipFeatureSet.MonthlyFeature> months) {
            List<Double> messages = months.stream().filter(month -> month.messageCount() > 0)
                    .map(month -> Math.log1p(month.messageCount())).sorted().toList();
            List<Double> sessions = months.stream().filter(month -> month.sessionCount() > 0)
                    .map(month -> Math.log1p(month.sessionCount())).sorted().toList();
            double messageMedian = percentile(messages, .5d);
            double sessionMedian = percentile(sessions, .5d);
            double messageMad = medianAbsoluteDeviation(messages, messageMedian);
            double sessionMad = medianAbsoluteDeviation(sessions, sessionMedian);
            boolean fallback = messages.size() < 6 || sessions.size() < 6 || messageMad == 0d || sessionMad == 0d;
            return new Baseline(messageMedian, messageMad, sessionMedian, sessionMad, fallback,
                    percentile(messages, .25d), percentile(messages, .75d),
                    percentile(sessions, .25d), percentile(sessions, .75d));
        }

        boolean isLowMessage(double value) {
            return quantileFallback ? value <= messageLowQuantile : value <= messageMedian - messageMad;
        }

        boolean isLowSession(double value) {
            return quantileFallback ? value <= sessionLowQuantile : value <= sessionMedian - sessionMad;
        }

        boolean isHighMessage(double value) {
            return quantileFallback ? value >= messageHighQuantile : value >= messageMedian + messageMad;
        }

        boolean isHighSession(double value) {
            return quantileFallback ? value >= sessionHighQuantile : value >= sessionMedian + sessionMad;
        }

        private static double medianAbsoluteDeviation(List<Double> values, double median) {
            return percentile(values.stream().map(value -> Math.abs(value - median)).sorted().toList(), .5d);
        }

        private static double percentile(List<Double> values, double percentile) {
            if (values.isEmpty()) return 0d;
            double rank = percentile * (values.size() - 1);
            int lower = (int) Math.floor(rank);
            int upper = (int) Math.ceil(rank);
            return values.get(lower) + (values.get(upper) - values.get(lower)) * (rank - lower);
        }
    }
}
