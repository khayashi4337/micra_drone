package io.github.khayashi4337.micradrone.construction.core;

/** What a write to the world came back with. */
public enum PlaceResult {
    PLACED,
    /** A protection or permission event cancelled the write. */
    DENIED,
    /** The block state cannot be built on this version of the game. */
    INVALID
}
