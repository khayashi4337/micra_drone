package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
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
    /**
     * Where a part compiled alone sits: far enough out on u that a mirror cannot push it against the bounds
     * edge (the widest random footprint reaches less than 50 cells from the anchor).
     */
    private static final LocalPos LONE_ANCHOR = new LocalPos(200, 0, 0);
    private static final boolean[] MIRROR = {false, true};
    private static final String STAIRS = "micra:stairs";
    private static final String STAIRS_DIR = "dir";

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
            assertNotNull(other, label + ": no cell at the turned position of " + p.pos());
            assertEquals(BlockRotation.rotate(p.block(), q), other.block(), label + " at " + p.pos());
            assertEquals(p.blockEntityConfig(), other.blockEntityConfig(), label);
            assertEquals(p.verify(), other.verify(), label);
            assertEquals(p.phase(), other.phase(), label);
            assertEquals(p.partNodeId(), other.partNodeId(), label);
        }
    }

    @Test
    void theHutTurnedInFourWaysIsTheSameHutTurned() throws IOException {
        SemanticPlan hut = GoldenHutTest.hut();
        PlacementManifest north = GoldenHutTest.compileHut(planAt(hut, Facing.NORTH)).manifest();
        for (int q = 1; q < QUARTER_TURNS; q++) {
            Facing f = Facing.NORTH.rotate(q);
            assertRotationOf(north, GoldenHutTest.compileHut(planAt(hut, f)).manifest(), q, north.frame().origin(),
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
                Facing f = Facing.NORTH.rotate(q);
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
            assertFalse(north.placements().isEmpty());
            for (int q = 1; q < QUARTER_TURNS; q++) {
                Facing f = Facing.NORTH.rotate(q);
                assertRotationOf(north, compileFacing(nodes, f, WIDE), q, ORIGIN,
                        "seed " + seed + " facing " + f);
            }
        }
    }

    /**
     * The node's own Rot (mirror, then turns) drawn by RandomParts can never be checked by the facing tests
     * above: the same rot is applied in the north and the turned compile, so the site-turn relation holds
     * whether the node rot is right or wrong. Compiled alone on the north frame, a part's cells with a rot
     * must equal its Rot.NONE cells mirrored on u about the anchor (when the rot mirrors) and then turned
     * clockwise about it — the same (dx,dz) to (-dz,dx) per turn {@link #turn} uses, with block states as
     * {@link BlockRotation#transform} gives them.
     */
    @Test
    void everyFreestandingPartAloneMirrorsThenTurnsAboutItsAnchor() {
        IntPos anchor = new IntPos(ORIGIN.x() + LONE_ANCHOR.u(), ORIGIN.y() + LONE_ANCHOR.v(),
                ORIGIN.z() - LONE_ANCHOR.w());
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            for (PlanNode n : RandomParts.nodes(seed)) {
                PlacementManifest plain = compileFacing(List.of(alone(n, Rot.NONE)), Facing.NORTH, WIDE);
                assertFalse(plain.placements().isEmpty(), n.type() + " alone places nothing");
                for (int turns = 0; turns < QUARTER_TURNS; turns++) {
                    for (boolean mirror : MIRROR) {
                        Rot rot = new Rot(turns, mirror);
                        assertNodeRot(plain, compileFacing(List.of(alone(n, rot)), Facing.NORTH, WIDE), rot, anchor,
                                "seed " + seed + " " + n.type() + " " + rot);
                    }
                }
            }
        }
    }

    /**
     * The scrambled seeds of {@link RandomParts} must actually spread the draws: across the 20 seeds every
     * stairs direction and every node turn and mirror has to occur, or the property tests would miss cases.
     */
    @Test
    void theSeedsCoverEveryStairsDirectionAndEveryNodeTurn() {
        Set<String> dirs = new TreeSet<>();
        Set<String> wantDirs = new TreeSet<>();
        for (Facing f : Facing.values()) {
            wantDirs.add(f.lower());
        }
        Set<Integer> turns = new TreeSet<>();
        Set<Boolean> mirrors = new TreeSet<>();
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            for (PlanNode n : RandomParts.nodes(seed)) {
                if (n.anchor() instanceof Anchor.Absolute a) {
                    turns.add(a.rot().quarterTurns());
                    mirrors.add(a.rot().mirror());
                }
                if (n.type().equals(STAIRS)) {
                    dirs.add(((ParamValue.StrV) n.params().get(STAIRS_DIR)).value());
                }
            }
        }
        assertEquals(wantDirs, dirs);
        assertEquals(Set.of(0, 1, 2, 3), turns);
        assertEquals(Set.of(false, true), mirrors);
    }

    /** The same node as a one-node plan at {@link #LONE_ANCHOR} with the given rot. */
    private static PlanNode alone(PlanNode n, Rot rot) {
        return new PlanNode(n.id(), n.type(), n.parent(), new Anchor.Absolute(LONE_ANCHOR, rot), n.params(),
                n.tags(), n.label());
    }

    private static Set<BuildPhase> phasesOf(PlacementManifest m) {
        Set<BuildPhase> out = new TreeSet<>();
        for (Placement p : m.placements()) {
            out.add(p.phase());
        }
        return out;
    }

    /**
     * Every cell of {@code plain} must reappear in {@code moved} where the node rot puts it: u mirrored about
     * the anchor (world x on the north frame), then {@code rot.quarterTurns()} clockwise quarter turns about
     * it, with the block states {@link BlockRotation#transform} produces.
     */
    private static void assertNodeRot(PlacementManifest plain, PlacementManifest moved, Rot rot, IntPos anchor,
                                      String label) {
        assertEquals(plain.placements().size(), moved.placements().size(), label);
        assertEquals(phasesOf(plain), phasesOf(moved), label);
        Map<IntPos, Placement> byPos = new HashMap<>();
        for (Placement p : moved.placements()) {
            byPos.put(p.pos(), p);
        }
        for (Placement p : plain.placements()) {
            IntPos pos = p.pos();
            if (rot.mirror()) {
                pos = new IntPos(anchor.x() - (pos.x() - anchor.x()), pos.y(), pos.z());
            }
            pos = turn(pos, rot.quarterTurns(), anchor);
            Placement other = byPos.get(pos);
            assertNotNull(other, label + ": no cell at the mirrored/turned position of " + p.pos());
            assertEquals(BlockRotation.transform(p.block(), rot), other.block(), label + " at " + p.pos());
            assertEquals(p.blockEntityConfig(), other.blockEntityConfig(), label);
            assertEquals(p.verify(), other.verify(), label);
            assertEquals(p.phase(), other.phase(), label);
            assertEquals(p.partNodeId(), other.partNodeId(), label);
        }
    }
}
