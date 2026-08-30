package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class ClientMainThreadDispatchTest {

    /** Runs the task immediately on the calling thread - as if the "render thread" were free right away. */
    private static final MainThreadExecutor IMMEDIATE = Runnable::run;

    /** Never runs the submitted task - simulates a render thread that's stuck/unavailable. */
    private static final MainThreadExecutor NEVER_RUNS = task -> {
    };

    @Test
    void returnsTheTasksResultWhenTheExecutorRunsItRightAway() throws TimeoutException {
        String result = ClientMainThreadDispatch.runAndWait(IMMEDIATE, () -> "block info", 1000L);
        assertEquals("block info", result);
    }

    @Test
    void timesOutWhenTheExecutorNeverRunsTheTask() {
        assertThrows(TimeoutException.class,
                () -> ClientMainThreadDispatch.runAndWait(NEVER_RUNS, () -> "unreachable", 50L));
    }

    @Test
    void propagatesARuntimeExceptionThrownByTheTaskItself() {
        MainThreadExecutor executor = IMMEDIATE;
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ClientMainThreadDispatch.runAndWait(executor, () -> {
                    throw new IllegalStateException("chunk not loaded");
                }, 1000L));
        assertEquals("chunk not loaded", thrown.getCause().getMessage());
    }

    @Test
    void propagatesAnErrorThrownByTheTaskInsteadOfWaitingForTheTimeout() {
        // An Error (a stale-jar NoSuchMethodError, say) is not a RuntimeException; it must still
        // complete the future, or the caller would sit out the full timeout with no clue why.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ClientMainThreadDispatch.runAndWait(IMMEDIATE, () -> {
                    throw new NoSuchMethodError("IdeScreen.isChatModeForTesting()");
                }, 10_000L));
        assertEquals(NoSuchMethodError.class, thrown.getCause().getClass());
    }
}
