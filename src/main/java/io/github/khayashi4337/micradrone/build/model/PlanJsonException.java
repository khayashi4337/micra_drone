package io.github.khayashi4337.micradrone.build.model;

/** A plan or patch could not be read from JSON; the message starts with the path of the bad value. */
public class PlanJsonException extends RuntimeException {
    public PlanJsonException(String message) {
        super(message);
    }
}
