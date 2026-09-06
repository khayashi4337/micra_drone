package io.github.khayashi4337.micradrone.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
    /** JSON-RPC 2.0's standard code for a request body that isn't valid JSON. */
    static final int PARSE_ERROR_CODE = -32700;
    /** Maximum accepted request body size (64 KB) - guards against OOM from oversized POSTs. */
    static final int MAX_BODY_BYTES = 64 * 1024;

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
            String body = readAllWithLimit(exchange.getRequestBody());
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
            // Built through MiniJson rather than string concatenation so a message containing a
            // quote or backslash (the parser echoes offending input) can't produce invalid JSON.
            sendJsonRpcError(exchange, String.valueOf(malformedRequest.getMessage()));
        } catch (IOException bodyTooLarge) {
            exchange.sendResponseHeaders(413, -1);
        } finally {
            exchange.close();
        }
    }

    private static String readAllWithLimit(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IOException("request body exceeds " + MAX_BODY_BYTES + " byte limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void sendJsonRpcError(HttpExchange exchange, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("jsonrpc", "2.0");
        error.put("id", null);
        error.put("error", Map.of("code", PARSE_ERROR_CODE, "message", message));
        byte[] payload = MiniJson.write(error).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
