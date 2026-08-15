package com.example.relationshipagent.parser;

import java.time.Instant;

/**
 * 解析后的消息（设计文档 7.2.1）。
 * <p>
 * 时间字段存储 UTC（与 5.2 / DDL TIMESTAMPTZ 一致）。
 * sourceLocalId 为 CSV 中的 localId（BIGINT，与 DDL 一致）。
 */
public record ParsedMessage(
        String speaker,
        String content,
        String cleanedContent,
        Instant messageTime,
        MessageType messageType,
        long sourceLocalId,
        int sourceLineNo,
        String mediaSourceRef      // 非 null 表示需要写入 message_media
) {
}
