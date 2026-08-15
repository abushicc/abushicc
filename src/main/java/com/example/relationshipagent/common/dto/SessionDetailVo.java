package com.example.relationshipagent.common.dto;

import java.time.Instant;

/**
 * 会话详情 VO(M7.1):speakerStats 直接返回 DB 中的 JSON 字符串,由前端自行 parse。
 */
public record SessionDetailVo(
        String id,
        Instant startTime,
        Instant endTime,
        int messageCount,
        String sessionType,
        String summary,
        String formattedText,
        String speakerStats,
        int durationSeconds
) {
}
