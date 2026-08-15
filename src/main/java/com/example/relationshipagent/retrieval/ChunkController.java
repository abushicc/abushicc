package com.example.relationshipagent.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.dto.ChatFileUploadResponse;
import com.example.relationshipagent.common.dto.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * CHUNK API（设计文档 9.3 + 浏览接口）。
 *
 * <p>POST /api/chat-files/{id}/retrieval-chunks/build — 触发 CHUNK 任务（异步）
 * <p>GET  /api/chat-files/{id}/retrieval-chunks — 分页浏览检索块
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/retrieval-chunks")
public class ChunkController {

    private final ChunkService chunkService;
    private final ChatFileService chatFileService;
    private final RetrievalChunkRepository chunkRepository;

    public ChunkController(ChunkService chunkService,
                           ChatFileService chatFileService,
                           RetrievalChunkRepository chunkRepository) {
        this.chunkService = chunkService;
        this.chatFileService = chatFileService;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 触发 CHUNK 任务（异步）。
     */
    @PostMapping("/build")
    public ResponseEntity<ApiResponse<ChatFileUploadResponse>> build(@PathVariable UUID chatFileId) {
        String jobId = chunkService.startChunk(chatFileId.toString());
        ChatFile cf = chatFileService.getById(chatFileId.toString());
        return ResponseEntity.accepted().body(ApiResponse.ok(
                new ChatFileUploadResponse(cf.getId(), cf.getFileName(), jobId, cf.getStatus())));
    }

    /**
     * 分页浏览检索块（不含 embedding 列）。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<RetrievalChunk>>> list(
            @PathVariable UUID chatFileId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String cid = chatFileId.toString();
        LambdaQueryWrapper<RetrievalChunk> qw = new LambdaQueryWrapper<>();
        qw.eq(RetrievalChunk::getChatFileId, cid);
        if (sessionId != null) qw.eq(RetrievalChunk::getParentSessionId, sessionId.toString());
        qw.orderByAsc(RetrievalChunk::getParentSessionId)
                .orderByAsc(RetrievalChunk::getSequenceNo);

        Page<RetrievalChunk> mpPage = new Page<>(page + 1, size);
        Page<RetrievalChunk> result = chunkRepository.selectPage(mpPage, qw);
        return ResponseEntity.ok(ApiResponse.ok(
                new PageResult<>(result.getRecords(), result.getTotal(), page, size)));
    }
}
