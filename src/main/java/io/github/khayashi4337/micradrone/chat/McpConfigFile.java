package io.github.khayashi4337.micradrone.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Builds the --mcp-config JSON claude -p needs to reach BlockSnapshotToolServer, and writes it to
 * disk once per server lifetime (its URL only changes if the server itself restarts on a new port).
 */
public final class McpConfigFile {
    private McpConfigFile() {
    }

    /** The JSON content for --mcp-config, pointing at {@code serverUrl} under the fixed server name. */
    static String content(String serverUrl) {
        return MiniJson.write(Map.of("mcpServers",
                Map.of(ClaudeCliBridge.MCP_SERVER_NAME, Map.of("type", "http", "url", serverUrl))));
    }

    /** Writes the config to {@code path}, creating parent directories as needed. */
    public static void write(Path path, String serverUrl) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content(serverUrl));
    }
}
