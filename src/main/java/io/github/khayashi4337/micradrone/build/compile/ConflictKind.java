package io.github.khayashi4337.micradrone.build.compile;

/** Why an expected block disagrees with the world: the player changed it, or it is gone entirely. */
public enum ConflictKind {
    PLAYER_MODIFIED,
    MISSING
}
