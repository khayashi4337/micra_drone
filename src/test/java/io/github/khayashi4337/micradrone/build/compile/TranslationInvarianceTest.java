package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A structure that does not stand at the plan origin moves every cell of every generator by exactly the offset and
 * changes nothing else. Almost every other generator test builds at (0,0,0), where a generator that forgets to add
 * the parent's origin (or adds it twice) still passes; here each generator family is compiled at the origin and at an
 * offset that is non-zero on every axis and negative on one, and the two manifests are compared cell by cell.
 * Construction order is (phase, v, w, u), and a translation keeps that order, so the placements line up by index.
 */
class TranslationInvarianceTest {
    /** Non-zero on every axis; the negative w checks that a generator does not assume non-negative coordinates. */
    private static final LocalPos OFFSET = new LocalPos(5, 2, -3);
    private static final LocalPos NO_OFFSET = new LocalPos(0, 0, 0);
    /** Wide enough that neither the origin-0 nor the moved compile is cut by the site bounds. */
    private static final Box WIDE = new Box(-100, -100, -100, 6100, 300, 100);
    /** Fixed seeds only: the test must be reproducible. */
    private static final long FIRST_SEED = 1;
    private static final long LAST_SEED = 20;

    /** The plan with its facing and bounds replaced and every root node moved by {@code by}; children follow their parent. */
    private static SemanticPlan moved(SemanticPlan plan, Facing facing, LocalPos by) {
        List<PlanNode> nodes = new ArrayList<>();
        for (PlanNode n : plan.nodes()) {
            if (n.parent() == null && n.anchor() instanceof Anchor.Absolute a) {
                nodes.add(new PlanNode(n.id(), n.type(), null,
                        new Anchor.Absolute(a.pos().plus(by.u(), by.v(), by.w()), a.rot()), n.params(), n.tags(), n.label()));
            } else {
                nodes.add(n);
            }
        }
        Site s = plan.site();
        Site site = new Site(s.dimension(), new BuildFrame(s.frame().origin(), facing), WIDE, s.terrainDigest(), s.claimId());
        return new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(), site, plan.style(),
                nodes, plan.connections(), plan.logistics(), plan.provenance());
    }

    private static PlacementManifest compileMoved(SemanticPlan plan, Facing facing, LocalPos by, String label) {
        CompileResult r = CompileFixtures.compile(moved(plan, facing, by));
        assertTrue(r.issues().isEmpty(), label + ": " + r.issues());
        assertNotNull(r.manifest(), label);
        return r.manifest();
    }

    private static void assertMovesWithTheStructure(SemanticPlan plan, Facing facing, String label) {
        PlacementManifest base = compileMoved(plan, facing, NO_OFFSET, label + " at the origin");
        PlacementManifest shifted = compileMoved(plan, facing, OFFSET, label + " at " + OFFSET);
        assertMovedBy(base, shifted, OFFSET, label);
    }

    /** Every placement of {@code shifted} is the same-index placement of {@code base} moved by {@code by}, in everything else equal. */
    private static void assertMovedBy(PlacementManifest base, PlacementManifest shifted, LocalPos by, String label) {
        assertFalse(base.placements().isEmpty(), label + ": nothing was built, so nothing was compared");
        assertEquals(base.placements().size(), shifted.placements().size(), label);
        BuildFrame frame = base.frame();
        for (int i = 0; i < base.placements().size(); i++) {
            Placement a = base.placements().get(i);
            Placement b = shifted.placements().get(i);
            String at = label + " placement " + i + " (" + a.partNodeId() + ")";
            assertEquals(frame.toLocal(a.pos()).plus(by.u(), by.v(), by.w()), frame.toLocal(b.pos()), at);
            assertEquals(a.block(), b.block(), at);
            assertEquals(a.blockEntityConfig(), b.blockEntityConfig(), at);
            assertEquals(a.partNodeId(), b.partNodeId(), at);
            assertEquals(a.phase(), b.phase(), at);
            assertEquals(a.placer(), b.placer(), at);
            assertEquals(a.verify(), b.verify(), at);
            assertEquals(a.replaces(), b.replaces(), at);
            assertEquals(a.assemblyGroup(), b.assemblyGroup(), at);
        }
        assertEquals(base.bom(), shifted.bom(), label);
        assertEquals(base.phases(), shifted.phases(), label);
    }

    @Test
    void theHutStandingAwayFromTheOriginIsTheSameHutMoved() throws IOException {
        SemanticPlan hut = GoldenHutTest.hut();
        for (Facing facing : Facing.values()) {
            assertMovesWithTheStructure(hut, facing, "hut " + facing);
        }
    }

    @Test
    void everyVariantOfTheBuildingPartsMovesWithItsStructure() {
        for (String roof : ShowcasePlans.ROOFS) {
            SemanticPlan showcase = ShowcasePlans.showcase(roof, Facing.NORTH);
            for (Facing facing : Facing.values()) {
                assertMovesWithTheStructure(showcase, facing, "showcase " + roof + " " + facing);
            }
        }
    }

    @Test
    void everyFreestandingPartWithRandomParametersMovesByTheOffset() {
        for (long seed = FIRST_SEED; seed <= LAST_SEED; seed++) {
            SemanticPlan parts = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, RandomParts.nodes(seed));
            for (Facing facing : Facing.values()) {
                assertMovesWithTheStructure(parts, facing, "seed " + seed + " " + facing);
            }
        }
    }

    @Test
    void theMovedPlansTogetherRunEveryGenerator() {
        Set<String> covered = new TreeSet<>(RandomParts.FREESTANDING);
        for (PlanNode n : ShowcasePlans.showcase("gable", Facing.NORTH).nodes()) {
            covered.add(n.type());
        }
        assertEquals(new TreeSet<>(PartGenerators.ids()), covered);
    }
}
