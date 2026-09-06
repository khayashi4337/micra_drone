package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigFileTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void contentPointsAtTheGivenUrlUnderTheFixedServerName() {
        String json = McpConfigFile.content("http://127.0.0.1:54016/mcp");
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(json);
        Map<String, Object> servers = (Map<String, Object>) parsed.get("mcpServers");
        Map<String, Object> entry = (Map<String, Object>) servers.get("micradrone");

        assertEquals("http", entry.get("type"));
        assertEquals("http://127.0.0.1:54016/mcp", entry.get("url"));
    }

    @Test
    void writeCreatesParentDirectoriesAndTheFileItself() throws IOException {
        Path path = tempDir.resolve("nested/mcp-config.json");
        McpConfigFile.write(path, "http://127.0.0.1:1234/mcp");

        assertTrue(Files.isRegularFile(path));
        assertTrue(Files.readString(path).contains("http://127.0.0.1:1234/mcp"));
    }
}
