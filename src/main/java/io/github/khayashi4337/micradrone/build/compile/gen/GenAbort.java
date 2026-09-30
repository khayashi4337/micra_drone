package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Issue;

/** Stops the generation of one part (or, when fatal, the whole compile) with the Issue that explains why. */
public final class GenAbort extends RuntimeException {
    private final transient Issue issue;
    private final boolean fatal;

    public GenAbort(Issue issue, boolean fatal) {
        // no cause, no suppression, no stack trace: this is an expected outcome, not a program error
        super(issue.message(), null, false, false);
        this.issue = issue;
        this.fatal = fatal;
    }

    public Issue issue() {
        return issue;
    }

    public boolean fatal() {
        return fatal;
    }
}
