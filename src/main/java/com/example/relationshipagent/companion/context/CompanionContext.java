package com.example.relationshipagent.companion.context;

import com.example.relationshipagent.companion.model.ChatMessage;
import com.example.relationshipagent.companion.model.ChatSession;
import com.example.relationshipagent.persona.model.PersonaProfile;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable database-derived input for exactly one companion attempt.
 */
public record CompanionContext(ChatSession session, PersonaProfile persona, ChatMessage userMessage,
                               List<ChatMessage> history, List<MemoryView> memories, List<RetrievedChunk> chunks,
                               List<Map<String, Object>> fewShots, JsonNode personaForPrompt,
                               String retrievalDecision, List<String> retrievalReasons, String topicTerms,
                               String inputHash, String contextRefsJson, String retrievalJson) {
    public Set<String> memoryIds() {
        return memories.stream().map(MemoryView::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> chunkIds() {
        return chunks.stream().map(RetrievedChunk::chunkId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record MemoryView(String id, String type, String content, String inputHash) {
    }

    public record RetrievedChunk(String chunkId, String sessionId, String text, double score, String channel) {
    }
}
