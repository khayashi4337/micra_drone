package io.github.khayashi4337.micradrone.build.model;

/** An axis-aligned box, inclusive on both ends. Used for local (u,v,w) and world (x,y,z) alike. */
public record Box(int minA, int minB, int minC, int maxA, int maxB, int maxC) {
    public Box {
        if (minA > maxA || minB > maxB || minC > maxC) {
            throw new IllegalArgumentException("box min must not exceed max: " + minA + "," + minB + "," + minC
                    + " .. " + maxA + "," + maxB + "," + maxC);
        }
    }

    /** Builds a box from any two opposite corners. */
    public static Box of(int a0, int b0, int c0, int a1, int b1, int c1) {
        return new Box(Math.min(a0, a1), Math.min(b0, b1), Math.min(c0, c1),
                Math.max(a0, a1), Math.max(b0, b1), Math.max(c0, c1));
    }

    public boolean contains(int a, int b, int c) {
        return a >= minA && a <= maxA && b >= minB && b <= maxB && c >= minC && c <= maxC;
    }

    public long volume() {
        return (long) (maxA - minA + 1) * (maxB - minB + 1) * (maxC - minC + 1);
    }
}
