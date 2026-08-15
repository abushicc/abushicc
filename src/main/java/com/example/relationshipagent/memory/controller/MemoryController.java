package com.example.relationshipagent.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.ErrorCode;
import com.example.relationshipagent.chatfile.repository.ChatFileRepository;
import com.example.relationshipagent.memory.dto.MemoryItemResponse;
import com.example.relationshipagent.memory.model.MemoryItem;
import com.example.relationshipagent.memory.repository.MemoryItemRepository;
import com.example.relationshipagent.memory.service.MemoryAggregationOrchestrator;
import com.example.relationshipagent.memory.service.MemoryEmbeddingService;
import com.example.relationshipagent.memory.service.MemorySimilarityCandidateService;
import com.example.relationshipagent.memory.service.MemoryOrchestrator;
import com.example.relationshipagent.memory.service.MemoryReviewService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual Memory workflow API. No route enables a model; configuration remains the gate.
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/memories")
public class MemoryController {
    private final MemoryOrchestrator extraction;
    private final MemoryAggregationOrchestrator aggregation;
    private final MemoryEmbeddingService embedding;
    private final MemorySimilarityCandidateService candidates;
    private final MemoryReviewService review;
    private final MemoryItemRepository memories;
    private final ChatFileRepository files;

    public MemoryController(MemoryOrchestrator extraction, MemoryAggregationOrchestrator aggregation, MemoryEmbeddingService embedding, MemorySimilarityCandidateService candidates, MemoryReviewService review, MemoryItemRepository memories, ChatFileRepository files) {
        this.extraction = extraction;
        this.aggregation = aggregation;
        this.embedding = embedding;
        this.candidates = candidates;
        this.review = review;
        this.memories = memories;
        this.files = files;
    }

    @PostMapping("/extract")
    public ResponseEntity<ApiResponse<MemoryOrchestrator.Accepted>> extract(@PathVariable String chatFileId, @RequestParam(required = false) String targetPerson, @RequestParam(required = false) Integer sessionLimit, @RequestParam(name = "sessionId", required = false) List<String> sessionIds) {
        var accepted = extraction.request(chatFileId, targetPerson, sessionLimit, sessionIds);
        return ResponseEntity.status(accepted.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @PostMapping("/aggregate")
    public ResponseEntity<ApiResponse<MemoryAggregationOrchestrator.Accepted>> aggregate(@PathVariable String chatFileId, @RequestParam(required = false) String targetPerson, @RequestParam(required = false) Integer candidateLimit, @RequestParam(required = false) String candidateKey) {
        var accepted = aggregation.request(chatFileId, targetPerson, candidateLimit, candidateKey);
        return ResponseEntity.status(accepted.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @PostMapping("/embed")
    public ResponseEntity<ApiResponse<MemoryEmbeddingService.Accepted>> embed(@PathVariable String chatFileId) {
        var accepted = embedding.request(chatFileId);
        return ResponseEntity.status(accepted.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResponse.ok(accepted));
    }

    @GetMapping
    public ApiResponse<List<MemoryItemResponse>> list(@PathVariable String chatFileId, @RequestParam(required = false) String targetPerson, @RequestParam(defaultValue = "50") int size) {
        file(chatFileId);
        int safe = Math.min(Math.max(size, 1), 100);
        QueryWrapper<MemoryItem> q = new QueryWrapper<MemoryItem>().eq("chat_file_id", chatFileId).orderByDesc("created_at").last("LIMIT " + safe);
        if (targetPerson != null && !targetPerson.isBlank()) q.eq("target_person", targetPerson);
        return ApiResponse.ok(memories.selectList(q).stream().map(MemoryItemResponse::from).toList());
    }

    @PostMapping("/{memoryId}/approve")
    public ApiResponse<MemoryItemResponse> approve(@PathVariable String chatFileId, @PathVariable String memoryId) {
        owned(chatFileId, memoryId, memories.selectById(memoryId));
        return ApiResponse.ok(MemoryItemResponse.from(review.approve(memoryId)));
    }

    @PostMapping("/{memoryId}/disable")
    public ApiResponse<MemoryItemResponse> disable(@PathVariable String chatFileId, @PathVariable String memoryId) {
        owned(chatFileId, memoryId, memories.selectById(memoryId));
        return ApiResponse.ok(MemoryItemResponse.from(review.disable(memoryId)));
    }

    @GetMapping("/{memoryId}/similar-candidates")
    public ApiResponse<List<com.example.relationshipagent.memory.model.MemorySimilarityCandidate>> similar(@PathVariable String chatFileId, @PathVariable String memoryId, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(candidates.find(chatFileId, memoryId, size));
    }

    private MemoryItem owned(String file, String id, MemoryItem item) {
        if (item == null || !file.equals(item.getChatFileId())) throw new BizException(ErrorCode.MEMORY_ITEM_NOT_FOUND);
        return item;
    }

    private void file(String id) {
        if (files.selectById(id) == null) throw new BizException(ErrorCode.FILE_NOT_FOUND);
    }
}
