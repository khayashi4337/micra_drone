package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerrainPrepTest {
    private static final Box BOX = new Box(-2, 55, -2, 4, 70, 4);

    @Test
    void nothingTouchesTheTerrainSoTheManifestIsReturnedAsIs() {
        PlacementManifest m = TestManifests.smallHut();
        TerrainResult r = TerrainPrep.apply(m, SiteSurvey.air(TestManifests.DIM, BOX));
        assertSame(m, r.manifest());
        assertEquals(new TerrainSummary(0, 0), r.summary());
        assertTrue(r.issues().isEmpty());
    }

    @Test
    void theFoundationSunkIntoTheGroundIsTerraformed() {
        PlacementManifest m = TestManifests.smallHut();
        TerrainResult r = TerrainPrep.apply(m, SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(9, 0), r.summary());
        assertEquals(m.placements().size(), r.manifest().placements().size());
        for (Placement p : r.manifest().placements()) {
            boolean underground = p.pos().y() <= 63;
            assertEquals(underground ? ReplacePolicy.TERRAFORM : ReplacePolicy.REPLACEABLE, p.replaces(), p.toString());
        }
        assertNotEquals(m.hash(), r.manifest().hash(), "the replace policy is part of the hash");
    }

    @Test
    void aHillInsideTheHutIsCutToAirFirst() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 65, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(27, 0), r.summary(), "25 placements in the ground plus the 2 interior cells");
        List<Placement> ps = r.manifest().placements();
        assertEquals(27, ps.size());
        assertEquals(new IntPos(1, 65, 1), ps.get(0).pos(), "cuts go top-down");
        assertEquals(new IntPos(1, 64, 1), ps.get(1).pos());
        for (int i = 0; i < 2; i++) {
            assertTrue(ps.get(i).block().isAir());
            assertEquals(BuildPhase.SITE_PREP, ps.get(i).phase());
            assertEquals(ReplacePolicy.TERRAFORM, ps.get(i).replaces());
            assertEquals(TerrainPrep.SITE_PREP_NODE, ps.get(i).partNodeId());
            assertEquals(i, ps.get(i).index());
        }
        assertEquals(List.of(new PhaseRange(BuildPhase.SITE_PREP, 0, 2), new PhaseRange(BuildPhase.STRUCTURE, 2, 27)),
                r.manifest().phases());
    }

    @Test
    void aValleyUnderTheFoundationIsFilledWithDirt() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 60, "minecraft:grass_block"));
        assertEquals(new TerrainSummary(0, 18), r.summary(), "y=61 and y=62 under each of the 9 columns");
        List<Placement> ps = r.manifest().placements();
        assertEquals(25 + 18, ps.size());
        assertEquals(TerrainPrep.FILL_BLOCK, ps.get(0).block().blockId());
        assertEquals(61, ps.get(0).pos().y());
        assertEquals(62, ps.get(17).pos().y());
        assertEquals(18, r.manifest().bom().get(TerrainPrep.FILL_BLOCK));
    }

    @Test
    void theResultIsDeterministic() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 60, "minecraft:grass_block").withColumn(1, 1, 66,
                "minecraft:stone");
        String first = TerrainPrep.apply(TestManifests.smallHut(), s).manifest().hash();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, TerrainPrep.apply(TestManifests.smallHut(), s).manifest().hash());
        }
    }

    @Test
    void fillBelowTheSiteAndUnsurveyedColumnsAreRefused() {
        PlacementManifest tight = TestManifests.of(new Box(-2, 62, -2, 4, 70, 4), TestManifests.smallHut().placements());
        TerrainResult low = TerrainPrep.apply(tight, SiteSurvey.flat(TestManifests.DIM, new Box(-2, 62, -2, 4, 70, 4), 59,
                "minecraft:grass_block"));
        assertNull(low.manifest());
        assertEquals(IssueCode.E_OUT_OF_BOUNDS, low.issues().get(0).code());
        assertTrue(low.issues().get(0).id().endsWith("#terrain"));
        TerrainResult narrow = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, new Box(0, 55, 0, 1, 70, 1), 60, "minecraft:grass_block"));
        assertNull(narrow.manifest());
        assertTrue(narrow.issues().get(0).id().endsWith("#survey"));
    }

    @Test
    void aSurveyOfAnotherDimensionIsABug() {
        assertThrows(IllegalArgumentException.class, () -> TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.air("minecraft:the_nether", BOX)));
    }

    @Test
    void theGoldenHutOverAnAirSurveyKeepsTheGoldenHash() {
        PlacementManifest hut = TestManifests.hut();
        SiteSurvey air = SiteSurvey.air(hut.dimension(), hut.worldBounds());
        assertEquals(hut.hash(), TerrainPrep.apply(hut, air).manifest().hash());
    }

    /** Applies the SITE_PREP placements in manifest order to a column world where sand and gravel fall into air below. */
    private static Map<IntPos, String> applyWithGravity(PlacementManifest m, Map<IntPos, String> world) {
        for (Placement p : m.placements()) {
            if (p.phase() != BuildPhase.SITE_PREP) {
                continue;
            }
            world.put(p.pos(), p.block().blockId());
            IntPos hole = p.pos();
            IntPos above = hole.plus(0, 1, 0);
            while (p.block().isAir() && GRAVITY.contains(world.getOrDefault(above, AIR_ID))) {
                world.put(hole, world.get(above));
                world.put(above, AIR_ID);
                hole = above;
                above = above.plus(0, 1, 0);
            }
        }
        return world;
    }

    private static final String AIR_ID = "minecraft:air";
    private static final java.util.Set<String> GRAVITY = java.util.Set.of("minecraft:sand", "minecraft:gravel");

    @Test
    void cutsGoTopDownSoSandAboveNeverFallsIntoAClearedCell() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 65, "minecraft:sand"));
        Map<IntPos, String> world = new HashMap<>();
        for (int y = 60; y <= 65; y++) {
            for (int z = 0; z <= 2; z++) {
                for (int x = 0; x <= 2; x++) {
                    world.put(new IntPos(x, y, z), "minecraft:sand");
                }
            }
        }
        applyWithGravity(r.manifest(), world);
        for (Placement p : r.manifest().placements()) {
            if (p.phase() == BuildPhase.SITE_PREP && p.block().isAir()) {
                assertEquals(AIR_ID, world.get(p.pos()), "a cleared cell stays clear: " + p.pos());
            }
        }
    }

    @Test
    void aHillTallerThanTheBuildingIsCutToItsSurface() {
        TerrainResult r = TerrainPrep.apply(TestManifests.smallHut(),
                SiteSurvey.flat(TestManifests.DIM, BOX, 68, "minecraft:stone"));
        assertEquals(new TerrainSummary(25 + 29, 0), r.summary(),
                "25 sunk placements; cut: the interior column 64..68 and 66..68 over the 8 ring columns");
        java.util.Set<IntPos> cut = new java.util.HashSet<>();
        for (Placement p : r.manifest().placements()) {
            if (p.phase() == BuildPhase.SITE_PREP) {
                cut.add(p.pos());
            }
        }
        assertTrue(cut.contains(new IntPos(0, 68, 0)), "no ground is left hanging over the roof");
        assertTrue(cut.contains(new IntPos(1, 64, 1)));
    }

    @Test
    void groundCutAboveTheSiteIsRefusedLikeFillBelowIt() {
        PlacementManifest low = TestManifests.of(new Box(-2, 55, -2, 4, 67, 4), TestManifests.smallHut().placements());
        TerrainResult r = TerrainPrep.apply(low, SiteSurvey.flat(TestManifests.DIM, BOX, 68, "minecraft:stone"));
        assertNull(r.manifest(), "the ground reaches y=68, the site only y=67");
        assertTrue(r.issues().get(0).id().endsWith("#terrain"));
    }
}
