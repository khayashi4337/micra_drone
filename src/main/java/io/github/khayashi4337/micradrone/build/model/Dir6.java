package io.github.khayashi4337.micradrone.build.model;

/** Six-way direction, used for port facings and routing entry directions. */
public enum Dir6 {
    UP, DOWN, NORTH, EAST, SOUTH, WEST;

    public String lower() {
        return WireEnum.lower(this);
    }

    public static Dir6 parse(String text) {
        return WireEnum.parse(Dir6.class, text);
    }
}
