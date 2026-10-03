package io.github.khayashi4337.micradrone.chat;

import io.github.khayashi4337.micradrone.build.ai.StageCliRunner;
import io.github.khayashi4337.micradrone.build.ai.StageResult;
import java.util.concurrent.CompletableFuture;

/**
 * The build chat's {@link StageCliRunner} on top of {@link ClaudeCliBridge} (P4 task M3): every
 * {@link #run} is a brand-new {@code claude -p} session with no MCP tools - a build prompt is
 * self-contained (sample plan, parts catalog, palette), so unlike the script chat it never resumes
 * a session and never needs the block-snapshot tool.
 *
 * <p>Uses its own bridge instance rather than the script panel's, but the executable name is handed
 * in by the caller so both share the one constant that names it.
 */
public final class ClaudeStageRunner implements StageCliRunner {
    private final ClaudeCliBridge bridge;

    public ClaudeStageRunner(String claudeExecutable) {
        this.bridge = new ClaudeCliBridge(claudeExecutable);
    }

    @Override
    public CompletableFuture<StageResult> run(String prompt) {
        return bridge.send(prompt, ClaudeCliBridge.ClaudeCliOptions.freshSession(null))
                .thenApply(ClaudeStageRunner::toStageResult);
    }

    static StageResult toStageResult(ClaudeCliBridge.ClaudeCliResult result) {
        boolean cliMissing = ClaudeCliBridge.CLI_NOT_FOUND_MESSAGE.equals(result.errorMessage());
        boolean loginMissing = ClaudeCliErrors.looksLikeLoginMissing(result.errorMessage());
        return new StageResult(result.success(), result.responseText(), result.errorMessage(),
                cliMissing, loginMissing);
    }

    /** Stops the round trip in flight; the pending future then completes as a failed result. */
    public void cancel() {
        bridge.cancel();
    }

    /** Shuts the bridge's background executor down - call when the owning screen is removed. */
    public void close() {
        bridge.close();
    }
}
