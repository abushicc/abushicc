package com.example.relationshipagent.session;

import com.example.relationshipagent.message.Message;

import java.util.List;

/**
 * 会话及其归属消息(M5):消除双份切分逻辑,由 builder 一次性产出会话+消息,
 * service 不再二次对齐。
 */
public record SessionWithMessages(ConversationSession session, List<Message> messages) {
}
