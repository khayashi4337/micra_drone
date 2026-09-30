package io.github.khayashi4337.micradrone.construction.core;

/** The server-wide construction budget (04 F-2). Defaults are the design's; the server config may change them. */
public record BudgetConfig(int maxRunningJobs, int maxJobsPerOwner, int maxPlacementsPerTick, int fastPlacementsPerTick,
                           int droneIntervalTicks, int placementsPerDrone, int minDrones, int maxDrones,
                           double slowdownAboveMspt, double recoverBelowMspt, int slowdownFactor) {
    public static final int DEFAULT_MAX_RUNNING_JOBS = 4;
    public static final int DEFAULT_MAX_JOBS_PER_OWNER = 1;
    public static final int DEFAULT_MAX_PLACEMENTS_PER_TICK = 32;
    public static final int DEFAULT_FAST_PLACEMENTS_PER_TICK = 16;
    /** The farm drone's LiveDroneApi.ACTION_DELAY_TICKS (DroneCadenceSyncTest keeps them equal). */
    public static final int DRONE_INTERVAL_TICKS = 4;
    public static final int PLACEMENTS_PER_DRONE = 300;
    public static final int MIN_DRONES = 1;
    public static final int MAX_DRONES = 6;
    public static final double SLOWDOWN_ABOVE_MSPT = 45.0;
    /** Not in the design: 5 ms below the slowdown line, so the speed does not flap around one threshold. */
    public static final double RECOVER_BELOW_MSPT = 40.0;
    public static final int SLOWDOWN_FACTOR = 2;

    public BudgetConfig {
        if (maxRunningJobs < 1 || maxJobsPerOwner < 1 || maxPlacementsPerTick < 1 || fastPlacementsPerTick < 1
                || droneIntervalTicks < 1 || placementsPerDrone < 1 || minDrones < 1 || maxDrones < minDrones
                || recoverBelowMspt > slowdownAboveMspt || slowdownFactor < 1) {
            throw new IllegalArgumentException("bad construction budget");
        }
    }

    public static BudgetConfig defaults() {
        return new BudgetConfig(DEFAULT_MAX_RUNNING_JOBS, DEFAULT_MAX_JOBS_PER_OWNER, DEFAULT_MAX_PLACEMENTS_PER_TICK,
                DEFAULT_FAST_PLACEMENTS_PER_TICK, DRONE_INTERVAL_TICKS, PLACEMENTS_PER_DRONE, MIN_DRONES, MAX_DRONES,
                SLOWDOWN_ABOVE_MSPT, RECOVER_BELOW_MSPT, SLOWDOWN_FACTOR);
    }
}
