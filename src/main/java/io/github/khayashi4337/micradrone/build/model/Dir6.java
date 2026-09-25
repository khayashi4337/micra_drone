package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/** Six-way direction, used for port facings and routing entry directions. */
public enum Dir6 {
    UP, DOWN, NORTH, EAST, SOUTH, WEST;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Dir6 parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
