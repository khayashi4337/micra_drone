package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConflictsTest {
    private static final IntPos POS = new IntPos(1, 2, 3);

    @Test
    void whatWeExpectIsNotAConflict() {
        BlockSpec expected = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected), Set.of()).isEmpty());
        // the world has extra states we never listed (shape): a subset comparison passes
        assertTrue(Conflicts.detect(POS, expected, new ObservedBlock(expected.with("shape", "straight")), Set.of()).isEmpty());
    }

    @Test
    void aDifferentBlockMeansThePlayerChangedIt() {
        Optional<Conflict> c = Conflicts.detect(POS, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.of("minecraft:dirt")), Set.of());
        assertEquals(ConflictKind.PLAYER_MODIFIED, c.orElseThrow().kind());
        assertEquals(POS, c.get().pos());
        assertEquals(BlockSpec.of("minecraft:stone"), c.get().expected());
        assertEquals(BlockSpec.of("minecraft:dirt"), c.get().observed().block());
    }

    @Test
    void airWhereABlockShouldBeIsMissing() {
        Optional<Conflict> c = Conflicts.detect(POS, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.AIR), Set.of());
        assertEquals(ConflictKind.MISSING, c.orElseThrow().kind());
    }

    @Test
    void aChangedListedStateIsAModification() {
        BlockSpec expected = BlockSpec.of("minecraft:oak_stairs", "facing", "north");
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected,
                new ObservedBlock(BlockSpec.of("minecraft:oak_stairs", "facing", "south")), Set.of()).orElseThrow().kind());
    }

    @Test
    void volatileStatesAreIgnoredSoRunningMachinesAreNotConflicts() {
        BlockSpec expected = BlockSpec.of("create:blaze_burner", "blaze", "smouldering");
        ObservedBlock running = new ObservedBlock(BlockSpec.of("create:blaze_burner", "blaze", "kindled"));
        assertEquals(ConflictKind.PLAYER_MODIFIED, Conflicts.detect(POS, expected, running, Set.of()).orElseThrow().kind());
        assertTrue(Conflicts.detect(POS, expected, running, Set.of("blaze")).isEmpty());
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "facing", "north", "open", "false");
        assertTrue(Conflicts.detect(POS, door, new ObservedBlock(door.with("open", "true")), Set.of("open", "powered")).isEmpty());
    }

    @Test
    void anExpectedAirIsNeverAConflictOfMissing() {
        assertTrue(Conflicts.detect(POS, BlockSpec.AIR, new ObservedBlock(BlockSpec.AIR), Set.of()).isEmpty());
        // a non-air block where we expect air is also not a conflict: a MODIFY job never planned that cell
        assertTrue(Conflicts.detect(POS, BlockSpec.AIR, new ObservedBlock(BlockSpec.of("minecraft:dirt")), Set.of()).isEmpty());
    }
}
