package io.github.khayashi4337.micradrone.lang;

/** Thrown to unwind script execution cleanly when Stop is requested. */
public final class ScriptStoppedException extends RuntimeException {
    public ScriptStoppedException() {
        super(null, null, false, false); // no message/stack trace needed, it's control flow
    }
}
