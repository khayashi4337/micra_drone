package io.github.khayashi4337.micradrone.chat;

import java.util.Map;

import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge.ClaudeCliResult;

/** Picks the fields this mod actually needs out of {@code claude -p --output-format json}'s response object. */
final class ClaudeCliJson {
    private ClaudeCliJson() {
    }

    @SuppressWarnings("unchecked")
    static ClaudeCliResult parseResult(String rawJson) {
        try {
            Map<String, Object> obj = (Map<String, Object>) MiniJson.parse(rawJson);
            boolean isError = Boolean.TRUE.equals(obj.get("is_error"));
            String sessionId = (String) obj.get("session_id");
            String result = (String) obj.get("result");
            if (isError || result == null) {
                return new ClaudeCliResult(false, null, sessionId, result != null ? result : "claude CLI reported is_error");
            }
            return new ClaudeCliResult(true, result, sessionId, null);
        } catch (RuntimeException malformed) {
            return new ClaudeCliResult(false, null, null, "could not parse claude CLI JSON output: " + malformed.getMessage());
        }
    }
}
