package com.example.relationshipagent.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.List;
import java.util.UUID;

/** 聊天交互循环：普通输入发送消息，以斜杠开头的输入由本地命令处理。 */
final class ChatShell {
    private final LineReader reader;
    private final TerminalView view;
    private final CompanionApiClient api;
    private final CliConfigStore configStore;
    private CliConfig config;
    private ApiModels.SessionInfo session;
    private ApiModels.Exchange lastExchange;

    ChatShell(LineReader reader, TerminalView view, CompanionApiClient api,
              CliConfigStore configStore, CliConfig config, ApiModels.SessionInfo session) {
        this.reader = reader;
        this.view = view;
        this.api = api;
        this.configStore = configStore;
        this.config = config.withSession(session.id());
        this.session = session;
    }

    void run() {
        configStore.save(config);
        view.banner(config, session);
        showHistory(50);
        while (true) {
            try {
                String line = readMessage();
                if (line == null) break;
                if (line.isBlank()) continue;
                if (line.startsWith("/")) {
                    if (!command(line.trim())) break;
                } else {
                    send(line);
                }
            } catch (UserInterruptException ignored) {
                view.info("已取消当前输入；使用 /exit 退出。");
            } catch (CompanionApiClient.ApiException | IllegalArgumentException e) {
                view.clearThinking();
                view.error(e.getMessage());
            }
        }
        configStore.save(config);
        view.info("会话已保存。");
    }

    private String readMessage() {
        String first;
        try {
            first = reader.readLine(config.visibleSelfName() + " > ");
        } catch (EndOfFileException e) {
            return null;
        }
        if (!first.endsWith("\\")) return first;
        StringBuilder message = new StringBuilder(first.substring(0, first.length() - 1));
        while (true) {
            String next = reader.readLine("...  ");
            boolean continued = next.endsWith("\\");
            message.append('\n').append(continued ? next.substring(0, next.length() - 1) : next);
            if (!continued) return message.toString();
        }
    }

    private void send(String content) {
        String requestId = UUID.randomUUID().toString();
        // JLine 已回显输入；将它重新绘制到右侧，保证实时对话和 /history 的视觉一致。
        view.message(new ApiModels.MessageInfo(null, "USER", content, null), config);
        view.thinking();
        try {
            try {
                lastExchange = api.send(config.chatFileId(), session.id(), requestId, content);
            } catch (CompanionApiClient.ApiException expired) {
                if (!expired.isSessionEnded()) throw expired;
                // 后端按最后活动时间判定过期；当前消息尚未创建 turn，可以安全迁移到新会话后重发。
                view.clearThinking();
                view.info("当前会话已过期，正在创建新会话...");
                session = api.createSession(config.chatFileId(), config.targetPerson());
                config = config.withSession(session.id());
                configStore.save(config);
                lastExchange = api.send(config.chatFileId(), session.id(), requestId, content);
                view.info("已切换到新会话：" + session.id());
            }
        } finally {
            view.clearThinking();
        }
        ApiModels.MessageInfo assistant = lastExchange.turn().assistantMessage();
        if (assistant == null) throw new CompanionApiClient.ApiException("后端未返回助手消息");
        view.message(assistant, config);
        view.exchangeMeta(lastExchange);
    }

    private boolean command(String source) {
        String[] parts = source.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";
        return switch (command) {
            case "/new" -> {
                requireNoArgument(command, argument);
                session = api.createSession(config.chatFileId(), config.targetPerson());
                config = config.withSession(session.id());
                configStore.save(config);
                lastExchange = null;
                view.clear();
                view.banner(config, session);
                yield true;
            }
            case "/history" -> {
                showHistory(argument.isBlank() ? 50 : boundedSize(argument));
                yield true;
            }
            case "/sessions" -> {
                requireNoArgument(command, argument);
                view.sessions(api.listSessions(config.chatFileId(), config.targetPerson(), null, 20), session.id());
                yield true;
            }
            case "/use" -> {
                if (argument.isBlank()) throw new IllegalArgumentException("用法：/use <sessionId>");
                ApiModels.SessionInfo selected = api.getSession(config.chatFileId(), argument);
                if (!config.targetPerson().equals(selected.targetPerson())) {
                    throw new IllegalArgumentException("该会话不属于当前 targetPerson：" + config.targetPerson());
                }
                session = selected;
                config = config.withSession(session.id());
                configStore.save(config);
                lastExchange = null;
                view.clear();
                view.banner(config, session);
                showHistory(50);
                yield true;
            }
            case "/name" -> {
                changeDisplayName(argument);
                yield true;
            }
            case "/status" -> {
                requireNoArgument(command, argument);
                session = api.getSession(config.chatFileId(), session.id());
                view.status(config, session, lastExchange);
                yield true;
            }
            case "/clear" -> {
                requireNoArgument(command, argument);
                view.clear();
                view.banner(config, session);
                yield true;
            }
            case "/help" -> {
                requireNoArgument(command, argument);
                view.help();
                yield true;
            }
            case "/exit", "/quit" -> false;
            default -> throw new IllegalArgumentException("未知命令：" + command + "，输入 /help 查看可用命令");
        };
    }

    private void showHistory(int size) {
        List<ApiModels.MessageInfo> messages = api.messages(config.chatFileId(), session.id(), size);
        view.history(messages, config);
    }

    private void changeDisplayName(String argument) {
        if (argument.isBlank()) {
            view.info("当前显示名：" + config.visibleSelfName() + " ↔ " + config.visibleTargetName());
            return;
        }
        String[] parts = argument.split("\\s+", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("用法：/name me <名字> 或 /name target <名字>");
        }
        String name = validatedDisplayName(parts[1]);
        switch (parts[0].toLowerCase()) {
            case "me", "self", "我" -> config = config.withDisplayNames(name, config.targetDisplayName());
            case "target", "other", "对方" -> config = config.withDisplayNames(config.selfDisplayName(), name);
            default -> throw new IllegalArgumentException("名字类型只能是 me 或 target");
        }
        configStore.save(config);
        view.info("显示名已更新：" + config.visibleSelfName() + " ↔ " + config.visibleTargetName()
                + "（不会改变 targetPerson 或 Persona）");
    }

    private static String validatedDisplayName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 32 || name.contains("\n") || name.contains("\r")) {
            throw new IllegalArgumentException("显示名必须为 1–32 个字符且不能换行");
        }
        return name;
    }

    private static int boundedSize(String value) {
        try {
            int size = Integer.parseInt(value);
            if (size < 1 || size > 100) throw new NumberFormatException();
            return size;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("history 数量必须在 1 到 100 之间");
        }
    }

    private static void requireNoArgument(String command, String argument) {
        if (!argument.isBlank()) throw new IllegalArgumentException(command + " 不接受参数");
    }
}
