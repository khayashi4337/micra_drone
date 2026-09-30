package io.github.khayashi4337.micradrone.lang;

/** A construction script ran past its step or time limit. Its own type so callers can tell it from a script error. */
public class PlanLimitException extends MicraLangException {
    public PlanLimitException(int line, String message) {
        super(line, message);
    }
}
