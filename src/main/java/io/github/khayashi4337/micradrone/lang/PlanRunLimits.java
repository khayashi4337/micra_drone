package io.github.khayashi4337.micradrone.lang;

/** Upper bounds for one construction script run: statements executed and wall-clock time. */
public record PlanRunLimits(long maxSteps, long maxMillis) {
    private static final long DEFAULT_MAX_STEPS = 100_000;
    private static final long DEFAULT_MAX_MILLIS = 5_000;

    /**
     * A non-positive limit would refuse every script at (or before) its first counted step -
     * reject it at construction so a bad value names the field that was wrong, where it was
     * built, instead of surfacing as a confusing first-statement refusal.
     */
    public PlanRunLimits {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive but was " + maxSteps);
        }
        if (maxMillis <= 0) {
            throw new IllegalArgumentException("maxMillis must be positive but was " + maxMillis);
        }
    }

    public static final PlanRunLimits DEFAULT = new PlanRunLimits(DEFAULT_MAX_STEPS, DEFAULT_MAX_MILLIS);
}
