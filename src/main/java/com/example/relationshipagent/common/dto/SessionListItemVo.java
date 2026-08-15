package com.example.relationshipagent.common.dto;

import java.time.Instant;

/**
 * 会话列表项 VO — GET /api/chat-files/{id}/sessions 的响应元素。
 */
public record SessionListItemVo(
        String id,
        Instant startTime,
        Instant endTime,
        int messageCount,
        String sessionType,
        String summary
) {
}
