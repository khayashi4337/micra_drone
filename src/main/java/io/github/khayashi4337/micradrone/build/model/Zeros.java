package io.github.khayashi4337.micradrone.build.model;

/**
 * Negative zero cannot live in a plan: its decimal text is "0" (the canonical form of the hash and the script
 * language both write it that way), so a plan holding -0.0 would come back from its own text as +0.0 and no longer
 * be equal to itself. Every place that stores a double the user typed goes through {@link #positive}.
 */
public final class Zeros {
    private Zeros() {
    }

    /** {@code value} itself, except that -0.0 becomes +0.0 (NaN and the infinities pass through unchanged). */
    public static double positive(double value) {
        return value == 0.0 ? 0.0 : value;
    }
}
