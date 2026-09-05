package io.github.khayashi4337.micradrone.lang;

/** Thrown to unwind a function call's body when {@code return} runs, carrying its value. */
public final class ReturnSignal extends RuntimeException {
    private final Object value;

    public ReturnSignal(Object value) {
        super(null, null, false, false); // no message/stack trace needed, it's control flow
        this.value = value;
    }

    public Object value() {
        return value;
    }
}
