package io.github.khayashi4337.micradrone.construction.core;

import java.io.IOException;

/**
 * A saved file that cannot be trusted: a bad check value, a broken shape, or a version this game cannot read
 * (04 F-2: the owning job goes {@code PAUSED(RECOVERY_NEEDED)} instead of guessing at the bytes).
 */
public final class UnreadableFileException extends IOException {
    public UnreadableFileException(String message) {
        super(message);
    }
}
