package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TerrainSummary;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SafetyEnvelopeTest {
    private static final SafetyLimits LIMITS = SafetyLimits.defaults(-64, 320);
    private static final TerrainSummary NO_TERRAIN = new TerrainSummary(0, 0);

    /** Every placement position reads as air unless overridden. */
    private static PlacementSurvey airAt(PlacementManifest m, Map<IntPos, WorldCell> overrides) {
        Map<IntPos, WorldCell> cells = new HashMap<>();
        for (Placement p : m.placements()) {
            cells.put(p.pos(), WorldCell.of(BlockSpec.AIR, CellTrait.REPLACEABLE));
        }
        cells.putAll(overrides);
        return new PlacementSurvey(cells);
    }

    private static SafetyReport check(PlacementManifest m, PlacementSurvey s) {
        return SafetyEnvelope.check(m, NO_TERRAIN, s, LIMITS, PlaceableBlockPolicy.builtin(), ItemCatalog.ANY, pos -> Optional.empty());
    }

    private static List<String> ids(SafetyReport r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void aCleanHutPassesWithNothingToConfirm() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyReport r = check(m, airAt(m, Map.of()));
        assertEquals(List.of(), r.issues());
        assertFalse(r.replacements().needsDestructiveConfirm());
        assertFalse(r.replacements().needsTerraformConfirm());
    }

    @Test
    void tooManyTooBigAndTooHighAreRefusedBeforeApproval() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyLimits tiny = new SafetyLimits(3, 2, 2, 2, 64, 320);
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, Map.of()), tiny, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY, pos -> Optional.empty());
        assertEquals(List.of("E-OUT-OF-BOUNDS:manifest#placements", "E-OUT-OF-BOUNDS:manifest#size",
                "E-OUT-OF-BOUNDS:found#height"), ids(r));
        assertEquals(List.of("found"), r.issues().get(2).subjects(), "the issue names the part that is out of range");
        assertEquals("9", r.issues().get(2).data().get("count"), "the 9 foundation cells lie below y=64");
    }

    @Test
    void blockedPositionsAreGroupedPerPartAndReason() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> stone = new HashMap<>();
        stone.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE));
        stone.put(new IntPos(2, 64, 0), WorldCell.of(BlockSpec.of("minecraft:stone"), CellTrait.TERRAFORMABLE));
        stone.put(new IntPos(0, 63, 0), WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest"));
        SafetyReport r = check(m, airAt(m, stone));
        assertEquals(List.of("E-SITE-BLOCKED:found#foreign_block_entity", "E-SITE-BLOCKED:wall#not_replaceable"), ids(r));
        Issue walls = r.issues().get(1);
        assertEquals("2", walls.data().get("count"));
        assertEquals("0,64,0", walls.data().get("first"));
        assertFalse(walls.acceptable(), "E-SITE-BLOCKED can never be accepted");
    }

    @Test
    void fluidsLeavesAndEmptyContainersNeedTheDestructiveConfirmation() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> o = new HashMap<>();
        o.put(new IntPos(0, 63, 0), WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID, CellTrait.REPLACEABLE));
        o.put(new IntPos(1, 63, 0), WorldCell.of(BlockSpec.of("minecraft:oak_leaves"), CellTrait.LEAVES));
        o.put(new IntPos(2, 63, 0), WorldCell.withBlockEntity(BlockSpec.of("minecraft:chest"), "minecraft:chest",
                CellTrait.EMPTY_CONTAINER));
        SafetyReport r = check(m, airAt(m, o));
        assertEquals(List.of(), r.issues());
        assertEquals(1, r.replacements().fluids());
        assertEquals(1, r.replacements().leaves());
        assertEquals(1, r.replacements().emptyContainers());
        assertTrue(r.replacements().needsDestructiveConfirm());
        assertEquals(List.of(new IntPos(0, 63, 0), new IntPos(1, 63, 0), new IntPos(2, 63, 0)),
                r.replacements().destructiveSample());
    }

    @Test
    void theSampleOfDestructivePositionsIsCapped() {
        List<Placement> row = new ArrayList<>();
        Map<IntPos, WorldCell> water = new HashMap<>();
        for (int x = 0; x < 40; x++) {
            row.add(TestManifests.put(x, 64, 0, "minecraft:oak_planks"));
            water.put(new IntPos(x, 64, 0), WorldCell.of(BlockSpec.of("minecraft:water"), CellTrait.FLUID));
        }
        PlacementManifest m = TestManifests.of(new Box(0, 60, 0, 40, 70, 1), row);
        SafetyReport r = check(m, new PlacementSurvey(water));
        assertEquals(40, r.replacements().fluids());
        assertEquals(ReplacementSummary.SAMPLE_LIMIT, r.replacements().destructiveSample().size());
    }

    @Test
    void unloadedForbiddenAndUnknownItemsAreReported() {
        List<Placement> ps = List.of(TestManifests.put(0, 64, 0, "minecraft:bedrock"),
                TestManifests.put(1, 64, 0, "minecraft:oak_planks"));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 3, 70, 1), ps);
        Map<IntPos, WorldCell> cells = new HashMap<>();
        cells.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.AIR));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, new PlacementSurvey(cells), LIMITS, PlaceableBlockPolicy.builtin(),
                id -> !id.equals("minecraft:oak_planks"), pos -> Optional.empty());
        assertEquals(List.of("E-BLOCK-FORBIDDEN:wall#minecraft:bedrock", "E-MATERIAL-UNKNOWN:wall#minecraft:oak_planks",
                "E-SITE-BLOCKED:manifest#unloaded"), ids(r));
    }

    @Test
    void positionsTheProjectAlreadyPlacedAreOurs() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> planks = new HashMap<>();
        planks.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:oak_planks")));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, planks), LIMITS, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY,
                pos -> pos.equals(new IntPos(0, 64, 0)) ? Optional.of(BlockSpec.of("minecraft:oak_planks")) : Optional.empty());
        assertEquals(List.of(), r.issues());
    }

    @Test
    void terrainCountsAreCarriedToTheSummary() {
        PlacementManifest m = TestManifests.smallHut();
        SafetyReport r = SafetyEnvelope.check(m, new TerrainSummary(9, 18), airAt(m, Map.of()), LIMITS,
                PlaceableBlockPolicy.builtin(), ItemCatalog.ANY, pos -> Optional.empty());
        assertEquals(9, r.replacements().terrainCut());
        assertEquals(18, r.replacements().terrainFill());
        assertTrue(r.replacements().needsTerraformConfirm());
        assertEquals(IssueCode.E_SITE_BLOCKED.label(), "E-SITE-BLOCKED");
    }

    @Test
    void aPlacementOutsideTheSiteBoxIsRefusedBeforeTheSurveyIsAsked() {
        PlacementManifest hut = TestManifests.smallHut();
        List<Placement> ps = new ArrayList<>(hut.placements());
        ps.add(TestManifests.put(new IntPos(1000, 64, 1000), BlockSpec.of("minecraft:gold_block"), "tower",
                BuildPhase.STRUCTURE, VerifyMode.EXACT));
        PlacementManifest m = TestManifests.of(hut.worldBounds(), ps);
        // the survey holds no cell for the off-site position (a lookup would report it as unloaded), and the
        // catalog and ownership callbacks must never see an off-site position or the item of its block
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(hut, Map.of()), LIMITS, PlaceableBlockPolicy.builtin(),
                id -> {
                    if (id.equals("minecraft:gold_block")) {
                        throw new AssertionError("the item catalog was asked about an off-site block");
                    }
                    return true;
                },
                pos -> {
                    if (!hut.worldBounds().contains(pos.x(), pos.y(), pos.z())) {
                        throw new AssertionError("ownership was asked about an off-site position: " + pos);
                    }
                    return Optional.empty();
                });
        assertEquals(List.of("E-OUT-OF-BOUNDS:tower#bounds"), ids(r), "not an unread survey cell: a position off the site");
        assertEquals(List.of("tower"), r.issues().get(0).subjects());
        assertEquals("1", r.issues().get(0).data().get("count"));
        assertEquals("1000,64,1000", r.issues().get(0).data().get("first"));
    }

    @Test
    void positionsOutsideTheSiteNameTheirPartsInOrder() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), BlockSpec.of("minecraft:oak_planks"), "wall",
                        BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(0, 65, 0), BlockSpec.of("minecraft:oak_planks"), "roof",
                        BuildPhase.STRUCTURE, VerifyMode.EXACT));
        PlacementManifest m = TestManifests.of(new Box(10, 60, 10, 20, 70, 20), ps);
        SafetyReport r = check(m, new PlacementSurvey(Map.of()));
        assertEquals(List.of("E-OUT-OF-BOUNDS:roof,wall#bounds"), ids(r));
        Issue issue = r.issues().get(0);
        assertEquals(List.of("roof", "wall"), issue.subjects(), "distinct part ids, sorted");
        assertEquals("2", issue.data().get("count"), "the count is the number of positions, not of parts");
    }

    @Test
    void thePartListOfAnOutOfBoundsIssueIsCapped() {
        List<Placement> ps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ps.add(TestManifests.put(new IntPos(0, 64, i), BlockSpec.of("minecraft:oak_planks"),
                    String.format(Locale.ROOT, "p%02d", i), BuildPhase.STRUCTURE, VerifyMode.EXACT));
        }
        PlacementManifest m = TestManifests.of(new Box(10, 60, 10, 20, 70, 20), ps);
        SafetyReport r = check(m, new PlacementSurvey(Map.of()));
        Issue issue = r.issues().get(0);
        assertEquals(SafetyEnvelope.MAX_ISSUE_SUBJECTS, issue.subjects().size());
        assertEquals("p00", issue.subjects().get(0));
        assertEquals("p15", issue.subjects().get(SafetyEnvelope.MAX_ISSUE_SUBJECTS - 1));
        assertEquals("20", issue.data().get("count"), "count keeps the real number of positions");
    }

    @Test
    void positionsOutsideTheBuildHeightNameTheirPartsAndSkipTheQueries() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 400, 0), BlockSpec.of("minecraft:gold_block"), "wall",
                        BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(1, 400, 0), BlockSpec.of("minecraft:gold_block"), "antenna",
                        BuildPhase.STRUCTURE, VerifyMode.EXACT));
        PlacementManifest m = TestManifests.of(new Box(-1, 340, -1, 3, 435, 1), ps);
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, new PlacementSurvey(Map.of()), LIMITS,
                PlaceableBlockPolicy.builtin(),
                id -> {
                    throw new AssertionError("the item catalog was asked about an out-of-height block: " + id);
                },
                pos -> {
                    throw new AssertionError("ownership was asked about an out-of-height position: " + pos);
                });
        assertEquals(List.of("E-OUT-OF-BOUNDS:antenna,wall#height"), ids(r));
        assertEquals("2", r.issues().get(0).data().get("count"));
    }

    @Test
    void aRegisteredPositionNowHoldingSomeoneElsesBlockIsBlocked() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, WorldCell> gold = new HashMap<>();
        gold.put(new IntPos(0, 64, 0), WorldCell.of(BlockSpec.of("minecraft:gold_block")));
        SafetyReport r = SafetyEnvelope.check(m, NO_TERRAIN, airAt(m, gold), LIMITS, PlaceableBlockPolicy.builtin(),
                ItemCatalog.ANY,
                pos -> pos.equals(new IntPos(0, 64, 0)) ? Optional.of(BlockSpec.of("minecraft:oak_planks")) : Optional.empty());
        assertEquals(List.of("E-SITE-BLOCKED:wall#not_replaceable"), ids(r), "ownership needs our block to still be there");
    }
}
