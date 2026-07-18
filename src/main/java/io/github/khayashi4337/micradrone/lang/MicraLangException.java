package io.github.khayashi4337.micradrone.lang;

/** Syntax or runtime error in a Micra Drone script, carrying the source line for display. */
public class MicraLangException extends RuntimeException {
    private final int line;

    public MicraLangException(int line, String message) {
        super(message);
        this.line = line;
    }

    public int line() {
        return line;
    }

    @Override
    public String getMessage() {
        return "line " + line + ": " + super.getMessage();
    }
}
