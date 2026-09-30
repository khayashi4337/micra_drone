package io.github.khayashi4337.micradrone.build.parts;

/** A parameter value does not fit its spec; the message says how, in Japanese for the user. */
public class ParamException extends Exception {
    public ParamException(String message) {
        super(message);
    }
}
