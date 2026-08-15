package com.example.relationshipagent.rag;

import com.example.relationshipagent.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 混合检索 API（设计文档 9.5 / 7.5.2）。
 *
 * <p>POST /api/chat-files/{chatFileId}/search — 向量 + 关键词 + RRF 融合检索。
 * 模型未就位时自动降级为 keywordOnly 模式。
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}")
public class SearchController {

    private final RetrievalService retrievalService;

    public SearchController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse<RetrievalService.SearchResponse>> search(
            @PathVariable UUID chatFileId,
            @Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                retrievalService.search(chatFileId.toString(), request)));
    }
}
