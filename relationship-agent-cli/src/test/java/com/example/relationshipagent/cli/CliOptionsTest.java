package com.example.relationshipagent.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {

    @Test
    void explicitOptionsOverrideSavedConfig() {
        CliOptions options = CliOptions.parse(new String[]{
                "--server", "http://127.0.0.1:9090/",
                "--chat-file-id", "new-file",
                "--target-person", "kiwi",
                "--session-id", "new-session",
                "--self-name", "阿布",
                "--target-name", "耳朵",
                "--config", "custom.json",
                "--timeout-seconds", "30",
                "--no-color"
        });

        CliConfig resolved = options.resolve(new CliConfig(
                "http://old", "old-file", "old", "old-session", "旧自己", "旧对方"));

        assertEquals("http://127.0.0.1:9090/", resolved.server());
        assertEquals("new-file", resolved.chatFileId());
        assertEquals("kiwi", resolved.targetPerson());
        assertEquals("new-session", resolved.sessionId());
        assertEquals("阿布", resolved.visibleSelfName());
        assertEquals("耳朵", resolved.visibleTargetName());
        assertEquals(Path.of("custom.json"), options.configPath());
        assertEquals(30, options.timeoutSeconds());
        assertFalse(options.color());
    }

    @Test
    void defaultsServerAndRetainsSavedValues() {
        CliOptions options = CliOptions.parse(new String[0]);
        CliConfig resolved = options.resolve(new CliConfig(null, "file", "kiwi", "session", null, null));

        assertEquals("http://localhost:8080", resolved.server());
        assertEquals("file", resolved.chatFileId());
        assertEquals("kiwi", resolved.targetPerson());
        assertEquals("session", resolved.sessionId());
        assertEquals("你", resolved.visibleSelfName());
        assertEquals("kiwi", resolved.visibleTargetName());
        assertEquals(180, options.timeoutSeconds());
    }

    @Test
    void rejectsUnknownAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--timeout-seconds", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--server"}));
    }
}
