package io.github.khayashi4337.micradrone.construction.core;

/** The safety envelope's limits (04 F-5). The server config may relax them for operators. */
public record SafetyLimits(int maxPlacements, int maxSizeX, int maxSizeY, int maxSizeZ, int minBuildY, int maxBuildYExclusive) {
    public static final int DEFAULT_MAX_PLACEMENTS = 20_000;
    public static final int DEFAULT_MAX_SIZE_X = 128;
    public static final int DEFAULT_MAX_SIZE_Y = 96;
    public static final int DEFAULT_MAX_SIZE_Z = 128;

    public SafetyLimits {
        if (maxPlacements <= 0 || maxSizeX <= 0 || maxSizeY <= 0 || maxSizeZ <= 0 || minBuildY >= maxBuildYExclusive) {
            throw new IllegalArgumentException("bad safety limits");
        }
    }

    public static SafetyLimits defaults(int minBuildY, int maxBuildYExclusive) {
        return new SafetyLimits(DEFAULT_MAX_PLACEMENTS, DEFAULT_MAX_SIZE_X, DEFAULT_MAX_SIZE_Y, DEFAULT_MAX_SIZE_Z, minBuildY,
                maxBuildYExclusive);
    }
}
