package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RotationInvarianceTest {
    private static final IntPos ORIGIN = new IntPos(1000, 64, -2000);
    /** Local bounds for the random parts: u in [-50, 6000], v and w within +/-50. */
    private static final Box WIDE = new Box(-50, -50, -50, 6000, 200, 50);
    private static final int QUARTER_TURNS = 4;
    /** Fixed seeds only: the test must be reproducible. */
    private static final long FIRST_SEED = 1;
    private static final long LAST_SEED = 20;

    private static PlacementManifest compileFacing(List<PlanNode> nodes, Facing facing, Box bounds) {
        Site site = new Site("minecraft:overworld", new BuildFrame(ORIGIN, facing), bounds, "", "");
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(site, StyleSpec.EMPTY, nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return r.manifest();
    }

    /** Clockwise turn of a world offset seen from above: (dx, dz) becomes (-dz, dx). */
    private static IntPos turn(IntPos p, int q, IntPos center) {
        int dx = p.x() - center.x();
        int dz = p.z() - center.z();
        for (int i = 0; i < q; i++) {
            int nx = -dz;
            int nz = dx;
            dx = nx;
            dz = nz;
        }
        return new IntPos(center.x() + dx, p.y(), center.z() + dz);
    }

    private static void assertRotationOf(PlacementManifest north, PlacementManifest turned, int q, IntPos center,
                                         String label) {
        assertEquals(north.placements().size(), turned.placements().size(), label);
        Map<IntPos, Placement> byPos = new HashMap<>();
        for (Placement p : turned.placements()) {
            byPos.put(p.pos(), p);
        }
        for (Placement p : north.placements()) {
            Placement other = byPos.get(turn(p.pos(), q, center));
            assertTrue(other != null, label + ": no cell at the turned position of " + p.pos());
            assertEquals(BlockRotation.rotate(p.block(), q), other.block(), label + " at " + p.pos());
            assertEquals(p.blockEntityConfig(), other.blockEntityConfig(), label);
            assertEquals(p.verify(), other.verify(), label);
            assertEquals(p.phase(), other.phase(), label);
        }
    }

    @Test
    void theHutTurnedInFourWaysIsTheSameHutTurned() throws IOException {
        SemanticPlan hut = GoldenHutTest.hut();
        PlacementManifest north = CompileFixtures.compile(planAt(hut, Facing.NORTH)).manifest();
        for (int q = 1; q < QUARTER_TURNS; q++) {
            Facing f = Facing.values()[q];
            assertRotationOf(north, CompileFixtures.compile(planAt(hut, f)).manifest(), q, north.frame().origin(),
                    "hut " + f);
        }
    }

    private static SemanticPlan planAt(SemanticPlan hut, Facing facing) {
        Site s = hut.site();
        return new SemanticPlan(hut.schemaVersion(), hut.planId(), hut.revision(), hut.parentRevision(),
                new Site(s.dimension(), new BuildFrame(s.frame().origin(), facing), s.localBounds(), "", ""),
                hut.style(), hut.nodes(), hut.connections(), hut.logistics(), hut.provenance());
    }

    @Test
    void everyVariantOfTheBuildingPartsTurnsCleanly() {
        for (String roof : ShowcasePlans.ROOFS) {
            CompileResult north = CompileFixtures.compile(ShowcasePlans.showcase(roof, Facing.NORTH));
            assertTrue(north.issues().isEmpty(), roof + ": " + north.issues());
            for (int q = 1; q < QUARTER_TURNS; q++) {
                Facing f = Facing.values()[q];
                CompileResult turned = CompileFixtures.compile(ShowcasePlans.showcase(roof, f));
                assertTrue(turned.issues().isEmpty(), roof + " " + f + ": " + turned.issues());
                assertRotationOf(north.manifest(), turned.manifest(), q, CompileFixtures.ORIGIN, roof + " " + f);
            }
        }
    }

    @Test
    void theShowcaseAndTheRandomPartsTogetherCoverEveryBuildingPart() {
        Set<String> covered = new TreeSet<>(RandomParts.FREESTANDING);
        for (PlanNode n : ShowcasePlans.showcase("gable", Facing.NORTH).nodes()) {
            covered.add(n.type());
        }
        assertEquals(new TreeSet<>(PartGenerators.ids()), covered);
    }

    @Test
    void everyFreestandingPartWithRandomParametersAndRotationsTurnsCleanly() {
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            List<PlanNode> nodes = RandomParts.nodes(seed);
            PlacementManifest north = compileFacing(nodes, Facing.NORTH, WIDE);
            assertTrue(north.placements().size() > 0);
            for (int q = 1; q < QUARTER_TURNS; q++) {
                assertRotationOf(north, compileFacing(nodes, Facing.values()[q], WIDE), q, ORIGIN,
                        "seed " + seed + " facing " + Facing.values()[q]);
            }
        }
    }
}
