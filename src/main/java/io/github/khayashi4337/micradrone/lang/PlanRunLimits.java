package io.github.khayashi4337.micradrone.lang;

/** Upper bounds for one construction script run: statements executed and wall-clock time. */
public record PlanRunLimits(long maxSteps, long maxMillis) {
    private static final long DEFAULT_MAX_STEPS = 100_000;
    private static final long DEFAULT_MAX_MILLIS = 5_000;

    public static final PlanRunLimits DEFAULT = new PlanRunLimits(DEFAULT_MAX_STEPS, DEFAULT_MAX_MILLIS);
}
