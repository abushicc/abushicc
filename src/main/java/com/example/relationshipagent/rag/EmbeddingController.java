package com.example.relationshipagent.rag;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.dto.ChatFileUploadResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * EMBED API（设计文档 9.4）。
 *
 * <p>POST /api/chat-files/{id}/embeddings/build — 触发向量化任务（异步）。
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/embeddings")
public class EmbeddingController {

    private final EmbeddingService embeddingService;
    private final ChatFileService chatFileService;

    public EmbeddingController(EmbeddingService embeddingService,
                               ChatFileService chatFileService) {
        this.embeddingService = embeddingService;
        this.chatFileService = chatFileService;
    }

    @PostMapping("/build")
    public ResponseEntity<ApiResponse<ChatFileUploadResponse>> build(@PathVariable UUID chatFileId) {
        String jobId = embeddingService.startEmbed(chatFileId.toString());
        ChatFile cf = chatFileService.getById(chatFileId.toString());
        return ResponseEntity.accepted().body(ApiResponse.ok(
                new ChatFileUploadResponse(cf.getId(), cf.getFileName(), jobId, cf.getStatus())));
    }
}
