package com.example.relationshipagent.cli;

/** 本地只保存连接、会话定位和界面显示信息，不保存聊天正文或任何密钥。 */
public record CliConfig(String server, String chatFileId, String targetPerson, String sessionId,
                        String selfDisplayName, String targetDisplayName) {
    public static CliConfig empty() {
        return new CliConfig(null, null, null, null, null, null);
    }

    public CliConfig withSession(String value) {
        return new CliConfig(server, chatFileId, targetPerson, value, selfDisplayName, targetDisplayName);
    }

    public CliConfig withDisplayNames(String selfName, String targetName) {
        return new CliConfig(server, chatFileId, targetPerson, sessionId, selfName, targetName);
    }

    public String visibleSelfName() {
        return displayName(selfDisplayName, "你");
    }

    public String visibleTargetName() {
        return displayName(targetDisplayName, displayName(targetPerson, "对方"));
    }

    private static String displayName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
