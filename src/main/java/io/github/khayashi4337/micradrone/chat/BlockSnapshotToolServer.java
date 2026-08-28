package io.github.khayashi4337.micradrone.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The loopback-HTTP MCP tool server SPK-2 (docs/investigations/spk2_mcp_http_connectivity.md)
 * confirmed claude -p can connect to. One JDK HttpServer (no extra dependency, matching this
 * project's preference for the standard library over new libraries) bound to 127.0.0.1 with an OS-
 * assigned port; JSON-RPC handling lives in McpProtocol so this class stays a thin socket wrapper.
 */
public final class BlockSnapshotToolServer implements AutoCloseable {
    private final HttpServer httpServer;
    private final BlockSnapshotReader reader;

    /** Binds immediately to an OS-assigned loopback port; call {@link #start()} to begin serving. */
    public BlockSnapshotToolServer(BlockSnapshotReader reader) throws IOException {
        this.reader = reader;
        this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/mcp", this::handle);
        httpServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MicraDrone-McpToolServer");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        httpServer.start();
    }

    /** The port bound to - only meaningful after construction (before or after {@link #start()}). */
    public int port() {
        return httpServer.getAddress().getPort();
    }

    /** The URL claude -p's --mcp-config should point at. */
    public String url() {
        return "http://127.0.0.1:" + port() + "/mcp";
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = readAll(exchange.getRequestBody());
            Map<String, Object> request = (Map<String, Object>) MiniJson.parse(body);
            Optional<Map<String, Object>> response = McpProtocol.handle(request, reader);

            if (response.isEmpty()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            byte[] payload = MiniJson.write(response.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (RuntimeException malformedRequest) {
            byte[] payload = ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\""
                    + malformedRequest.getMessage() + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } finally {
            exchange.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
