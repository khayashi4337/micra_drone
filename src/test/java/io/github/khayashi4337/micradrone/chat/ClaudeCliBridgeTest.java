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

    @Test
    void windowsLaunchWrapsThroughCmdExeSoPathextResolvesTheCmdWrapper() {
        List<String> logical = List.of("claude", "-p");
        List<String> launch = ClaudeCliBridge.launchCommand(logical, "Windows 11");

        assertEquals(List.of("cmd.exe", "/c", "claude", "-p"), launch);
    }

    @Test
    void aCancelledResultIsNeitherASuccessNorMistakenForAnOrdinaryFailure() {
        ClaudeCliBridge.ClaudeCliResult cancelled = ClaudeCliBridge.ClaudeCliResult.cancelled();
        assertFalse(cancelled.success());
        assertTrue(cancelled.isCancelled());
        assertFalse(new ClaudeCliBridge.ClaudeCliResult(false, null, null, "claude CLI exited 1: boom").isCancelled());
        assertFalse(new ClaudeCliBridge.ClaudeCliResult(true, "ok", "s", null).isCancelled());
    }

    @Test
    void recognisesTheWaysAMissingCliShowsUpOnEachPlatform() {
        assertTrue(ClaudeCliBridge.looksLikeCliNotFound(9009, "", "claude"));                       // cmd.exe: command not found
        assertTrue(ClaudeCliBridge.looksLikeCliNotFound(1, "'claude' は、内部コマンドまたは外部コマンド、操作可能なプログラムまたはバッチ ファイルとして認識されていません。", "claude"));
        // The same message as it actually arrives on a Japanese Windows: exit 1 + cp932 bytes read as UTF-8.
        assertTrue(ClaudeCliBridge.looksLikeCliNotFound(1, "'claude' ���A����R�}���h", "claude"));
        assertTrue(ClaudeCliBridge.looksLikeCliNotFound(-1, "Cannot run program \"claude\": error=2, No such file or directory", "claude"));
        assertFalse(ClaudeCliBridge.looksLikeCliNotFound(1, "Error: not logged in", "claude"));
        assertFalse(ClaudeCliBridge.looksLikeCliNotFound(0, "", "claude"));
    }

    @Test
    void aMissingExecutableIsReportedAsNotFoundByBothTheProbeAndASend() throws Exception {
        // Real subprocesses on purpose: this is the exact path a player without Claude Code hits.
        ClaudeCliBridge bridge = new ClaudeCliBridge("micradrone-no-such-cli-xyz");

        assertTrue(bridge.probeVersion().get(30, java.util.concurrent.TimeUnit.SECONDS).isEmpty());

        ClaudeCliBridge.ClaudeCliResult result = bridge.send("hello",
                ClaudeCliOptions.freshSession(List.of(), null)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(ClaudeCliBridge.CLI_NOT_FOUND_MESSAGE, result.errorMessage());
    }

    @Test
    void nonWindowsLaunchIsUnwrapped() {
        List<String> logical = List.of("claude", "-p");

        assertEquals(logical, ClaudeCliBridge.launchCommand(logical, "Mac OS X"));
        assertEquals(logical, ClaudeCliBridge.launchCommand(logical, "Linux"));
    }
}
