package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/**
 * Horizontal direction in the local build frame: NORTH is +w (forward), EAST is +u (right), SOUTH is -w,
 * WEST is -u. Clockwise turns are counted seen from above, matching {@link BuildFrame}.
 */
public enum Facing {
    NORTH, EAST, SOUTH, WEST;

    public int quarterTurns() {
        return ordinal();
    }

    public Facing rotate(int quarterTurns) {
        return values()[Math.floorMod(ordinal() + quarterTurns, 4)];
    }

    public Facing opposite() {
        return rotate(2);
    }

    /** Unit step along u (EAST is +1, WEST is -1). */
    public int du() {
        return switch (this) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    /** Unit step along w (NORTH is +1, SOUTH is -1). */
    public int dw() {
        return switch (this) {
            case NORTH -> 1;
            case SOUTH -> -1;
            default -> 0;
        };
    }

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Facing parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
