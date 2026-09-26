package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDifferTest {
    private static Placement at(int index, int x, int y, int z, BlockSpec block) {
        return new Placement(index, new IntPos(x, y, z), block, Map.of(), "n", BuildPhase.STRUCTURE, PlacerId.SIMPLE,
                VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
    }

    private static PlacementManifest manifest(String dimension, String hash, Placement... ps) {
        List<Placement> list = new ArrayList<>(List.of(ps));
        return new PlacementManifest(1, "p", 1, "v", dimension, new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH),
                new Box(0, 0, 0, 10, 10, 10), list, List.of(), Map.of(), List.of(), hash);
    }

    private static final BlockSpec STONE = BlockSpec.of("minecraft:stone");
    private static final BlockSpec BRICKS = BlockSpec.of("minecraft:bricks");

    @Test
    void removalsAdditionsChangesAndUnchangedAreSeparated() {
        PlacementManifest from = manifest("minecraft:overworld", "h1",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, STONE), at(3, 0, 5, 0, STONE));
        PlacementManifest to = manifest("minecraft:overworld", "h2",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, BRICKS), at(2, 9, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, pos -> BlockSpec.AIR);
        assertEquals("h1", d.fromHash());
        assertEquals("h2", d.toHash());
        assertEquals(1, d.unchanged());
        assertEquals(List.of(new IntPos(0, 5, 0), new IntPos(2, 0, 0)), d.removals().stream().map(r -> r.old().pos()).toList(),
                "removals go from the top down");
        assertEquals(List.of(new IntPos(9, 0, 0)), d.additions().stream().map(Placement::pos).toList());
        assertEquals(1, d.changes().size());
        assertEquals(BRICKS, d.changes().get(0).now().block());
        assertEquals(STONE, d.changes().get(0).expectedNow(), "what the old manifest expects to find now");
    }

    @Test
    void aRemovalCarriesWhatIsExpectedNowAndWhatToRestore() {
        PlacementManifest from = manifest("minecraft:overworld", "h1", at(0, 4, 4, 4, STONE));
        PlacementManifest to = manifest("minecraft:overworld", "h2");
        BlockSpec dirt = BlockSpec.of("minecraft:dirt");
        ManifestDiff withRecord = ManifestDiffer.diff(from, to, pos -> pos.equals(new IntPos(4, 4, 4)) ? dirt : BlockSpec.AIR);
        assertEquals(STONE, withRecord.removals().get(0).expectedNow());
        assertEquals(dirt, withRecord.removals().get(0).restoreTo());
        ManifestDiff noRecord = ManifestDiffer.diff(from, to, pos -> null);
        assertEquals(BlockSpec.AIR, noRecord.removals().get(0).restoreTo(), "no record means air");
    }

    @Test
    void removalOrderIsTopDownThenZThenX() {
        PlacementManifest from = manifest("minecraft:overworld", "h1", at(0, 5, 1, 1, STONE), at(1, 1, 1, 2, STONE),
                at(2, 2, 1, 1, STONE), at(3, 0, 9, 0, STONE));
        List<IntPos> order = ManifestDiffer.diff(from, manifest("minecraft:overworld", "h2"), p -> BlockSpec.AIR).removals().stream()
                .map(r -> r.old().pos()).toList();
        assertEquals(List.of(new IntPos(0, 9, 0), new IntPos(2, 1, 1), new IntPos(5, 1, 1), new IntPos(1, 1, 2)), order);
    }

    @Test
    void theSamePlacementsInADifferentIndexOrderAreUnchanged() {
        PlacementManifest from = manifest("minecraft:overworld", "h1",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, BRICKS));
        PlacementManifest to = manifest("minecraft:overworld", "h2",
                at(0, 2, 0, 0, BRICKS), at(1, 0, 0, 0, STONE), at(2, 1, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty() && d.changes().isEmpty(),
                "matching is by position, not by index");
        assertEquals(3, d.unchanged());
    }

    @Test
    void additionsAndChangesFollowTheNewManifestIndexOrder() {
        PlacementManifest from = manifest("minecraft:overworld", "h1",
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, STONE), at(3, 3, 0, 0, STONE));
        // the new manifest deliberately lists its placements in an order unrelated to the old one
        PlacementManifest to = manifest("minecraft:overworld", "h2",
                at(0, 5, 0, 0, STONE), at(1, 2, 0, 0, BRICKS), at(2, 1, 0, 0, STONE),
                at(3, 7, 0, 0, STONE), at(4, 0, 0, 0, BRICKS), at(5, 3, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty());
        assertEquals(List.of(new IntPos(5, 0, 0), new IntPos(7, 0, 0)),
                d.additions().stream().map(Placement::pos).toList(), "additions keep the new index order");
        assertEquals(List.of(new IntPos(2, 0, 0), new IntPos(0, 0, 0)),
                d.changes().stream().map(c -> c.now().pos()).toList(), "changes keep the new index order");
        assertEquals(2, d.unchanged());
    }

    @Test
    void blockEntityConfigCountsAsAChange() {
        Placement plain = at(0, 0, 0, 0, STONE);
        Placement configured = new Placement(0, new IntPos(0, 0, 0), STONE, Map.of("line1", "x"), "n", BuildPhase.STRUCTURE,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
        ManifestDiff d = ManifestDiffer.diff(manifest("minecraft:overworld", "h1", plain), manifest("minecraft:overworld", "h2", configured), p -> BlockSpec.AIR);
        assertEquals(1, d.changes().size());
    }

    @Test
    void differentDimensionsCannotBeDiffed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ManifestDiffer.diff(manifest("minecraft:overworld", "h1"),
                        manifest("minecraft:the_nether", "h2"), p -> BlockSpec.AIR));
        assertTrue(ex.getMessage().contains("different dimensions"), "the message must name the cause");
    }

    @Test
    void identicalManifestsHaveNothingToDo() {
        PlacementManifest m = manifest("minecraft:overworld", "h", at(0, 0, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(m, m, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty() && d.changes().isEmpty());
        assertEquals(1, d.unchanged());
    }
}
