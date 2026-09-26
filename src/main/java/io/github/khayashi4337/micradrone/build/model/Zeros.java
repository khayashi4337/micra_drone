package io.github.khayashi4337.micradrone.build.model;

/**
 * Negative zero cannot live in a plan: its decimal text is "0" (the canonical form of the hash and the script
 * language both write it that way), so a plan holding -0.0 would come back from its own text as +0.0 and no longer
 * be equal to itself. The plan model applies {@link #positive} where the patcher takes a typed number in: a NUM
 * parameter ({@code ParamValidator}) and the rate of a {@code LogisticsPlan.CargoFlow}. A {@code ParamValue.NumV}
 * built directly keeps its sign; only a value that went through the patcher is flattened.
 */
public final class Zeros {
    private Zeros() {
    }

    /** {@code value} itself, except that -0.0 becomes +0.0 (NaN and the infinities pass through unchanged). */
    public static double positive(double value) {
        return value == 0.0 ? 0.0 : value;
    }
}
