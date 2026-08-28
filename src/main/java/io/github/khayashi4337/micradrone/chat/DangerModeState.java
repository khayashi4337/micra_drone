package io.github.khayashi4337.micradrone.chat;

import java.util.List;

/**
 * The chat panel's safe/dangerous toggle, translated into the actual claude CLI flags (SPK-1,
 * docs/investigations/spk1_claude_cli_behavior.md). Lives per IdeScreen instance, not in
 * ChatSession - it must not be persisted, since "IDEを開いている間だけON" was the confirmed
 * requirement, not "remembered forever".
 *
 * <p>Safe (default) drops the player's own CLAUDE.md/settings (irrelevant to drone scripting and,
 * per SPK-1, a real per-message cost: ~$0.20 of cache-creation on a cold session vs ~$0.09) and
 * blocks every built-in tool, verified in SPK-1 by an actual filesystem side effect, not the
 * model's self-report. Dangerous drops all of that and instead mirrors running {@code claude}
 * directly in the player's own terminal (their own CLAUDE.md, settings, and personal MCP servers
 * included) - the explicit "same as my local PC" requirement.
 */
public final class DangerModeState {
    private static final List<String> SAFE_FLAGS =
            List.of("--setting-sources", "", "--restricted", "--strict-mcp-config", "--tools", "");
    private static final List<String> DANGEROUS_FLAGS = List.of("--dangerously-skip-permissions");

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void toggle() {
        enabled = !enabled;
    }

    /** The claude CLI flags for the current mode, ready to append to the command line. */
    public List<String> toCliFlags() {
        return enabled ? DANGEROUS_FLAGS : SAFE_FLAGS;
    }
}
