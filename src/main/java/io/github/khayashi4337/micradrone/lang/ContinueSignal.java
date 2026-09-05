package io.github.khayashi4337.micradrone.lang;

/** Thrown to skip to the next iteration of the innermost loop when {@code continue} runs. Stateless, so a single instance is reused. */
public final class ContinueSignal extends RuntimeException {
    public static final ContinueSignal INSTANCE = new ContinueSignal();

    private ContinueSignal() {
        super(null, null, false, false); // no message/stack trace needed, it's control flow
    }
}
