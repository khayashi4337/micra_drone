package io.github.khayashi4337.micradrone.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The JSON-RPC message handling for a minimal MCP server exposing exactly one tool
 * (get_block_snapshot). Pure request-Map to response-Map logic, no sockets - BlockSnapshotToolServer
 * is the thin HTTP wrapper around this. Verified against the real MCP Streamable HTTP wire format
 * in SPK-2 (docs/investigations/spk2_mcp_http_connectivity.md) using the official Node.js SDK as
 * the reference implementation; this class reimplements the same message shapes in Java.
 */
final class McpProtocol {
    private static final String SERVER_NAME = ClaudeCliBridge.MCP_SERVER_NAME;
    private static final String TOOL_NAME = ClaudeCliBridge.MCP_TOOL_NAME;

    private McpProtocol() {
    }

    /** Empty for a notification (no "id"): per JSON-RPC, notifications get no response body. */
    @SuppressWarnings("unchecked")
    static Optional<Map<String, Object>> handle(Map<String, Object> request, BlockSnapshotReader reader) {
        Object id = request.get("id");
        String method = (String) request.get("method");
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        if (method == null) {
            return Optional.of(errorResponse(id, -32600, "Invalid Request: missing method"));
        }
        if (id == null) {
            // Notification (e.g. notifications/initialized) - nothing to acknowledge.
            return Optional.empty();
        }

        return switch (method) {
            case "initialize" -> Optional.of(successResponse(id, initializeResult(params)));
            case "tools/list" -> Optional.of(successResponse(id, toolsListResult()));
            case "tools/call" -> Optional.of(successResponse(id, toolsCallResult(params, reader)));
            default -> Optional.of(errorResponse(id, -32601, "Method not found: " + method));
        };
    }

    private static Map<String, Object> initializeResult(Map<String, Object> params) {
        String requestedVersion = (String) params.get("protocolVersion");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", requestedVersion != null ? requestedVersion : "2025-06-18");
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", "0.1.0"));
        return result;
    }

    private static Map<String, Object> toolsListResult() {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> numberType = Map.of("type", "number");
        inputSchema.put("properties", Map.ofEntries(
                Map.entry("x1", numberType), Map.entry("y1", numberType), Map.entry("z1", numberType),
                Map.entry("x2", numberType), Map.entry("y2", numberType), Map.entry("z2", numberType)));
        inputSchema.put("required", List.of("x1", "y1", "z1", "x2", "y2", "z2"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Reads the blocks in a coordinate range from the player's currently loaded Minecraft world. "
                + "Returns 'unavailable' text for any range that isn't currently loaded.");
        tool.put("inputSchema", inputSchema);
        return Map.of("tools", List.of(tool));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toolsCallResult(Map<String, Object> params, BlockSnapshotReader reader) {
        String name = (String) params.get("name");
        if (!TOOL_NAME.equals(name)) {
            return errorContent("unknown tool: " + name);
        }
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        Integer x1 = intArg(arguments, "x1");
        Integer y1 = intArg(arguments, "y1");
        Integer z1 = intArg(arguments, "z1");
        Integer x2 = intArg(arguments, "x2");
        Integer y2 = intArg(arguments, "y2");
        Integer z2 = intArg(arguments, "z2");
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            return errorContent("missing or non-numeric x1/y1/z1/x2/y2/z2 argument");
        }

        Optional<String> snapshot = reader.read(x1, y1, z1, x2, y2, z2);
        String text = snapshot.orElse("unavailable: part of that range is not currently loaded on the client");
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private static Integer intArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value instanceof Number n ? n.intValue() : null;
    }

    private static Map<String, Object> errorContent(String message) {
        return Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", message)));
    }

    private static Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
