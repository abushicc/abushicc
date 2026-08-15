package com.example.relationshipagent.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.relationshipagent.common.dto.ApiResponse;
import com.example.relationshipagent.common.dto.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 消息浏览 API（设计文档 9.17）。
 *
 * <p>GET /api/chat-files/{chatFileId}/messages — 分页查询消息，支持按 speaker、时间范围过滤。
 */
@RestController
@RequestMapping("/api/chat-files/{chatFileId}/messages")
public class MessageController {

    private final MessageRepository messageRepository;

    public MessageController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<MessageListItemVo>>> list(
            @PathVariable UUID chatFileId,
            @RequestParam(required = false) String speaker,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        String cid = chatFileId.toString();
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<>();
        qw.eq(Message::getChatFileId, cid);
        if (speaker != null && !speaker.isBlank()) qw.eq(Message::getSpeaker, speaker);
        if (startTime != null && !startTime.isBlank()) qw.ge(Message::getMessageTime, Instant.parse(startTime));
        if (endTime != null && !endTime.isBlank()) qw.le(Message::getMessageTime, Instant.parse(endTime));
        qw.orderByAsc(Message::getMessageTime).orderByAsc(Message::getSourceLocalId);

        Page<Message> mpPage = new Page<>(page + 1, size);
        Page<Message> result = messageRepository.selectPage(mpPage, qw);
        List<MessageListItemVo> items = result.getRecords().stream()
                .map(m -> new MessageListItemVo(m.getId(), m.getSpeaker(), m.getCleanedContent(),
                        m.getMessageTime(), m.getMessageType())).toList();
        return ResponseEntity.ok(ApiResponse.ok(new PageResult<>(items, result.getTotal(), page, size)));
    }

    public record MessageListItemVo(
            String id, String speaker, String cleanedContent, Instant messageTime, String messageType) {
    }
}
