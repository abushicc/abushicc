package com.example.relationshipagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliConfigStoreTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsOnlyConnectionAndSessionMetadata() {
        CliConfigStore store = new CliConfigStore(temp.resolve("nested/cli.json"), new ObjectMapper());
        CliConfig expected = new CliConfig(
                "http://localhost:8080", "file", "kiwi", "session", "阿布", "耳朵");

        store.save(expected);

        assertEquals(expected, store.load());
    }

    @Test
    void missingFileLoadsEmptyConfig() {
        CliConfigStore store = new CliConfigStore(temp.resolve("missing.json"), new ObjectMapper());
        assertEquals(CliConfig.empty(), store.load());
    }

    @Test
    void loadsLegacyConfigWithoutDisplayNames() throws Exception {
        Path path = temp.resolve("legacy.json");
        Files.writeString(path, """
                {"server":"http://localhost:8080","chatFileId":"file","targetPerson":"耳朵小","sessionId":"session"}
                """);

        CliConfig loaded = new CliConfigStore(path, new ObjectMapper()).load();

        assertEquals("你", loaded.visibleSelfName());
        assertEquals("耳朵小", loaded.visibleTargetName());
    }
}
