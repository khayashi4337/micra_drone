package io.github.khayashi4337.micradrone.build.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Timing guards for the two questions {@link PlanPatcher} answers from its dependency index: "does this move close a
 * loop?" and "who still depends on this node?". Each patch here would take tens of seconds or more if either question
 * were answered by scanning the plan (the scanning version is quadratic in these sizes); the bounds are about ten times
 * the time the index needs, measured on the development machine (numbers in the A2 report).
 */
class PlanPatcherSpeedTest {
    /** Walls in a chain built by relocations. */
    private static final int CHAIN_WALLS = 100_000;
    /** Nodes of the plan whose every node is then removed in one patch. */
    private static final int REMOVAL_NODES = 50_000;
    /** Walls chained by adds, and the loose nodes then moved onto the deepest one (the variant of perf item P-02). */
    private static final int DEEP_CHAIN_WALLS = 50_000;
    private static final int LOOSE_NODES = 50_000;

    /*
     * About ten times the slowest patch of each group measured with the index (A2 report): chain 1,156 ms (onto later
     * walls, shuffled), removal 413 ms, deep chain 344 ms. The scanning implementation needed 12 s for a chain of only
     * 16,000 walls, 61 s for the 50,000 removals and 24 s for 16,000 loose nodes on a 16,000-wall chain.
     */
    private static final Duration CHAIN_BOUND = Duration.ofSeconds(12);
    private static final Duration REMOVAL_BOUND = Duration.ofSeconds(5);
    private static final Duration DEEP_CHAIN_BOUND = Duration.ofSeconds(5);

    private static final long SHUFFLE_SEED = 20_260_927L;
    /** Chances, in percent, that a node of the removal plan has a parent and that it rests on a wall. */
    private static final int PARENT_PERCENT = 70;
    private static final int SURFACE_PERCENT = 50;
    private static final int PERCENT = 100;

    private static final String WALL_PREFIX = "w";
    private static final String LOOSE_PREFIX = "x";
    private static final String PLAN_ID = "plan";
    private static final String STAGE_ID = "speed";
    private static final String SIDE_PARAM = "side";
    private static final String SIDE_NORTH = "north";

    private final PlanPatcher patcher = new PlanPatcher(TestParts.registry(), TestParts.bundle());

    private enum Order { ASCENDING, DESCENDING, SHUFFLED }

    /** Which wall each wall of the chain rests on: the one added just before it, or the one added just after it. */
    private enum Direction { ONTO_EARLIER, ONTO_LATER }

    private static String wallId(int i) {
        return WALL_PREFIX + i;
    }

    private static Anchor origin() {
        return new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE);
    }

    private static Anchor onWall(String wallId) {
        return new Anchor.OnSurface(wallId, Side.OUTER, 0, 0);
    }

    private static PlanNode wall(String id, String parent, Anchor anchor) {
        return new PlanNode(id, BuildingParts.WALL, parent, anchor, Map.of(SIDE_PARAM, new StrV(SIDE_NORTH)), Set.of(), "");
    }

    private static PlanPatch patch(int base, List<PlanOp> ops) {
        return new PlanPatch("p-" + base, base, STAGE_ID, ops);
    }

    private SemanticPlan applied(SemanticPlan base, List<PlanOp> ops) {
        PatchResult r = patcher.apply(base, patch(base.revision(), ops));
        assertTrue(r.ok(), () -> r.issues().stream().limit(3).toList().toString());
        return r.plan();
    }

    /** Runs the patch under the bound and prints how long it took (the numbers of the report come from here). */
    private static <T> T timed(String what, Duration bound, Supplier<T> work) {
        return assertTimeoutPreemptively(bound, () -> {
            long start = System.nanoTime();
            T result = work.get();
            System.out.println("[speed] " + what + ": " + (System.nanoTime() - start) / 1_000_000 + " ms");
            return result;
        });
    }

    private static List<Integer> ordered(int from, int toExclusive, Order order) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i < toExclusive; i++) {
            list.add(i);
        }
        if (order == Order.DESCENDING) {
            Collections.reverse(list);
        } else if (order == Order.SHUFFLED) {
            Collections.shuffle(list, new Random(SHUFFLE_SEED));
        }
        return list;
    }

    // ------------------------------------------------------------------ (a) a chain built by relocations

    private void chainByRelocations(Direction direction, Order order) {
        List<PlanOp> adds = new ArrayList<>();
        for (int i = 0; i < CHAIN_WALLS; i++) {
            adds.add(new PlanOp.AddNode(wall(wallId(i), null, origin())));
        }
        SemanticPlan walls = applied(SemanticPlan.empty(PLAN_ID), adds);
        // ONTO_EARLIER: w(i) rests on w(i-1) for i = 1..n-1; ONTO_LATER: w(i) rests on w(i+1) for i = 0..n-2.
        List<PlanOp> moves = new ArrayList<>();
        int shift = direction == Direction.ONTO_EARLIER ? -1 : 1;
        int from = direction == Direction.ONTO_EARLIER ? 1 : 0;
        int to = direction == Direction.ONTO_EARLIER ? CHAIN_WALLS : CHAIN_WALLS - 1;
        for (int i : ordered(from, to, order)) {
            moves.add(new PlanOp.MoveNode(wallId(i), onWall(wallId(i + shift))));
        }
        PatchResult r = timed("chain of " + CHAIN_WALLS + " walls by relocations " + direction + " " + order, CHAIN_BOUND,
                () -> patcher.apply(walls, patch(walls.revision(), moves)));
        assertTrue(r.ok(), () -> r.issues().stream().limit(3).toList().toString());
        // hand-derived: the two ends of the chain
        int top = direction == Direction.ONTO_EARLIER ? CHAIN_WALLS - 1 : 0;
        assertEquals(onWall(wallId(top + shift)), r.plan().node(wallId(top)).orElseThrow().anchor());
        // closing the chain into a loop is refused: the root of the chain resting on its far end
        int root = direction == Direction.ONTO_EARLIER ? 0 : CHAIN_WALLS - 1;
        PatchResult loop = timed("loop over the whole chain " + direction + " " + order, CHAIN_BOUND,
                () -> patcher.apply(r.plan(), patch(r.plan().revision(),
                        List.of(new PlanOp.MoveNode(wallId(root), onWall(wallId(top)))))));
        assertEquals(List.of("E-ANCHOR:" + wallId(root) + "#cycle"), loop.issues().stream().map(i -> i.id()).toList());
    }

    @Test
    void aChainOntoEarlierWallsBuiltInAscendingOrder() {
        chainByRelocations(Direction.ONTO_EARLIER, Order.ASCENDING);
    }

    @Test
    void aChainOntoEarlierWallsBuiltInDescendingOrder() {
        chainByRelocations(Direction.ONTO_EARLIER, Order.DESCENDING);
    }

    @Test
    void aChainOntoEarlierWallsBuiltInShuffledOrder() {
        chainByRelocations(Direction.ONTO_EARLIER, Order.SHUFFLED);
    }

    @Test
    void aChainOntoLaterWallsBuiltInAscendingOrder() {
        chainByRelocations(Direction.ONTO_LATER, Order.ASCENDING);
    }

    @Test
    void aChainOntoLaterWallsBuiltInDescendingOrder() {
        chainByRelocations(Direction.ONTO_LATER, Order.DESCENDING);
    }

    @Test
    void aChainOntoLaterWallsBuiltInShuffledOrder() {
        chainByRelocations(Direction.ONTO_LATER, Order.SHUFFLED);
    }

    @Test
    void looseNodesMovedOntoTheDeepestWallOfAChainBuiltByAdds() {
        List<PlanOp> adds = new ArrayList<>();
        for (int i = 0; i < LOOSE_NODES; i++) {
            adds.add(new PlanOp.AddNode(wall(LOOSE_PREFIX + i, null, origin())));
        }
        for (int i = 0; i < DEEP_CHAIN_WALLS; i++) {
            adds.add(new PlanOp.AddNode(wall(wallId(i), null, i == 0 ? origin() : onWall(wallId(i - 1)))));
        }
        SemanticPlan plan = applied(SemanticPlan.empty(PLAN_ID), adds);
        String deepest = wallId(DEEP_CHAIN_WALLS - 1);
        List<PlanOp> moves = new ArrayList<>();
        for (int i = 0; i < LOOSE_NODES; i++) {
            moves.add(new PlanOp.MoveNode(LOOSE_PREFIX + i, onWall(deepest)));
        }
        PatchResult r = timed(LOOSE_NODES + " loose nodes onto the end of a " + DEEP_CHAIN_WALLS + "-wall chain",
                DEEP_CHAIN_BOUND, () -> patcher.apply(plan, patch(plan.revision(), moves)));
        assertTrue(r.ok(), () -> r.issues().stream().limit(3).toList().toString());
        assertEquals(onWall(deepest), r.plan().node(LOOSE_PREFIX + (LOOSE_NODES - 1)).orElseThrow().anchor());
    }

    // ------------------------------------------------------------------ (b) removing every node

    /**
     * A random forest of walls: node i may hang below and may rest on nodes added before it, so the nodes in
     * descending index order are a reverse dependency order. Returns the plan and, per node, its dependencies.
     */
    private record Forest(SemanticPlan plan, List<List<Integer>> dependencies) {
    }

    private Forest forest() {
        Random rnd = new Random(SHUFFLE_SEED);
        List<PlanOp> adds = new ArrayList<>();
        List<List<Integer>> deps = new ArrayList<>();
        for (int i = 0; i < REMOVAL_NODES; i++) {
            List<Integer> mine = new ArrayList<>();
            String parent = null;
            Anchor anchor = origin();
            if (i > 0 && rnd.nextInt(PERCENT) < PARENT_PERCENT) {
                int p = rnd.nextInt(i);
                parent = wallId(p);
                mine.add(p);
            }
            if (i > 0 && rnd.nextInt(PERCENT) < SURFACE_PERCENT) {
                int s = rnd.nextInt(i);
                anchor = onWall(wallId(s));
                mine.add(s);
            }
            adds.add(new PlanOp.AddNode(wall(wallId(i), parent, anchor)));
            deps.add(mine);
        }
        return new Forest(applied(SemanticPlan.empty(PLAN_ID), adds), deps);
    }

    private void removeAll(String what, SemanticPlan plan, List<Integer> order) {
        List<PlanOp> removals = new ArrayList<>();
        for (int i : order) {
            removals.add(new PlanOp.RemoveNode(wallId(i)));
        }
        PatchResult r = timed(what, REMOVAL_BOUND, () -> patcher.apply(plan, patch(plan.revision(), removals)));
        assertTrue(r.ok(), () -> r.issues().stream().limit(3).toList().toString());
        assertEquals(0, r.plan().nodes().size());
    }

    @Test
    void removingEveryNodeInReverseDependencyOrder() {
        Forest f = forest();
        removeAll("removing " + REMOVAL_NODES + " nodes in reverse dependency order", f.plan(),
                ordered(0, REMOVAL_NODES, Order.DESCENDING));
    }

    @Test
    void removingEveryNodeInRandomOrderWhereAllowed() {
        Forest f = forest();
        // a random order in which each node goes only after everything that depends on it: repeatedly pick a random
        // node that nothing depends on any more
        int[] dependents = new int[REMOVAL_NODES];
        for (List<Integer> mine : f.dependencies()) {
            for (int d : mine) {
                dependents[d]++;
            }
        }
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < REMOVAL_NODES; i++) {
            if (dependents[i] == 0) {
                free.add(i);
            }
        }
        Random rnd = new Random(SHUFFLE_SEED);
        List<Integer> order = new ArrayList<>();
        while (!free.isEmpty()) {
            int k = rnd.nextInt(free.size());
            int node = free.get(k);
            free.set(k, free.get(free.size() - 1));
            free.remove(free.size() - 1);
            order.add(node);
            for (int d : f.dependencies().get(node)) {
                if (--dependents[d] == 0) {
                    free.add(d);
                }
            }
        }
        assertEquals(REMOVAL_NODES, order.size());
        removeAll("removing " + REMOVAL_NODES + " nodes in random order", f.plan(), order);
    }
}
