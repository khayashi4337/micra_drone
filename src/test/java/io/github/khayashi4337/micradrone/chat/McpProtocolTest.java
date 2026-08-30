package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class McpProtocolTest {

    private static final BlockSnapshotReader FAKE_READER = (x1, y1, z1, x2, y2, z2) ->
            Optional.of("fake block at (" + x1 + "," + y1 + "," + z1 + ")");

    private static final BlockSnapshotReader UNLOADED_READER = (x1, y1, z1, x2, y2, z2) -> Optional.empty();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @Test
    void notificationsGetNoResponse() {
        Map<String, Object> notification = Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
        assertEquals(Optional.empty(), McpProtocol.handle(notification, FAKE_READER));
    }

    @Test
    void initializeEchoesTheRequestedProtocolVersionAndNamesTheServer() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1.0, "method", "initialize",
                "params", Map.of("protocolVersion", "2099-01-01"));

        Map<String, Object> response = McpProtocol.handle(request, FAKE_READER).orElseThrow();
        Map<String, Object> result = resultOf(response);

        assertEquals(1.0, response.get("id"));
        assertEquals("2099-01-01", result.get("protocolVersion"));
        assertEquals("micradrone", ((Map<?, ?>) result.get("serverInfo")).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolsListAdvertisesExactlyGetBlockSnapshot() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 2.0, "method", "tools/list");

        Map<String, Object> result = resultOf(McpProtocol.handle(request, FAKE_READER).orElseThrow());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

        assertEquals(1, tools.size());
        assertEquals("get_block_snapshot", tools.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolsCallInvokesTheReaderAndReturnsItsTextVerbatim() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 3.0, "method", "tools/call",
                "params", Map.of("name", "get_block_snapshot",
                        "arguments", Map.of("x1", 10.0, "y1", 64.0, "z1", -3.0, "x2", 10.0, "y2", 64.0, "z2", -3.0)));

        Map<String, Object> result = resultOf(McpProtocol.handle(request, FAKE_READER).orElseThrow());
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertEquals("fake block at (10,64,-3)", content.get(0).get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toolsCallReportsUnavailableForAnUnloadedRange() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 4.0, "method", "tools/call",
                "params", Map.of("name", "get_block_snapshot",
                        "arguments", Map.of("x1", 0.0, "y1", 0.0, "z1", 0.0, "x2", 0.0, "y2", 0.0, "z2", 0.0)));

        Map<String, Object> result = resultOf(McpProtocol.handle(request, UNLOADED_READER).orElseThrow());
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");

        assertTrue(((String) content.get(0).get("text")).startsWith("unavailable"));
    }

    @Test
    void toolsCallWithMissingArgumentsReportsAnErrorInsteadOfThrowing() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 5.0, "method", "tools/call",
                "params", Map.of("name", "get_block_snapshot", "arguments", Map.of("x1", 0.0)));

        Map<String, Object> result = resultOf(McpProtocol.handle(request, FAKE_READER).orElseThrow());

        assertEquals(true, result.get("isError"));
    }

    @Test
    void unknownMethodReturnsAJsonRpcError() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 6.0, "method", "not/a/real/method");

        Map<String, Object> response = McpProtocol.handle(request, FAKE_READER).orElseThrow();

        assertTrue(response.containsKey("error"));
    }
}
