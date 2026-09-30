package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The server-wide construction budget (04 F-2): at most N running jobs (one per owner), a per-tick cap shared by
 * rotating the starting job every tick, the drones' pace for the visible phases, and an automatic halving of every rate
 * while the average tick time is above the line, back to normal once it is below the (lower) recovery line.
 */
public final class ConstructionBudget {
    private final BudgetConfig config;
    private boolean slowed;

    public ConstructionBudget(BudgetConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static int droneCount(int totalPlacements, BudgetConfig c) {
        int wanted = (int) Math.ceil(totalPlacements / (double) c.placementsPerDrone());
        return Math.max(c.minDrones(), Math.min(c.maxDrones(), wanted));
    }

    /** Ticks to finish {@code fast} structure placements and {@code slow} drone-shown placements at the default pace. */
    public static long etaTicks(int fast, int slow, BudgetConfig c) {
        int fastRate = Math.min(c.fastPlacementsPerTick(), c.maxPlacementsPerTick());
        long fastTicks = (fast + fastRate - 1L) / fastRate;
        int drones = droneCount(fast + slow, c);
        long droneRounds = (slow + drones - 1L) / drones;
        return fastTicks + droneRounds * c.droneIntervalTicks();
    }

    public boolean slowed() {
        return slowed;
    }

    /** The queued jobs that may start now, in arrival order: free slots first, and one running job per owner. */
    public List<String> admit(List<QueuedJob> queuedInArrivalOrder, List<BudgetJob> running) {
        Map<UUID, Integer> perOwner = new HashMap<>();
        for (BudgetJob j : running) {
            perOwner.merge(j.owner(), 1, Integer::sum);
        }
        int free = config.maxRunningJobs() - running.size();
        List<String> start = new ArrayList<>();
        for (QueuedJob q : queuedInArrivalOrder) {
            if (free <= 0) {
                break;
            }
            if (perOwner.getOrDefault(q.owner(), 0) >= config.maxJobsPerOwner()) {
                continue;
            }
            perOwner.merge(q.owner(), 1, Integer::sum);
            start.add(q.jobId());
            free--;
        }
        return start;
    }

    public BudgetTick allocate(long tick, double averageMspt, List<BudgetJob> running) {
        if (!slowed && averageMspt > config.slowdownAboveMspt()) {
            slowed = true;
        } else if (slowed && averageMspt < config.recoverBelowMspt()) {
            slowed = false;
        }
        int factor = slowed ? config.slowdownFactor() : 1;
        int left = Math.max(1, config.maxPlacementsPerTick() / factor);
        int fastCap = Math.max(1, config.fastPlacementsPerTick() / factor);
        long interval = (long) config.droneIntervalTicks() * factor;
        List<BudgetJob> order = new ArrayList<>(running);
        order.sort(Comparator.comparingLong(BudgetJob::admittedOrder));
        List<Allowance> out = new ArrayList<>();
        int n = order.size();
        for (int k = 0; k < n; k++) {
            BudgetJob j = order.get((int) ((tick + k) % n));
            int want = j.fastPhase() ? fastCap : (tick - j.lastDroneTick() >= interval ? j.droneCount() : 0);
            int give = Math.min(want, left);
            if (give > 0) {
                out.add(new Allowance(j.jobId(), give));
                left -= give;
            }
        }
        return new BudgetTick(out, slowed);
    }
}
