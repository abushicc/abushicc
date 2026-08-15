package com.example.relationshipagent.analysis.feature;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Purely deterministic description of a chat file. Values are observations, not relationship
 * diagnoses. All month strings use the source timezone recorded on the chat file.
 */
public record RelationshipFeatureSet(
        String schemaVersion,
        String sourceTimezone,
        Coverage coverage,
        List<MonthlyFeature> monthly,
        TerminalFeature terminal
) {
    public record Coverage(
            Instant firstMessageTime,
            Instant lastMessageTime,
            long messageCount,
            long sessionCount,
            Map<String, Long> messageTypeCounts,
            long mediaMessageCount,
            long unreadableMediaCount
    ) {
    }

    public record MonthlyFeature(
            String month,
            long messageCount,
            long sessionCount,
            long activeDays,
            Map<String, Long> speakerMessageCounts,
            Map<String, ReplyMetric> replyBySpeaker,
            Map<String, Long> sessionStartsBySpeaker,
            Map<String, Long> singleMessageUnansweredBySpeaker,
            SessionIntensity sessionIntensity,
            double longInterSessionGapRatio
    ) {
    }

    /**
     * Null quantiles explicitly mean that there were no reply samples.
     */
    public record ReplyMetric(Double p50Seconds, Double p90Seconds, long sampleCount) {
    }

    public record SessionIntensity(
            Double messageCountP50,
            Double messageCountP90,
            Double durationP50Seconds,
            Double durationP90Seconds,
            Map<String, Double> turnCountP50BySpeaker,
            Map<String, Double> turnCountP90BySpeaker,
            long sampleCount
    ) {
    }

    public record TerminalFeature(
            String anchorType,
            Instant anchorTime,
            String lastSessionId,
            String lastSessionFirstSpeaker,
            String lastMessageSpeaker,
            boolean lastMessageHasSubsequentReply,
            List<TerminalWindow> windows,
            List<TerminalSession> lastSessions
    ) {
    }

    public record TerminalWindow(
            int months,
            Instant startTime,
            long messageCount,
            long sessionCount,
            Map<String, Long> speakerMessageCounts,
            Map<String, Long> sessionStartsBySpeaker
    ) {
    }

    public record TerminalSession(
            String sessionId,
            Instant startTime,
            Instant endTime,
            long messageCount,
            String firstSpeaker,
            String lastSpeaker
    ) {
    }
}
