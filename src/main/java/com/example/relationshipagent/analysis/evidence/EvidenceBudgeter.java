package com.example.relationshipagent.analysis.evidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies count/character budgets by dropping complete references only.
 */
public class EvidenceBudgeter {

    public static final int DEFAULT_MAX_ITEMS = 160;
    public static final int DEFAULT_MAX_CHARS = 90_000;

    public List<EvidencePacket> budget(List<EvidencePacket> packets, int maxItems, int maxChars) {
        if (maxItems <= 0 || maxChars <= 0) throw new IllegalArgumentException("Evidence budgets must be positive");
        int before = packets.stream().mapToInt(packet -> packet.supportCandidates().size() + packet.counterCandidates().size()).sum();
        BudgetState state = new BudgetState(maxItems, maxChars);
        List<EvidencePacket> result = new ArrayList<>();
        List<EvidencePacket> ordered = packets.stream().sorted(Comparator
                .comparingInt((EvidencePacket packet) -> packetPriority(packet.packetType()))
                .thenComparing(EvidencePacket::packetId)).toList();
        Set<String> omitted = new LinkedHashSet<>();
        for (EvidencePacket packet : ordered) {
            List<EvidenceRef> support = retain(packet.supportCandidates(), state);
            List<EvidenceRef> counter = retain(packet.counterCandidates(), state);
            if (support.isEmpty() && counter.isEmpty() && (!packet.supportCandidates().isEmpty() || !packet.counterCandidates().isEmpty())) {
                omitted.add(packet.packetId());
            }
            int after = support.size() + counter.size();
            CoverageNote coverage = new CoverageNote(before, after, false, List.of(),
                    packet.coverage().unreadableMediaCount(), packet.coverage().counterEvidenceSearched(),
                    !counter.isEmpty() || packet.coverage().counterEvidenceFound());
            result.add(new EvidencePacket(packet.packetId(), packet.packetType(), packet.subjectKey(), packet.startTime(),
                    packet.endTime(), packet.metrics(), support, counter, coverage, packet.cautions()));
        }
        int retained = result.stream().mapToInt(packet -> packet.supportCandidates().size() + packet.counterCandidates().size()).sum();
        boolean truncated = retained < before;
        return result.stream().map(packet -> new EvidencePacket(packet.packetId(), packet.packetType(), packet.subjectKey(),
                packet.startTime(), packet.endTime(), packet.metrics(), packet.supportCandidates(), packet.counterCandidates(),
                new CoverageNote(before, retained, truncated, List.copyOf(omitted), packet.coverage().unreadableMediaCount(),
                        packet.coverage().counterEvidenceSearched(), packet.coverage().counterEvidenceFound()), packet.cautions())).toList();
    }

    private List<EvidenceRef> retain(List<EvidenceRef> candidates, BudgetState state) {
        List<EvidenceRef> result = new ArrayList<>();
        for (EvidenceRef ref : candidates) {
            if (state.add(ref)) result.add(ref);
        }
        return List.copyOf(result);
    }

    private static int packetPriority(String type) {
        return switch (type) {
            case "FOCUS_QUESTION" -> 0;
            case "STAGE" -> 1;
            case "TERMINAL" -> 2;
            case "EVENT" -> 3;
            case "GLOBAL_OVERVIEW" -> 4;
            default -> 5;
        };
    }

    private static final class BudgetState {
        private final int maxItems;
        private final int maxChars;
        private final Set<String> sourceKeys = new LinkedHashSet<>();
        private int chars;

        private BudgetState(int maxItems, int maxChars) {
            this.maxItems = maxItems;
            this.maxChars = maxChars;
        }

        private boolean add(EvidenceRef ref) {
            if (sourceKeys.contains(ref.sourceKey())) return true;
            if (sourceKeys.size() >= maxItems || chars + ref.characterCount() > maxChars) return false;
            sourceKeys.add(ref.sourceKey());
            chars += ref.characterCount();
            return true;
        }
    }
}
