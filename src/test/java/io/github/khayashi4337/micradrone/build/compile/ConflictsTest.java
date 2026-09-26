package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConflictsTest {
    private static final IntPos POS = new IntPos(1, 2, 3);
    private static final String STAIRS_ID = "minecraft:oak_stairs";
    private static final String DOOR_ID = "minecraft:oak_door";
    private static final String FACING = "facing";
    private static final String OPEN = "open";
    private static final String NORTH = "north";
    private static final String SOUTH = "south";
    private static final BlockSpec STONE = BlockSpec.of("minecraft:stone");
    private static final BlockSpec DIRT = BlockSpec.of("minecraft:dirt");

    @Test
    void whatWeExpectIsNotAConflict() {
        BlockSpec expected = BlockSpec.of(STAIRS_ID, FACING, NORTH, "half", "bottom");
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected), Set.of()).isEmpty());
        // the world has extra states we never listed (shape): a subset comparison passes
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected.with("shape", "straight")), Set.of()).isEmpty());
    }

    @Test
    void aDifferentBlockMeansThePlayerChangedIt() {
        Optional<Conflict> c = Conflicts.detect(POS, STONE, new ObservedBlock(DIRT), Set.of());
        assertEquals(ConflictKind.PLAYER_MODIFIED, c.orElseThrow().kind());
        assertEquals(POS, c.get().pos());
        assertEquals(STONE, c.get().expected());
        assertEquals(DIRT, c.get().observed().block());
    }

    @Test
    void airWhereABlockShouldBeIsMissing() {
        Optional<Conflict> c = Conflicts.detect(POS, STONE, new ObservedBlock(BlockSpec.AIR), Set.of());
        assertEquals(ConflictKind.MISSING, c.orElseThrow().kind());
        assertEquals(POS, c.get().pos());
        assertEquals(STONE, c.get().expected());
        assertEquals(BlockSpec.AIR, c.get().observed().block());
    }

    @Test
    void aChangedListedStateIsAModification() {
        BlockSpec expected = BlockSpec.of(STAIRS_ID, FACING, NORTH);
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected,
                new ObservedBlock(BlockSpec.of(STAIRS_ID, FACING, SOUTH)), Set.of()).orElseThrow().kind());
    }

    @Test
    void volatileStatesAreIgnoredSoRunningMachinesAreNotConflicts() {
        BlockSpec expected = BlockSpec.of("create:blaze_burner", "blaze", "smouldering");
        ObservedBlock running = new ObservedBlock(BlockSpec.of("create:blaze_burner", "blaze", "kindled"));
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected, running, Set.of()).orElseThrow().kind());
        assertTrue(Conflicts.detect(POS, expected, running, Set.of("blaze")).isEmpty());
        BlockSpec door = BlockSpec.of(DOOR_ID, FACING, NORTH, OPEN, "false");
        assertTrue(Conflicts.detect(POS, door, new ObservedBlock(door.with(OPEN, "true")), Set.of(OPEN, "powered")).isEmpty());
    }

    @Test
    void onlyListedVolatileStatesAreIgnored() {
        BlockSpec expected = BlockSpec.of(DOOR_ID, FACING, NORTH, OPEN, "false");
        // the player both rotated the door and opened it
        ObservedBlock moved = new ObservedBlock(BlockSpec.of(DOOR_ID, FACING, SOUTH, OPEN, "true"));
        assertEquals(ConflictKind.PLAYER_MODIFIED,
                Conflicts.detect(POS, expected, moved, Set.of(OPEN)).orElseThrow().kind(),
                "facing is not listed as volatile, so the rotation is still a conflict");
        assertEquals(ConflictKind.PLAYER_MODIFIED,
                Conflicts.detect(POS, expected, moved, Set.of()).orElseThrow().kind());
        assertTrue(Conflicts.detect(POS, expected, moved, Set.of(OPEN, FACING)).isEmpty(),
                "every differing state is listed as volatile");
    }

    @Test
    void aNonAirBlockWhereAirIsExpectedIsPlayerModified() {
        // something the job did not place stands where the job planned air: a false conflict is safe,
        // a silent overwrite of a player's block is not
        Optional<Conflict> c = Conflicts.detect(POS, BlockSpec.AIR, new ObservedBlock(STONE), Set.of());
        assertEquals(ConflictKind.PLAYER_MODIFIED, c.orElseThrow().kind());
        assertEquals(POS, c.get().pos());
        assertEquals(BlockSpec.AIR, c.get().expected());
        assertEquals(STONE, c.get().observed().block());
        assertTrue(Conflicts.detect(POS, BlockSpec.AIR, new ObservedBlock(BlockSpec.AIR), Set.of()).isEmpty(),
                "expected air and observed air agree");
    }

    @Test
    void nullArgumentsAreRejected() {
        ObservedBlock observed = new ObservedBlock(STONE);
        assertThrows(NullPointerException.class, () -> Conflicts.detect(null, STONE, observed, Set.of()));
        assertThrows(NullPointerException.class, () -> Conflicts.detect(POS, null, observed, Set.of()));
        assertThrows(NullPointerException.class, () -> Conflicts.detect(POS, STONE, null, Set.of()));
        assertThrows(NullPointerException.class, () -> Conflicts.detect(POS, STONE, observed, null));
        assertThrows(NullPointerException.class, () -> new ObservedBlock(null));
        assertThrows(NullPointerException.class, () -> new Conflict(null, STONE, observed, ConflictKind.MISSING));
        assertThrows(NullPointerException.class, () -> new Conflict(POS, null, observed, ConflictKind.MISSING));
        assertThrows(NullPointerException.class, () -> new Conflict(POS, STONE, null, ConflictKind.MISSING));
        assertThrows(NullPointerException.class, () -> new Conflict(POS, STONE, observed, null));
    }
}
