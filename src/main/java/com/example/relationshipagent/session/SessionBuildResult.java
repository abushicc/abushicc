package com.example.relationshipagent.session;

import java.util.List;

/**
 * 会话构建结果(M5):携带每个会话及其消息列表。
 */
public record SessionBuildResult(List<SessionWithMessages> sessions) {
}
