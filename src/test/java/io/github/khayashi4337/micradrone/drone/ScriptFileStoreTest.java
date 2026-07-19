package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void fileNameEncodesGridPosition() {
        assertEquals("1_-2_3.mdrone", ScriptFileStore.fileName(1, -2, 3));
    }

    @Test
    void loadOrCreateDefaultWritesDefaultContentWhenMissing() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");

        String loaded = ScriptFileStore.loadOrCreateDefault(scriptsDir, 5, 64, -5);

        assertTrue(loaded.contains("print(get_world_size())"));
        Path expectedFile = scriptsDir.resolve("5_64_-5.mdrone");
        assertTrue(Files.exists(expectedFile));
        assertEquals(loaded, Files.readString(expectedFile));
    }

    @Test
    void loadOrCreateDefaultReturnsExistingContentUnchanged() throws IOException {
        Path scriptsDir = tempDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Path file = scriptsDir.resolve(ScriptFileStore.fileName(0, 0, 0));
        Files.writeString(file, "move(\"east\")\n");

        String loaded = ScriptFileStore.loadOrCreateDefault(scriptsDir, 0, 0, 0);

        assertEquals("move(\"east\")\n", loaded);
    }
}
