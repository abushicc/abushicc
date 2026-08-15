package com.example.relationshipagent.cli;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;
import java.util.List;

/** 终端渲染集中在这里，避免颜色控制符和业务状态散落在交互逻辑中。 */
final class TerminalView {
    private final Terminal terminal;
    private final PrintWriter out;
    private final boolean color;

    TerminalView(Terminal terminal, boolean color) {
        this.terminal = terminal;
        this.out = terminal.writer();
        this.color = color && terminal.getType() != null && !"dumb".equalsIgnoreCase(terminal.getType());
    }

    void banner(CliConfig config, ApiModels.SessionInfo session) {
        out.println(style("XiaoAi CLI", AttributedStyle.BOLD.foreground(AttributedStyle.CYAN)));
        out.println(dim("连接  ") + config.server());
        out.println(dim("人物  ") + session.targetPerson() + dim(" · Persona ") + shortValue(session.personaVersion()));
        out.println(dim("显示  ") + config.visibleSelfName() + dim(" ↔ ") + config.visibleTargetName());
        out.println(dim("会话  ") + shortId(session.id()) + dim(" · ") + session.status());
        out.println();
        out.println(dim(session.simulationNotice()));
        out.println(dim("输入 /help 查看命令，行末输入 \\ 可继续下一行。"));
        out.println();
        out.flush();
    }

    void message(ApiModels.MessageInfo message, CliConfig config) {
        TerminalMessageLayout.Bubble bubble = TerminalMessageLayout.layout(
                message.role(), message.content(), config.visibleSelfName(), config.visibleTargetName(), terminal.getWidth());
        // 左右位置已经足以区分角色；统一白色能避免蓝/紫配色干扰阅读。
        AttributedStyle labelStyle = AttributedStyle.BOLD.foreground(AttributedStyle.WHITE);
        AttributedStyle messageStyle = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE);

        printIndented(bubble.indent(bubble.label()), style(bubble.label(), labelStyle));
        for (String line : bubble.contentLines()) {
            printIndented(bubble.indent(line), style(line, messageStyle));
        }
        out.println();
        out.flush();
    }

    void history(List<ApiModels.MessageInfo> messages, CliConfig config) {
        if (messages.isEmpty()) {
            out.println(dim("当前会话还没有消息。"));
            out.println();
            out.flush();
            return;
        }
        messages.forEach(message -> message(message, config));
    }

    void thinking() {
        out.print(dim("正在生成..."));
        out.flush();
    }

    void clearThinking() {
        out.print("\r\033[2K");
        out.flush();
    }

    void exchangeMeta(ApiModels.Exchange exchange) {
        ApiModels.TurnInfo turn = exchange.turn();
        StringBuilder meta = new StringBuilder("  ").append(formatDuration(exchange.elapsedMillis()));
        append(meta, turn.retrievalDecision());
        append(meta, turn.historyStance());
        if (!turn.usedMemoryIds().isEmpty()) meta.append(" · memory ").append(turn.usedMemoryIds().size());
        if (!turn.usedSessionIds().isEmpty()) meta.append(" · session ").append(turn.usedSessionIds().size());
        if (!turn.usedChunkIds().isEmpty()) meta.append(" · chunk ").append(turn.usedChunkIds().size());
        append(meta, turn.safety());
        out.println(dim(meta.toString()));
        out.println();
        out.flush();
    }

    void sessions(List<ApiModels.SessionInfo> sessions, String currentId) {
        if (sessions.isEmpty()) {
            info("没有可用会话。");
            return;
        }
        out.println(style("最近会话", AttributedStyle.BOLD));
        for (ApiModels.SessionInfo session : sessions) {
            String marker = session.id().equals(currentId) ? "*" : " ";
            out.printf("%s %s  %-8s  %s  Persona %s%n", marker, session.id(), session.status(),
                    session.targetPerson(), shortValue(session.personaVersion()));
        }
        out.println();
        out.flush();
    }

    void status(CliConfig config, ApiModels.SessionInfo session, ApiModels.Exchange lastExchange) {
        out.println(style("当前状态", AttributedStyle.BOLD));
        out.println("server        " + config.server());
        out.println("chatFileId    " + config.chatFileId());
        out.println("targetPerson  " + session.targetPerson());
        out.println("selfName      " + config.visibleSelfName());
        out.println("targetName    " + config.visibleTargetName());
        out.println("sessionId     " + session.id());
        out.println("session       " + session.status());
        out.println("persona       " + session.personaVersion() + " (" + session.personaProfileId() + ")");
        if (lastExchange != null) {
            ApiModels.TurnInfo turn = lastExchange.turn();
            out.println("lastTurn      " + turn.turnId());
            out.println("retrieval     " + value(turn.retrievalDecision()));
            out.println("stance        " + value(turn.historyStance()));
            out.println("references    memory=" + turn.usedMemoryIds().size() + ", session="
                    + turn.usedSessionIds().size() + ", chunk=" + turn.usedChunkIds().size());
            out.println("elapsed       " + formatDuration(lastExchange.elapsedMillis()));
        }
        out.println();
        out.flush();
    }

    void help() {
        out.println(style("命令", AttributedStyle.BOLD));
        out.println("/new                 创建并切换到新会话");
        out.println("/history [数量]      重新加载消息，默认 50，最多 100");
        out.println("/sessions            列出最近会话");
        out.println("/use <sessionId>     切换会话并加载历史");
        out.println("/name                查看双方显示名");
        out.println("/name me <名字>      修改你的显示名（仅影响 CLI）");
        out.println("/name target <名字>  修改对方显示名（仅影响 CLI）");
        out.println("/status              查看当前连接、Persona 和最近检索摘要");
        out.println("/clear               清屏并重绘会话标题");
        out.println("/help                显示本帮助");
        out.println("/exit                保存配置并退出");
        out.println();
        out.println(dim("普通文本会直接发送。行末输入 \\ 可输入多行消息。"));
        out.println();
        out.flush();
    }

    void clear() {
        out.print("\033[H\033[2J");
        out.flush();
        terminal.flush();
    }

    void info(String message) {
        out.println(dim(message));
        out.println();
        out.flush();
    }

    void error(String message) {
        out.println(style("错误  " + message, AttributedStyle.BOLD.foreground(AttributedStyle.RED)));
        out.println();
        out.flush();
    }

    private String dim(String value) {
        return value == null ? "" : style(value, AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT).faint());
    }

    private String style(String value, AttributedStyle attributedStyle) {
        if (!color || value == null) return value == null ? "" : value;
        return new AttributedStringBuilder().style(attributedStyle).append(value).toAnsi(terminal);
    }

    private void printIndented(int indent, String value) {
        out.print(" ".repeat(Math.max(0, indent)));
        out.println(value);
    }

    private static void append(StringBuilder value, String part) {
        if (part != null && !part.isBlank()) value.append(" · ").append(part);
    }

    private static String shortId(String value) {
        return value == null || value.length() <= 12 ? value : value.substring(0, 12) + "...";
    }

    private static String shortValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String formatDuration(long millis) {
        return millis < 1000 ? millis + "ms" : String.format("%.1fs", millis / 1000d);
    }
}
