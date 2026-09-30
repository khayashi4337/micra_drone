package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.ReplacePolicy;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SnapshotDiffTest {
    private static final Function<String, Set<String>> NONE = node -> Set.of();

    static Map<IntPos, ObservedBlock> asBuilt(PlacementManifest m) {
        Map<IntPos, ObservedBlock> out = new HashMap<>();
        for (Placement p : m.placements()) {
            out.put(p.pos(), new ObservedBlock(p.block()));
        }
        return out;
    }

    private static List<DeviationKind> kinds(SnapshotDiff.Result r) {
        return r.deviations().stream().map(Deviation::kind).toList();
    }

    @Test
    void theGoldenHutAsBuiltHasNoDeviation() {
        PlacementManifest hut = TestManifests.hut();
        SnapshotDiff.Result r = SnapshotDiff.compare(hut, new SparseSnapshot(asBuilt(hut)), CompareScope.ALL, NONE);
        assertEquals(List.of(), r.deviations());
        assertEquals(List.of(), r.unread());
    }

    @Test
    void everyKindIsFound() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(1, 64, 0), BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(2, 64, 0), BlockSpec.of("minecraft:oak_stairs", "facing", "north"), "w",
                        BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET),
                TestManifests.put(new IntPos(3, 64, 0), BlockSpec.AIR, "w", BuildPhase.ENVELOPE, VerifyMode.EXACT));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 4, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = asBuilt(m);
        world.put(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.AIR));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:gold_block")));
        world.put(new IntPos(2, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:oak_stairs", "facing", "east", "shape", "straight")));
        world.put(new IntPos(3, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:dirt")));
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(DeviationKind.MISSING, DeviationKind.WRONG_BLOCK, DeviationKind.WRONG_STATE, DeviationKind.EXTRA),
                kinds(r));
        assertEquals(List.of(0, 1, 2, 3), r.deviations().stream().map(Deviation::placementIndex).toList());
    }

    @Test
    void volatileStatesAndBlockOnlyAreNotCompared() {
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), BlockSpec.of("minecraft:oak_door", "open", "false", "facing", "south"),
                        "door-1", BuildPhase.ENVELOPE, VerifyMode.STATE_SUBSET),
                TestManifests.put(new IntPos(1, 64, 0), BlockSpec.of("minecraft:glass_pane", "north", "true"), "win-1",
                        BuildPhase.ENVELOPE, VerifyMode.BLOCK_ONLY));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = new HashMap<>();
        world.put(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:oak_door", "open", "true", "facing", "south")));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(BlockSpec.of("minecraft:glass_pane", "north", "false")));
        Function<String, Set<String>> doorVolatile = node -> node.equals("door-1") ? Set.of("open", "powered") : Set.of();
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, doorVolatile).deviations());
    }

    @Test
    void theScopeKeepsUnbuiltPositionsFromCountingAsMissing() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, ObservedBlock> half = new HashMap<>();
        for (Placement p : m.placements()) {
            half.put(p.pos(), new ObservedBlock(p.index() < 10 ? p.block() : BlockSpec.AIR));
        }
        SparseSnapshot s = new SparseSnapshot(half);
        assertEquals(List.of(), SnapshotDiff.compare(m, s, new CompareScope.UpToCursor(10), NONE).deviations());
        assertEquals(15, SnapshotDiff.compare(m, s, CompareScope.ALL, NONE).deviations().size());
        assertEquals(5, SnapshotDiff.compare(m, s, new CompareScope.IndexRange(10, 15), NONE).deviations().size());
        assertEquals(0, SnapshotDiff.compare(m, s, new CompareScope.Phases(Set.of(BuildPhase.DECORATION)), NONE).deviations().size());
    }

    @Test
    void unreadPositionsAreNotMissing() {
        PlacementManifest m = TestManifests.smallHut();
        Map<IntPos, ObservedBlock> world = asBuilt(m);
        world.remove(m.placements().get(4).pos());
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(), r.deviations());
        assertEquals(List.of(4), r.unread());
    }

    @Test
    void onlyTheLastPlacementAtASharedPositionIsCompared() {
        IntPos pos = new IntPos(0, 64, 0);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(
                TestManifests.put(pos, BlockSpec.AIR, "site", BuildPhase.SITE_PREP, VerifyMode.EXACT),
                TestManifests.put(pos, BlockSpec.of("minecraft:stone"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT)));
        // the finished world shows the stone: the site-prep cut it was built over is not a deviation
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(Map.of(pos,
                new ObservedBlock(BlockSpec.of("minecraft:stone")))), CompareScope.ALL, NONE).deviations());
        SnapshotDiff.Result missing = SnapshotDiff.compare(m, new SparseSnapshot(Map.of(pos,
                new ObservedBlock(BlockSpec.AIR))), CompareScope.ALL, NONE);
        assertEquals(List.of(DeviationKind.MISSING), kinds(missing));
        assertEquals(List.of(1), missing.deviations().stream().map(Deviation::placementIndex).toList(),
                "the deviation names the last placement's index, not the superseded cut's");
        SnapshotDiff.Result wrong = SnapshotDiff.compare(m, new SparseSnapshot(Map.of(pos,
                new ObservedBlock(BlockSpec.of("minecraft:dirt")))), CompareScope.ALL, NONE);
        assertEquals(List.of(DeviationKind.WRONG_BLOCK), kinds(wrong));
        assertEquals(List.of(1), wrong.deviations().stream().map(Deviation::placementIndex).toList());
    }

    @Test
    void aScopeSeesOnlyTheLastPlacementInsideIt() {
        IntPos pos = new IntPos(0, 64, 0);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(
                TestManifests.put(pos, BlockSpec.AIR, "site", BuildPhase.SITE_PREP, VerifyMode.EXACT),
                TestManifests.put(pos, BlockSpec.of("minecraft:stone"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT)));
        CompareScope upToTheCut = new CompareScope.UpToCursor(1);
        // the stone is not built yet: the cut alone decides what the position should look like now
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(Map.of(pos,
                new ObservedBlock(BlockSpec.AIR))), upToTheCut, NONE).deviations());
        assertEquals(List.of(DeviationKind.EXTRA), kinds(SnapshotDiff.compare(m, new SparseSnapshot(Map.of(pos,
                new ObservedBlock(BlockSpec.of("minecraft:stone")))), upToTheCut, NONE)));
    }

    @Test
    void deviationsComeInIndexOrderAcrossSharedPositions() {
        IntPos p = new IntPos(0, 64, 0);
        IntPos q = new IntPos(1, 64, 0);
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), List.of(
                TestManifests.put(p, BlockSpec.AIR, "site", BuildPhase.SITE_PREP, VerifyMode.EXACT),
                TestManifests.put(q, BlockSpec.of("minecraft:oak_planks"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(p, BlockSpec.of("minecraft:stone"), "w", BuildPhase.STRUCTURE, VerifyMode.EXACT)));
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(Map.of(
                p, new ObservedBlock(BlockSpec.AIR), q, new ObservedBlock(BlockSpec.AIR))), CompareScope.ALL, NONE);
        assertEquals(List.of(1, 2), r.deviations().stream().map(Deviation::placementIndex).toList(),
                "one deviation per position, ordered by the placement index that ends up there");
        assertEquals(List.of(DeviationKind.MISSING, DeviationKind.MISSING), kinds(r));
    }

    @Test
    void anAssembledAwayPlacementWithoutAGroupIsCheckedNormally() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(TestManifests.put(new IntPos(0, 64, 0),
                BlockSpec.of("minecraft:white_wool"), "sail", BuildPhase.ASSEMBLE, VerifyMode.ASSEMBLED_AWAY)));
        Map<IntPos, ObservedBlock> air = Map.of(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.AIR));
        Map<IntPos, ObservedBlock> wool = Map.of(new IntPos(0, 64, 0),
                new ObservedBlock(BlockSpec.of("minecraft:white_wool")));
        // a group-less placement is never assembled, so its block must stand in the world
        assertEquals(List.of(DeviationKind.MISSING), kinds(SnapshotDiff.compare(m, new SparseSnapshot(air),
                CompareScope.ALL, NONE)));
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(wool), CompareScope.ALL, NONE).deviations());
    }

    @Test
    void anUnassembledGroupIsVerifiedLikeAnyOtherPlacement() {
        IntPos pos = new IntPos(0, 64, 0);
        Placement sail = new Placement(0, pos, BlockSpec.of("minecraft:white_wool"), Map.of(), "sail",
                BuildPhase.ASSEMBLE, PlacerId.SIMPLE, VerifyMode.ASSEMBLED_AWAY, ReplacePolicy.REPLACEABLE, "rig-1");
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(sail));
        SparseSnapshot air = new SparseSnapshot(Map.of(pos, new ObservedBlock(BlockSpec.AIR)));
        SparseSnapshot wool = new SparseSnapshot(Map.of(pos, new ObservedBlock(BlockSpec.of("minecraft:white_wool"))));
        assertEquals(List.of(DeviationKind.MISSING), kinds(SnapshotDiff.compare(m, air, CompareScope.ALL, NONE,
                Set.of("other-group"))), "before the assembly the block must be in the world");
        assertEquals(List.of(), SnapshotDiff.compare(m, wool, CompareScope.ALL, NONE, Set.of()).deviations());
        // the old four-argument form means "nothing assembled yet"
        assertEquals(List.of(DeviationKind.MISSING), kinds(SnapshotDiff.compare(m, air, CompareScope.ALL, NONE)));
    }

    @Test
    void anAssembledGroupExpectsThePositionToBeEmptyAgain() {
        IntPos pos = new IntPos(0, 64, 0);
        Placement sail = new Placement(0, pos, BlockSpec.of("minecraft:white_wool"), Map.of(), "sail",
                BuildPhase.ASSEMBLE, PlacerId.SIMPLE, VerifyMode.ASSEMBLED_AWAY, ReplacePolicy.REPLACEABLE, "rig-1");
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(sail));
        SparseSnapshot air = new SparseSnapshot(Map.of(pos, new ObservedBlock(BlockSpec.AIR)));
        SparseSnapshot wool = new SparseSnapshot(Map.of(pos, new ObservedBlock(BlockSpec.of("minecraft:white_wool"))));
        assertEquals(List.of(), SnapshotDiff.compare(m, air, CompareScope.ALL, NONE, Set.of("rig-1")).deviations(),
                "the assembly moved the block into its contraption: air is right");
        assertEquals(List.of(DeviationKind.EXTRA), kinds(SnapshotDiff.compare(m, wool, CompareScope.ALL, NONE,
                Set.of("rig-1"))), "a leftover block the assembly did not take is EXTRA");
    }

    @Test
    void exactComparesEveryStateButStateSubsetOnlyTheListedOnes() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "y");
        List<Placement> ps = List.of(
                TestManifests.put(new IntPos(0, 64, 0), log, "w", BuildPhase.STRUCTURE, VerifyMode.EXACT),
                TestManifests.put(new IntPos(1, 64, 0), log, "w", BuildPhase.STRUCTURE, VerifyMode.STATE_SUBSET));
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 2, 70, 1), ps);
        Map<IntPos, ObservedBlock> world = new HashMap<>();
        world.put(new IntPos(0, 64, 0), new ObservedBlock(log.with("waterlogged", "true")));
        world.put(new IntPos(1, 64, 0), new ObservedBlock(log.with("waterlogged", "true")));
        SnapshotDiff.Result r = SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE);
        assertEquals(List.of(0), r.deviations().stream().map(Deviation::placementIndex).toList(),
                "an unlisted observed state fails EXACT only");
        assertEquals(List.of(DeviationKind.WRONG_STATE), kinds(r));
    }

    @Test
    void theSnapshotIsBounded() {
        Map<IntPos, ObservedBlock> tooMany = new HashMap<>();
        for (int i = 0; i <= SparseSnapshot.MAX_POSITIONS; i++) {
            tooMany.put(new IntPos(i, 0, 0), new ObservedBlock(BlockSpec.AIR));
        }
        assertThrows(IllegalArgumentException.class, () -> new SparseSnapshot(tooMany));
    }
}
