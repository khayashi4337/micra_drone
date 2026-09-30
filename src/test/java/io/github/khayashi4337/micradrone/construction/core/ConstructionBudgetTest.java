package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstructionBudgetTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);
    private static final UUID E = new UUID(0, 5);
    private static final double CALM = 20.0;

    private static BudgetJob fast(String id, UUID owner, long order) {
        return new BudgetJob(id, owner, true, 1, 0L, order);
    }

    private static BudgetJob drone(String id, UUID owner, int drones, long lastDroneTick, long order) {
        return new BudgetJob(id, owner, false, drones, lastDroneTick, order);
    }

    private static int given(BudgetTick t, String id) {
        return t.allowances().stream().filter(a -> a.jobId().equals(id)).mapToInt(Allowance::placements).sum();
    }

    @Test
    void droneCountFollowsTheDesignFormula() {
        BudgetConfig c = BudgetConfig.defaults();
        assertEquals(1, ConstructionBudget.droneCount(0, c));
        assertEquals(1, ConstructionBudget.droneCount(238, c));
        assertEquals(1, ConstructionBudget.droneCount(300, c));
        assertEquals(2, ConstructionBudget.droneCount(301, c));
        assertEquals(6, ConstructionBudget.droneCount(20_000, c));
    }

    @Test
    void etaCountsFastPlacementsPerTickAndDroneWorkEveryFourTicks() {
        BudgetConfig c = BudgetConfig.defaults();
        assertEquals(10L + 12L * 4L, ConstructionBudget.etaTicks(160, 12, c), "160 fast at 16/tick; 12 slow with 1 drone");
        assertEquals(0L, ConstructionBudget.etaTicks(0, 0, c));
    }

    @Test
    void atMostFourJobsRunAndEachOwnerRunsOneInArrivalOrder() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<QueuedJob> queue = List.of(new QueuedJob("a1", A), new QueuedJob("a2", A), new QueuedJob("b1", B),
                new QueuedJob("c1", C), new QueuedJob("d1", D), new QueuedJob("e1", E));
        assertEquals(List.of("a1", "b1", "c1", "d1"), b.admit(queue, List.of()),
                "a2 waits for its owner's first job; e1 waits for a free slot");
        assertEquals(List.of("e1"), b.admit(List.of(new QueuedJob("a2", A), new QueuedJob("e1", E)),
                List.of(fast("a1", A, 0), fast("b1", B, 1), fast("c1", C, 2))));
    }

    @Test
    void theServerWideCapIsSharedFairlyOverTicks() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<BudgetJob> four = List.of(fast("a", A, 0), fast("b", B, 1), fast("c", C, 2), fast("d", D, 3));
        Map<String, Integer> total = new HashMap<>();
        for (long tick = 0; tick < 4; tick++) {
            BudgetTick t = b.allocate(tick, CALM, four);
            int sum = t.allowances().stream().mapToInt(Allowance::placements).sum();
            assertTrue(sum <= BudgetConfig.DEFAULT_MAX_PLACEMENTS_PER_TICK, "never more than 32 per tick");
            for (BudgetJob j : four) {
                total.merge(j.jobId(), given(t, j.jobId()), Integer::sum);
            }
        }
        assertEquals(Map.of("a", 32, "b", 32, "c", 32, "d", 32), total, "rotation gives every job the same share");
    }

    @Test
    void droneJobsPlaceOnePerDroneEveryFourTicks() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        assertEquals(3, given(b.allocate(10, CALM, List.of(drone("a", A, 3, 6, 0))), "a"));
        assertEquals(0, given(b.allocate(9, CALM, List.of(drone("a", A, 3, 6, 0))), "a"), "only 3 ticks since the last");
    }

    @Test
    void aSlowServerHalvesEverythingUntilItRecovers() {
        ConstructionBudget b = new ConstructionBudget(BudgetConfig.defaults());
        List<BudgetJob> one = List.of(fast("a", A, 0));
        assertEquals(16, given(b.allocate(0, CALM, one), "a"));
        BudgetTick slow = b.allocate(1, 46.0, one);
        assertTrue(slow.slowed());
        assertEquals(8, given(slow, "a"));
        assertTrue(b.allocate(2, 42.0, one).slowed(), "still above the recovery line: stay slow (no flapping)");
        assertEquals(0, given(b.allocate(7, 42.0, List.of(drone("d", A, 2, 0, 0))), "d"),
                "slowed drones wait 8 ticks, 7 is not enough");
        BudgetTick back = b.allocate(8, 39.0, one);
        assertFalse(back.slowed());
        assertEquals(16, given(back, "a"));
    }
}
