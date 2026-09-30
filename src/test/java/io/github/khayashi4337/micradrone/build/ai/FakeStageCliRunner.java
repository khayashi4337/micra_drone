package io.github.khayashi4337.micradrone.build.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link StageCliRunner} that returns queued replies (M3): tests and the devkit drive the build
 * chat flow without spawning a real claude CLI. {@link #run} records the prompt and completes
 * immediately with the next queued result; an empty queue answers with a generic failure so a
 * missing stub is loud rather than hanging.
 */
public final class FakeStageCliRunner implements StageCliRunner {
    private final Queue<StageResult> replies = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();

    public FakeStageCliRunner enqueue(StageResult result) {
        replies.add(result);
        return this;
    }

    /** The prompts this fake was called with, in order. */
    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public CompletableFuture<StageResult> run(String prompt) {
        prompts.add(prompt);
        StageResult next = replies.poll();
        if (next == null) {
            next = new StageResult(false, null, "FakeStageCliRunner: no reply enqueued", false);
        }
        return CompletableFuture.completedFuture(next);
    }
}
