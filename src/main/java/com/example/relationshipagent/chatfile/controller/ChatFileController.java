package com.example.relationshipagent.chatfile.controller;

import com.example.relationshipagent.chatfile.model.ChatFile;
import com.example.relationshipagent.chatfile.service.ChatFileProcessingService;
import com.example.relationshipagent.chatfile.service.ChatFileService;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.dto.ChatFileUploadResponse;
import com.example.relationshipagent.common.dto.JobStatusResponse;
import com.example.relationshipagent.common.exception.BizException;
import com.example.relationshipagent.common.exception.SystemException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat-files")
public class ChatFileController {

    private final ChatFileService chatFileService;
    private final ChatFileProcessingService processingService;

    public ChatFileController(ChatFileService chatFileService,
                              ChatFileProcessingService processingService) {
        this.chatFileService = chatFileService;
        this.processingService = processingService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChatFileUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("selfParticipant") @NotBlank String selfParticipant,
            @RequestParam("targetParticipant") @NotBlank String targetParticipant,
            @RequestParam(value = "sourceTimezone", defaultValue = "Asia/Shanghai") String sourceTimezone) {
        try {
            ChatFile chatFile = chatFileService.uploadAndCreate(file, selfParticipant, targetParticipant, sourceTimezone);
            String jobId = processingService.startParse(chatFile.getId(), selfParticipant, targetParticipant);
            return ResponseEntity.accepted().body(ApiResponse.ok(
                    new ChatFileUploadResponse(chatFile.getId(), chatFile.getFileName(), jobId, ChatFile.STATUS_PARSING)));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new SystemException("上传聊天文件失败", e);
        }
    }

    @GetMapping("/{chatFileId}/status")
    public ResponseEntity<ApiResponse<JobStatusResponse>> getStatus(@PathVariable UUID chatFileId) {
        return ResponseEntity.ok(ApiResponse.ok(processingService.getStatus(chatFileId.toString())));
    }

    @PostMapping("/{chatFileId}/sessions/build")
    public ResponseEntity<ApiResponse<ChatFileUploadResponse>> buildSessions(@PathVariable UUID chatFileId) {
        String id = chatFileId.toString();
        String jobId = processingService.startSessionize(id);
        ChatFile chatFile = chatFileService.getById(id);
        return ResponseEntity.accepted().body(ApiResponse.ok(
                new ChatFileUploadResponse(id, chatFile.getFileName(), jobId, ChatFile.STATUS_SESSIONIZING)));
    }

    @DeleteMapping("/{chatFileId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID chatFileId) {
        try {
            chatFileService.delete(chatFileId.toString());
        } catch (Exception e) {
            throw new com.example.relationshipagent.common.exception.SystemException("删除聊天文件失败: " + e.getMessage(), e);
        }
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
