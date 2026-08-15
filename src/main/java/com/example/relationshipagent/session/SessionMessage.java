package com.example.relationshipagent.session;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话-消息关联实体 — 多对多中间表（设计文档 6.2 DDL session_message）。
 *
 * <p>复合主键 (session_id, message_id)，每条消息只属于一个会话。
 * seqInSession 记录消息在会话内的顺序（从 1 开始）。
 * 不设独立 ID 列，直接使用复合主键。
 */
@Data
@TableName("session_message")
public class SessionMessage {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 消息 ID
     */
    private String messageId;

    /**
     * 消息在会话内的序号（从 1 开始）
     */
    private Integer seqInSession;
}
