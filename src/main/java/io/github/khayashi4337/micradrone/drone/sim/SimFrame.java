package io.github.khayashi4337.micradrone.drone.sim;

/**
 * One recorded step of a dry-run simulation (see {@link ScriptSimulator}): which builtin ran,
 * whether it succeeded, and the drone/plot state right after it. {@code cells} is a fresh
 * row-major worldSize*worldSize array using the CELL_* encoding below; the bird's-eye panel maps
 * each byte straight to a color. A record with an array field deliberately keeps default
 * identity-based equals - frames are never compared, only replayed in order.
 */
public record SimFrame(String action, boolean succeeded, int droneX, int droneY, byte[] cells) {
    public static final byte CELL_UNTILLED = 0;
    public static final byte CELL_TILLED = 1;
    public static final byte CELL_WHEAT_GROWING = 2;
    public static final byte CELL_WHEAT_MATURE = 3;
    public static final byte CELL_CARROT_GROWING = 4;
    public static final byte CELL_CARROT_MATURE = 5;
    public static final byte CELL_PUMPKIN_GROWING = 6;
    public static final byte CELL_PUMPKIN_MATURE = 7;
    public static final byte CELL_PUMPKIN_ROTTEN = 8;
}
