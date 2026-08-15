package com.example.relationshipagent.common.dto;

/**
 * 文件上传响应 — POST /api/chat-files 的返回体。
 */
public record ChatFileUploadResponse(
        String chatFileId,
        String fileName,
        String jobId,
        String status
) {
}
