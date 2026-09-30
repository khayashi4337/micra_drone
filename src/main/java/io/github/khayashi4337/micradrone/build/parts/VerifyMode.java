package io.github.khayashi4337.micradrone.build.parts;

/** How strictly the placed blocks are compared with the expected ones after building. */
public enum VerifyMode {
    EXACT, STATE_SUBSET, BLOCK_ONLY, ASSEMBLED_AWAY
}
