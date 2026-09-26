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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ManifestDifferTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String HASH_FROM = "h1";
    private static final String HASH_TO = "h2";
    private static final BlockSpec STONE = BlockSpec.of("minecraft:stone");
    private static final BlockSpec BRICKS = BlockSpec.of("minecraft:bricks");

    private static Placement at(int index, int x, int y, int z, BlockSpec block) {
        return new Placement(index, new IntPos(x, y, z), block, Map.of(), "n", BuildPhase.STRUCTURE, PlacerId.SIMPLE,
                VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
    }

    private static PlacementManifest manifest(String dimension, String hash, Placement... ps) {
        List<Placement> list = new ArrayList<>(List.of(ps));
        return new PlacementManifest(1, "p", 1, "v", dimension, new BuildFrame(new IntPos(0, 0, 0), Facing.NORTH),
                new Box(0, 0, 0, 10, 10, 10), list, List.of(), Map.of(), List.of(), hash);
    }

    @Test
    void removalsAdditionsChangesAndUnchangedAreSeparated() {
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM,
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, STONE), at(3, 0, 5, 0, STONE));
        PlacementManifest to = manifest(OVERWORLD, HASH_TO,
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, BRICKS), at(2, 9, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, pos -> BlockSpec.AIR);
        assertEquals(HASH_FROM, d.fromHash());
        assertEquals(HASH_TO, d.toHash());
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
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM, at(0, 4, 4, 4, STONE));
        PlacementManifest to = manifest(OVERWORLD, HASH_TO);
        BlockSpec dirt = BlockSpec.of("minecraft:dirt");
        ManifestDiff withRecord = ManifestDiffer.diff(from, to, pos -> pos.equals(new IntPos(4, 4, 4)) ? dirt : BlockSpec.AIR);
        assertEquals(STONE, withRecord.removals().get(0).expectedNow());
        assertEquals(dirt, withRecord.removals().get(0).restoreTo());
        ManifestDiff noRecord = ManifestDiffer.diff(from, to, pos -> null);
        assertEquals(BlockSpec.AIR, noRecord.removals().get(0).restoreTo(), "no record means air");
    }

    @Test
    void removalOrderIsTopDownThenZThenX() {
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM, at(0, 5, 1, 1, STONE), at(1, 1, 1, 2, STONE),
                at(2, 2, 1, 1, STONE), at(3, 0, 9, 0, STONE));
        List<IntPos> order = ManifestDiffer.diff(from, manifest(OVERWORLD, HASH_TO), p -> BlockSpec.AIR).removals().stream()
                .map(r -> r.old().pos()).toList();
        assertEquals(List.of(new IntPos(0, 9, 0), new IntPos(2, 1, 1), new IntPos(5, 1, 1), new IntPos(1, 1, 2)), order);
    }

    @Test
    void aRemovalAtTheLowestPossibleYStillSortsLast() {
        // -Integer.MIN_VALUE overflows back to MIN_VALUE, so a negated sort key would send it to the front
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM,
                at(0, 0, Integer.MIN_VALUE, 0, STONE), at(1, 0, 5, 0, STONE), at(2, 0, 1, 0, STONE));
        List<IntPos> order = ManifestDiffer.diff(from, manifest(OVERWORLD, HASH_TO), p -> BlockSpec.AIR).removals().stream()
                .map(r -> r.old().pos()).toList();
        assertEquals(List.of(new IntPos(0, 5, 0), new IntPos(0, 1, 0), new IntPos(0, Integer.MIN_VALUE, 0)), order);
    }

    @Test
    void theSamePlacementsInADifferentIndexOrderAreUnchanged() {
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM,
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, BRICKS));
        PlacementManifest to = manifest(OVERWORLD, HASH_TO,
                at(0, 2, 0, 0, BRICKS), at(1, 0, 0, 0, STONE), at(2, 1, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(from, to, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty() && d.changes().isEmpty(),
                "matching is by position, not by index");
        assertEquals(3, d.unchanged());
    }

    @Test
    void additionsAndChangesFollowTheNewManifestIndexOrder() {
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM,
                at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE), at(2, 2, 0, 0, STONE), at(3, 3, 0, 0, STONE));
        // the new manifest deliberately lists its placements in an order unrelated to the old one
        PlacementManifest to = manifest(OVERWORLD, HASH_TO,
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
    void theSameBlockIdWithDifferentPropertiesIsAChange() {
        Placement before = at(0, 0, 0, 0, BlockSpec.of("minecraft:oak_stairs", "facing", "north"));
        Placement after = at(0, 0, 0, 0, BlockSpec.of("minecraft:oak_stairs", "facing", "south"));
        ManifestDiff d = ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM, before),
                manifest(OVERWORLD, HASH_TO, after), p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty());
        assertEquals(1, d.changes().size());
        assertEquals(before, d.changes().get(0).old());
        assertEquals(after, d.changes().get(0).now());
        assertEquals(before.block(), d.changes().get(0).expectedNow());
        assertEquals(0, d.unchanged());
    }

    @Test
    void blockEntityConfigCountsAsAChange() {
        Placement plain = at(0, 0, 0, 0, STONE);
        Placement configured = new Placement(0, new IntPos(0, 0, 0), STONE, Map.of("line1", "x"), "n", BuildPhase.STRUCTURE,
                PlacerId.SIMPLE, VerifyMode.EXACT, ReplacePolicy.REPLACEABLE, null);
        ManifestDiff d = ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM, plain), manifest(OVERWORLD, HASH_TO, configured), p -> BlockSpec.AIR);
        assertEquals(1, d.changes().size());
    }

    @Test
    void anEmptyOldManifestMakesEverythingAnAddition() {
        PlacementManifest to = manifest(OVERWORLD, HASH_TO, at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, BRICKS));
        ManifestDiff d = ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM), to, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.changes().isEmpty());
        assertEquals(List.of(new IntPos(0, 0, 0), new IntPos(1, 0, 0)),
                d.additions().stream().map(Placement::pos).toList());
        assertEquals(0, d.unchanged());
    }

    @Test
    void restoreLookupIsOnlyAskedAboutRemovedPositions() {
        List<IntPos> asked = new ArrayList<>();
        Function<IntPos, BlockSpec> recording = pos -> {
            asked.add(pos);
            return BlockSpec.AIR;
        };
        PlacementManifest from = manifest(OVERWORLD, HASH_FROM, at(0, 0, 0, 0, STONE), at(1, 1, 0, 0, STONE));
        PlacementManifest to = manifest(OVERWORLD, HASH_TO, at(0, 0, 0, 0, STONE), at(1, 2, 0, 0, STONE));
        ManifestDiffer.diff(from, to, recording);
        assertEquals(List.of(new IntPos(1, 0, 0)), asked, "only the removed position needs a restore block");
    }

    @Test
    void differentDimensionsCannotBeDiffed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM),
                        manifest("minecraft:the_nether", HASH_TO), p -> BlockSpec.AIR));
        assertTrue(ex.getMessage().contains("different dimensions"), "the message must name the cause");
    }

    @Test
    void identicalManifestsHaveNothingToDo() {
        PlacementManifest m = manifest(OVERWORLD, "h", at(0, 0, 0, 0, STONE));
        ManifestDiff d = ManifestDiffer.diff(m, m, p -> BlockSpec.AIR);
        assertTrue(d.removals().isEmpty() && d.additions().isEmpty() && d.changes().isEmpty());
        assertEquals(1, d.unchanged());
    }

    @Test
    void mutatingTheInputListsAfterConstructionDoesNotChangeTheDiff() {
        List<RemovalEntry> removals = new ArrayList<>();
        List<Placement> additions = new ArrayList<>();
        List<PlacementChange> changes = new ArrayList<>();
        Placement p = at(0, 0, 0, 0, STONE);
        additions.add(p);
        ManifestDiff d = new ManifestDiff(HASH_FROM, HASH_TO, removals, additions, changes, 0);
        removals.add(new RemovalEntry(p, STONE, BlockSpec.AIR));
        additions.add(at(1, 1, 0, 0, BRICKS));
        changes.add(new PlacementChange(p, p, STONE));
        assertTrue(d.removals().isEmpty() && d.changes().isEmpty());
        assertEquals(List.of(p), d.additions());
    }

    @Test
    void aNegativeUnchangedCountIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ManifestDiff(HASH_FROM, HASH_TO, List.of(), List.of(), List.of(), -1));
        assertTrue(ex.getMessage().contains("unchanged"), "the message must name the field");
    }

    @Test
    void nullArgumentsAreRejected() {
        Placement p = at(0, 0, 0, 0, STONE);
        assertThrows(NullPointerException.class, () -> new RemovalEntry(null, STONE, BlockSpec.AIR));
        assertThrows(NullPointerException.class, () -> new RemovalEntry(p, null, BlockSpec.AIR));
        assertThrows(NullPointerException.class, () -> new RemovalEntry(p, STONE, null));
        assertThrows(NullPointerException.class, () -> new PlacementChange(null, p, STONE));
        assertThrows(NullPointerException.class, () -> new PlacementChange(p, null, STONE));
        assertThrows(NullPointerException.class, () -> new PlacementChange(p, p, null));
        assertThrows(NullPointerException.class, () -> new ManifestDiff(null, HASH_TO, List.of(), List.of(), List.of(), 0));
        assertThrows(NullPointerException.class, () -> new ManifestDiff(HASH_FROM, null, List.of(), List.of(), List.of(), 0));
        assertThrows(NullPointerException.class, () -> new ManifestDiff(HASH_FROM, HASH_TO, null, List.of(), List.of(), 0));
        assertThrows(NullPointerException.class, () -> new ManifestDiff(HASH_FROM, HASH_TO, List.of(), null, List.of(), 0));
        assertThrows(NullPointerException.class, () -> new ManifestDiff(HASH_FROM, HASH_TO, List.of(), List.of(), null, 0));
        assertThrows(NullPointerException.class, () -> ManifestDiffer.diff(null, manifest(OVERWORLD, HASH_TO), pos -> BlockSpec.AIR));
        assertThrows(NullPointerException.class, () -> ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM), null, pos -> BlockSpec.AIR));
        assertThrows(NullPointerException.class, () -> ManifestDiffer.diff(manifest(OVERWORLD, HASH_FROM), manifest(OVERWORLD, HASH_TO), null));
    }
}
