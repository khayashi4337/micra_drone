package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
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
    void assembledAwayPlacementsAreNotCompared() {
        PlacementManifest m = TestManifests.of(new Box(-1, 60, -1, 1, 70, 1), List.of(TestManifests.put(new IntPos(0, 64, 0),
                BlockSpec.of("minecraft:white_wool"), "sail", BuildPhase.ASSEMBLE, VerifyMode.ASSEMBLED_AWAY)));
        Map<IntPos, ObservedBlock> world = Map.of(new IntPos(0, 64, 0), new ObservedBlock(BlockSpec.AIR));
        assertEquals(List.of(), SnapshotDiff.compare(m, new SparseSnapshot(world), CompareScope.ALL, NONE).deviations());
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
