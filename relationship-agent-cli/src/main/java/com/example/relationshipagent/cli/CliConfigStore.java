package com.example.relationshipagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 以原子替换方式保存 CLI 最近会话，避免进程中断留下半个 JSON 文件。 */
public final class CliConfigStore {
    private final Path path;
    private final ObjectMapper json;

    public CliConfigStore(Path path, ObjectMapper json) {
        this.path = path != null ? path : defaultPath();
        this.json = json;
    }

    public CliConfig load() {
        if (!Files.isRegularFile(path)) return CliConfig.empty();
        try {
            return json.readValue(path.toFile(), CliConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 CLI 配置：" + path + "（" + e.getMessage() + "）", e);
        }
    }

    public void save(CliConfig config) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), config);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法保存 CLI 配置：" + path + "（" + e.getMessage() + "）", e);
        }
    }

    public Path path() {
        return path;
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".xiaoai", "cli.json");
    }
}
