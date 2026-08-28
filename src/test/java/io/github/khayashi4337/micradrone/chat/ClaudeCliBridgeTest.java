package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge.ClaudeCliOptions;

class ClaudeCliBridgeTest {

    @Test
    void newSessionUsesSessionIdFlag() {
        ClaudeCliOptions options = new ClaudeCliOptions("uuid-1", true, List.of(), null);
        List<String> cmd = ClaudeCliBridge.buildCommand("claude", options);

        assertEquals(List.of("claude", "-p", "--output-format", "json", "--session-id", "uuid-1"), cmd);
    }

    @Test
    void resumingUsesResumeFlag() {
        ClaudeCliOptions options = new ClaudeCliOptions("uuid-1", false, List.of(), null);
        List<String> cmd = ClaudeCliBridge.buildCommand("claude", options);

        assertTrue(cmd.contains("--resume"));
        assertFalse(cmd.contains("--session-id"));
    }

    @Test
    void dangerFlagsAreAppendedVerbatim() {
        ClaudeCliOptions options = new ClaudeCliOptions("uuid-1", true, List.of("--dangerously-skip-permissions"), null);
        List<String> cmd = ClaudeCliBridge.buildCommand("claude", options);

        assertTrue(cmd.contains("--dangerously-skip-permissions"));
    }

    @Test
    void noMcpConfigOmitsMcpFlagsEntirely() {
        ClaudeCliOptions options = new ClaudeCliOptions("uuid-1", true, List.of(), null);
        List<String> cmd = ClaudeCliBridge.buildCommand("claude", options);

        assertFalse(cmd.contains("--mcp-config"));
        assertFalse(cmd.contains("--allowedTools"));
    }

    @Test
    void mcpConfigAddsThePathAndTheFixedToolAllowlistEntry() {
        ClaudeCliOptions options = new ClaudeCliOptions("uuid-1", true, List.of(), "C:/tmp/mcp-config.json");
        List<String> cmd = ClaudeCliBridge.buildCommand("claude", options);

        int configIndex = cmd.indexOf("--mcp-config");
        assertTrue(configIndex >= 0);
        assertEquals("C:/tmp/mcp-config.json", cmd.get(configIndex + 1));
        int allowedIndex = cmd.indexOf("--allowedTools");
        assertTrue(allowedIndex >= 0);
        assertEquals("mcp__micradrone__get_block_snapshot", cmd.get(allowedIndex + 1));
    }

    @Test
    void freshSessionGeneratesARandomUuidAndMarksItNew() {
        ClaudeCliOptions options = ClaudeCliOptions.freshSession(List.of(), null);
        assertTrue(options.isNewSession());
        assertEquals(36, options.sessionId().length()); // UUID string length
    }
}
