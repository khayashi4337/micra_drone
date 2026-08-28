package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A real socket-level round trip against the actual JDK HttpServer - no Minecraft involved, so
 * this is still plain JUnit, but it exercises the genuine HTTP/JSON-RPC wire format rather than
 * calling McpProtocol directly.
 */
class BlockSnapshotToolServerTest {

    private BlockSnapshotToolServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        BlockSnapshotReader reader = (x1, y1, z1, x2, y2, z2) ->
                Optional.of("test-block at (" + x1 + "," + y1 + "," + z1 + ")");
        server = new BlockSnapshotToolServer(reader);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private Map<String, Object> post(Map<String, Object> jsonRpcRequest) throws Exception {
        String body = MiniJson.write(jsonRpcRequest);
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.url()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MiniJson.parse(response.body());
        return parsed;
    }

    @Test
    void bindsToLoopbackWithARealPort() {
        assertTrue(server.port() > 0);
        assertTrue(server.url().startsWith("http://127.0.0.1:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullHandshakeThenToolCallWorksOverRealHttp() throws Exception {
        Map<String, Object> initResponse = post(Map.of("jsonrpc", "2.0", "id", 1.0, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18")));
        assertTrue(initResponse.containsKey("result"));

        Map<String, Object> listResponse = post(Map.of("jsonrpc", "2.0", "id", 2.0, "method", "tools/list"));
        assertEquals("get_block_snapshot",
                ((java.util.List<Map<String, Object>>) ((Map<String, Object>) listResponse.get("result")).get("tools"))
                        .get(0).get("name"));

        Map<String, Object> callResponse = post(Map.of("jsonrpc", "2.0", "id", 3.0, "method", "tools/call",
                "params", Map.of("name", "get_block_snapshot",
                        "arguments", Map.of("x1", 5.0, "y1", 64.0, "z1", 2.0, "x2", 5.0, "y2", 64.0, "z2", 2.0))));
        Map<String, Object> callResult = (Map<String, Object>) callResponse.get("result");
        java.util.List<Map<String, Object>> content = (java.util.List<Map<String, Object>>) callResult.get("content");
        assertEquals("test-block at (5,64,2)", content.get(0).get("text"));
    }

    @Test
    void malformedBodyReturnsAJsonRpcParseErrorRatherThanHangingOrCrashing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.url()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("not json", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"error\""));
    }
}
