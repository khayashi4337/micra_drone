package io.github.khayashi4337.micradrone.build.model;

import java.util.Locale;

/** Which face of a part something attaches to. Openings and decorations use OUTER or INNER only. */
public enum Side {
    NORTH, EAST, SOUTH, WEST, TOP, BOTTOM, INNER, OUTER;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Side parse(String text) {
        return valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
