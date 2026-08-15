package com.example.relationshipagent.cli;

import java.time.Instant;
import java.util.List;

final class ApiModels {
    private ApiModels() {
    }

    record SessionInfo(String id, String targetPerson, String personaProfileId, String personaVersion,
                       String status, Instant createdAt, Instant endedAt, String simulationNotice) {
    }

    record MessageInfo(String id, String role, String content, Instant createdAt) {
    }

    record TurnInfo(String turnId, String userMessageId, MessageInfo assistantMessage, String status,
                    boolean inProgress, List<String> usedMemoryIds, List<String> usedSessionIds,
                    List<String> usedChunkIds, String retrievalDecision, String safety,
                    String historyStance, String simulationNotice) {
        TurnInfo {
            usedMemoryIds = usedMemoryIds == null ? List.of() : List.copyOf(usedMemoryIds);
            usedSessionIds = usedSessionIds == null ? List.of() : List.copyOf(usedSessionIds);
            usedChunkIds = usedChunkIds == null ? List.of() : List.copyOf(usedChunkIds);
        }
    }

    record TurnStatus(String turnId, String status, String assistantMessageId, Integer attemptCount,
                      Instant finishedAt, String simulationNotice) {
    }

    record Exchange(TurnInfo turn, long elapsedMillis) {
    }
}
