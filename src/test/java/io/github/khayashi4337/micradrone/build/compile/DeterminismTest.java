package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterminismTest {
    /** How often the same plan is compiled; every run must produce the identical manifest hash. */
    private static final int COMPILE_RUNS = 1000;
    /** Fixed seeds of the hut node-order shuffles; node order must not change the hash. */
    private static final int HUT_SHUFFLES = 50;
    /** Fixed seeds of the showcase node-order shuffles, per site facing. */
    private static final int SHOWCASE_SHUFFLES = 10;
    private static final String SHOWCASE_ROOF = "gable";

    @Test
    void theSameInputGivesTheSameHashOneThousandTimes() throws IOException {
        var plan = GoldenHutTest.hut();
        String first = GoldenHutTest.compileHut(plan).manifest().hash();
        // The golden file pins the value itself: a silent change in any generator moves the hash.
        assertEquals(goldenHash(), first);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < COMPILE_RUNS; i++) {
            seen.add(GoldenHutTest.compileHut(plan).manifest().hash());
        }
        assertEquals(Set.of(first), seen);
    }

    @Test
    void shufflingTheHutsNodeOrderKeepsTheHash() throws IOException {
        SemanticPlan hut = GoldenHutTest.hut();
        PlacementManifest baseline = GoldenHutTest.compileHut(hut).manifest();
        for (long seed = 0; seed < HUT_SHUFFLES; seed++) {
            PlacementManifest shuffled = GoldenHutTest.compileHut(withShuffledNodes(hut, seed)).manifest();
            assertEquals(baseline.hash(), shuffled.hash(), "hut shuffle seed " + seed);
            assertEquals(baseline.placements(), shuffled.placements(), "hut shuffle seed " + seed);
        }
    }

    @Test
    void shufflingTheShowcaseNodeOrderKeepsTheHashInEveryFacing() {
        for (Facing facing : Facing.values()) {
            SemanticPlan showcase = ShowcasePlans.showcase(SHOWCASE_ROOF, facing);
            PlacementManifest baseline = CompileFixtures.compile(showcase).manifest();
            for (long seed = 0; seed < SHOWCASE_SHUFFLES; seed++) {
                PlacementManifest shuffled = CompileFixtures.compile(withShuffledNodes(showcase, seed)).manifest();
                // the hash alone would also pass for a mutant that swaps same-depth node ids; compare the full
                // placement list, which carries partNodeId
                assertEquals(baseline.hash(), shuffled.hash(), facing + " shuffle seed " + seed);
                assertEquals(baseline.placements(), shuffled.placements(), facing + " shuffle seed " + seed);
            }
        }
    }

    /** The hash stored in the first line ("hash <hex>") of the golden manifest. */
    private static String goldenHash() throws IOException {
        String firstLine = GoldenHutTest.resource(GoldenHutTest.HUT_MANIFEST).lines().findFirst().orElse("");
        assertTrue(firstLine.startsWith(GoldenHutTest.HASH_PREFIX),
                "the golden manifest must start with '" + GoldenHutTest.HASH_PREFIX + "<hex>'");
        return firstLine.substring(GoldenHutTest.HASH_PREFIX.length());
    }

    /**
     * The same plan built through the patcher from the same nodes in a different order. The patcher refuses a
     * node whose parent or OnSurface anchor target is not in the plan yet, so the shuffled nodes are sorted by
     * dependency depth afterwards; the order inside one depth still changes with the seed.
     */
    private static SemanticPlan withShuffledNodes(SemanticPlan plan, long seed) {
        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode n : plan.nodes()) {
            byId.put(n.id(), n);
        }
        List<PlanNode> nodes = new ArrayList<>(plan.nodes());
        Collections.shuffle(nodes, new Random(seed));
        Map<String, Integer> memo = new HashMap<>();
        nodes.sort(Comparator.comparingInt(n -> depth(n, byId, memo)));
        return CompileFixtures.plan(plan.site(), plan.style(), nodes);
    }

    /** One more than the deepest node this node depends on (its parent or its OnSurface anchor target). */
    private static int depth(PlanNode node, Map<String, PlanNode> byId, Map<String, Integer> memo) {
        Integer cached = memo.get(node.id());
        if (cached != null) {
            return cached;
        }
        int d = 0;
        if (node.parent() != null && byId.containsKey(node.parent())) {
            d = depth(byId.get(node.parent()), byId, memo) + 1;
        }
        if (node.anchor() instanceof Anchor.OnSurface s && byId.containsKey(s.nodeId())) {
            d = Math.max(d, depth(byId.get(s.nodeId()), byId, memo) + 1);
        }
        memo.put(node.id(), d);
        return d;
    }
}
