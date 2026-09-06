package io.github.khayashi4337.micradrone.lang;

/** Thrown to unwind the innermost loop when {@code break} runs. Stateless, so a single instance is reused. */
public final class BreakSignal extends RuntimeException {
    public static final BreakSignal INSTANCE = new BreakSignal();

    private BreakSignal() {
        super(null, null, false, false); // no message/stack trace needed, it's control flow
    }
}
