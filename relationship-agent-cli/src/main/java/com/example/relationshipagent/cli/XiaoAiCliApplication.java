package com.example.relationshipagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** XiaoAi Companion 的独立终端入口。 */
public final class XiaoAiCliApplication {
    private static final List<String> COMMANDS = List.of(
            "/new", "/history", "/sessions", "/use", "/name", "/status", "/clear", "/help", "/exit");

    private XiaoAiCliApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println("使用 --help 查看完整参数。");
            return 2;
        }
        if (options.help()) {
            System.out.println(usage());
            return 0;
        }

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        CliConfigStore store = new CliConfigStore(options.configPath(), json);
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(commandCompleter())
                    // 提交后清除 JLine 原始输入行，聊天区只保留右侧的正式消息，避免重复显示。
                    .option(LineReader.Option.ERASE_LINE_ON_FINISH, true)
                    .build();
            TerminalView view = new TerminalView(terminal, options.color());
            CliConfig config = completeRequired(options.resolve(store.load()), reader);
            CompanionApiClient api = new CompanionApiClient(config.server(), json,
                    Duration.ofSeconds(options.timeoutSeconds()));
            api.checkAvailable();
            ApiModels.SessionInfo session = resolveSession(api, config, options.sessionId());
            config = config.withSession(session.id());
            store.save(config);
            new ChatShell(reader, view, api, store, config, session).run();
            return 0;
        } catch (CompanionApiClient.ApiException | IllegalStateException e) {
            System.err.println("XiaoAi CLI 启动失败：" + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("无法初始化终端：" + e.getMessage());
            return 1;
        }
    }

    private static CliConfig completeRequired(CliConfig config, LineReader reader) {
        String chatFileId = promptRequired(reader, "chatFileId > ", config.chatFileId());
        String targetPerson = promptRequired(reader, "targetPerson > ", config.targetPerson());
        return new CliConfig(config.server(), chatFileId, targetPerson, config.sessionId(),
                config.selfDisplayName(), config.targetDisplayName());
    }

    private static String promptRequired(LineReader reader, String prompt, String current) {
        if (current != null && !current.isBlank()) return current;
        while (true) {
            String value = reader.readLine(prompt).trim();
            if (!value.isBlank()) return value;
        }
    }

    private static ApiModels.SessionInfo resolveSession(CompanionApiClient api, CliConfig config,
                                                         String explicitSessionId) {
        if (config.sessionId() != null && !config.sessionId().isBlank()) {
            try {
                ApiModels.SessionInfo saved = api.getSession(config.chatFileId(), config.sessionId());
                if (config.targetPerson().equals(saved.targetPerson()) && "ACTIVE".equals(saved.status())) return saved;
                if (explicitSessionId != null) {
                    throw new CompanionApiClient.ApiException("指定会话不是当前人物的 ACTIVE 会话");
                }
            } catch (CompanionApiClient.ApiException e) {
                if (explicitSessionId != null) throw e;
                // 本地保存的会话可能已结束或被删除，继续回退到服务端最近 ACTIVE 会话。
            }
        }
        return api.listSessions(config.chatFileId(), config.targetPerson(), "ACTIVE", 1).stream()
                .findFirst().orElseGet(() -> api.createSession(config.chatFileId(), config.targetPerson()));
    }

    private static Completer commandCompleter() {
        return (LineReader reader, ParsedLine line, List<Candidate> candidates) -> {
            if (!line.line().startsWith("/")) return;
            COMMANDS.stream().filter(value -> value.startsWith(line.word()))
                    .map(Candidate::new).forEach(candidates::add);
        };
    }

    static String usage() {
        return """
                XiaoAi CLI - Companion 终端聊天客户端

                用法：
                  xiaoai.cmd [选项]

                选项：
                  --server <url>          后端地址，默认 http://localhost:8080
                  --chat-file-id <id>     聊天文件 ID
                  --target-person <name>  目标人物（绑定后端 Persona）
                  --self-name <name>      你的界面显示名，默认“你”
                  --target-name <name>    对方界面显示名，默认 targetPerson
                  --session-id <id>       恢复指定 ACTIVE 会话
                  --config <path>         自定义本地配置文件
                  --timeout-seconds <n>   单轮等待上限，默认 180
                  --no-color              禁用 ANSI 颜色
                  --help, -h              显示帮助
                """;
    }
}
