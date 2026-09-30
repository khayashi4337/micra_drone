package io.github.khayashi4337.micradrone.build.model;

/** Which face of a part something attaches to. Openings and decorations use OUTER or INNER only. */
public enum Side {
    NORTH, EAST, SOUTH, WEST, TOP, BOTTOM, INNER, OUTER;

    public String lower() {
        return WireEnum.lower(this);
    }

    public static Side parse(String text) {
        return WireEnum.parse(Side.class, text);
    }
}
