package io.github.khayashi4337.micradrone.client;

import java.io.IOException;
import java.nio.file.Path;

import io.github.khayashi4337.micradrone.chat.BlockSnapshotToolServer;
import io.github.khayashi4337.micradrone.chat.McpConfigFile;
import net.minecraft.client.Minecraft;

/**
 * Starts BlockSnapshotToolServer once per game run (lazily, on first chat use) and keeps its
 * --mcp-config file up to date. One process-wide instance is enough - the tool has no per-
 * controller state, and every ClaudeCliBridge call points at the same URL.
 */
public final class ChatToolServerLifecycle {
    private static BlockSnapshotToolServer server;
    private static String mcpConfigPath;

    private ChatToolServerLifecycle() {
    }

    /** Starts the server on first call; returns the (already-written) --mcp-config path either way. */
    public static synchronized String ensureRunningAndConfigPath() {
        if (server == null) {
            try {
                server = new BlockSnapshotToolServer(new LiveBlockSnapshotReader(MinecraftMainThreadExecutor.INSTANCE));
                server.start();
                Path configPath = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("micradrone").resolve("mcp-config.json");
                McpConfigFile.write(configPath, server.url());
                mcpConfigPath = configPath.toString();
            } catch (IOException e) {
                throw new IllegalStateException("failed to start the AI chat tool server", e);
            }
        }
        return mcpConfigPath;
    }

    /**
     * Stops the HTTP server and clears the singleton. The server's threads are daemon threads so
     * the JVM exits fine without this, but calling it on client shutdown avoids leaving a listening
     * socket open across Minecraft's own reload paths (e.g. F3+T resource reload).
     */
    public static synchronized void shutdown() {
        if (server != null) {
            server.close();
            server = null;
            mcpConfigPath = null;
        }
    }
}
