package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlockMatchTest {
    private static final BlockSpec STAIRS = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom");

    @Test
    void onlyTheListedPropertiesAreCompared() {
        BlockSpec observed = BlockSpec.of("minecraft:oak_stairs", "facing", "north", "half", "bottom", "shape", "outer_left",
                "waterlogged", "false");
        assertTrue(BlockMatch.satisfies(observed, STAIRS, Set.of()), "extra observed states are not compared");
        assertFalse(BlockMatch.satisfies(observed.with("facing", "east"), STAIRS, Set.of()), "a turned stair is caught");
        assertFalse(BlockMatch.satisfies(BlockSpec.of("minecraft:stone_stairs", "facing", "north", "half", "bottom"), STAIRS,
                Set.of()));
    }

    @Test
    void ignoredPropertiesNeverFail() {
        BlockSpec door = BlockSpec.of("minecraft:oak_door", "open", "false", "facing", "south");
        assertTrue(BlockMatch.satisfies(door.with("open", "true"), door, Set.of("open")));
        assertFalse(BlockMatch.satisfies(door.with("open", "true"), door, Set.of()));
    }

    @Test
    void exactComparesEveryStateAndSubsetOnlyTheListedOnes() {
        BlockSpec log = BlockSpec.of("minecraft:oak_log", "axis", "y");
        assertTrue(BlockMatch.exact(log, log, Set.of()));
        assertFalse(BlockMatch.exact(log.with("extra", "1"), log, Set.of()), "an unlisted observed state fails EXACT");
        assertTrue(BlockMatch.satisfies(log.with("extra", "1"), log, Set.of()), "but not STATE_SUBSET");
        assertTrue(BlockMatch.exact(log.with("open", "true"), log, Set.of("open")), "volatile states are ignored by both");
        assertFalse(BlockMatch.exact(BlockSpec.of("minecraft:stone"), BlockSpec.of("minecraft:stone", "x", "1"), Set.of()));
    }

    @Test
    void nullArgumentsAreRejectedEvenWithAnEmptyExpectation() {
        BlockSpec plain = BlockSpec.of("minecraft:stone");
        assertThrows(NullPointerException.class, () -> BlockMatch.satisfies(null, plain, Set.of()));
        assertThrows(NullPointerException.class, () -> BlockMatch.satisfies(plain, null, Set.of()));
        assertThrows(NullPointerException.class, () -> BlockMatch.satisfies(plain, plain, null),
                "an empty expected state list must not skip the ignoredProps check");
        assertThrows(NullPointerException.class, () -> BlockMatch.exact(null, plain, Set.of()));
        assertThrows(NullPointerException.class, () -> BlockMatch.exact(plain, null, Set.of()));
        assertThrows(NullPointerException.class, () -> BlockMatch.exact(plain, plain, null));
    }

    @Test
    void observedBlockCarriesTheBlockEntityFlag() {
        ObservedBlock plain = new ObservedBlock(BlockSpec.of("minecraft:stone"));
        assertFalse(plain.hasBlockEntity());
        assertEquals("", plain.blockEntityType());
        ObservedBlock chest = new ObservedBlock(BlockSpec.of("minecraft:chest"), true, "minecraft:chest");
        assertTrue(chest.hasBlockEntity());
        assertThrows(IllegalArgumentException.class, () -> new ObservedBlock(BlockSpec.of("minecraft:stone"), false, "x"));
    }
}
