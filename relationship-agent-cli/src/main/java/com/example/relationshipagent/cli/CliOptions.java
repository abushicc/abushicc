package com.example.relationshipagent.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 命令行参数。显式参数会覆盖本地持久化配置。 */
public record CliOptions(String server, String chatFileId, String targetPerson, String sessionId,
                         String selfDisplayName, String targetDisplayName,
                         Path configPath, int timeoutSeconds, boolean color, boolean help) {

    public static CliOptions parse(String[] args) {
        String server = null;
        String chatFileId = null;
        String targetPerson = null;
        String sessionId = null;
        String selfDisplayName = null;
        String targetDisplayName = null;
        Path configPath = null;
        int timeoutSeconds = 180;
        boolean color = true;
        boolean help = false;
        List<String> values = new ArrayList<>(List.of(args));
        for (int i = 0; i < values.size(); i++) {
            String option = values.get(i);
            switch (option) {
                case "--server" -> server = requiredValue(values, ++i, option);
                case "--chat-file-id" -> chatFileId = requiredValue(values, ++i, option);
                case "--target-person" -> targetPerson = requiredValue(values, ++i, option);
                case "--session-id" -> sessionId = requiredValue(values, ++i, option);
                case "--self-name", "--my-name" -> selfDisplayName = requiredValue(values, ++i, option);
                case "--target-name" -> targetDisplayName = requiredValue(values, ++i, option);
                case "--config" -> configPath = Path.of(requiredValue(values, ++i, option));
                case "--timeout-seconds" -> timeoutSeconds = positiveInt(requiredValue(values, ++i, option), option);
                case "--no-color" -> color = false;
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("未知参数：" + option);
            }
        }
        return new CliOptions(server, chatFileId, targetPerson, sessionId, selfDisplayName, targetDisplayName, configPath,
                timeoutSeconds, color, help);
    }

    public CliConfig resolve(CliConfig saved) {
        return new CliConfig(first(server, saved.server(), "http://localhost:8080"),
                first(chatFileId, saved.chatFileId(), null),
                first(targetPerson, saved.targetPerson(), null),
                first(sessionId, saved.sessionId(), null),
                first(selfDisplayName, saved.selfDisplayName(), "你"),
                first(targetDisplayName, saved.targetDisplayName(), null));
    }

    private static String requiredValue(List<String> args, int index, String option) {
        if (index >= args.size() || args.get(index).startsWith("--")) {
            throw new IllegalArgumentException(option + " 缺少参数值");
        }
        return args.get(index).trim();
    }

    private static int positiveInt(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " 必须是正整数");
        }
    }

    private static String first(String explicit, String saved, String fallback) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        if (saved != null && !saved.isBlank()) return saved.trim();
        return fallback;
    }
}
