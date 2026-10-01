package io.github.khayashi4337.micradrone.build.ai;

import java.util.concurrent.CompletableFuture;

/**
 * The seam between the build-chat screen and the AI CLI (P4 task M3): the screen hands the flow's
 * {@code AskAi} prompt to {@link #run} and feeds the result back as {@code aiReply}. Keeping the
 * call behind an interface lets tests and the devkit substitute a fake (see
 * {@code build/ai/FakeStageCliRunner}); the real implementation is {@code chat.ClaudeStageRunner}.
 * A later phase (P7) widens this entry point.
 */
public interface StageCliRunner {
    CompletableFuture<StageResult> run(String prompt);
}
