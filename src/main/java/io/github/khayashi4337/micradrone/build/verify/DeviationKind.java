package io.github.khayashi4337.micradrone.build.verify;

/** How an expected block disagrees with the observed one; BLOCKED marks a position protection refused to touch. */
public enum DeviationKind {
    MISSING,
    WRONG_BLOCK,
    WRONG_STATE,
    EXTRA,
    BLOCKED
}
