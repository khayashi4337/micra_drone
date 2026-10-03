package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeWorldTest {
    private static final UUID ACTOR = new UUID(0, 1);
    private static final IntPos P = new IntPos(1, 2, 3);

    @Test
    void unsetIsAirAndWritesAreLogged() {
        FakeWorld w = new FakeWorld();
        assertEquals(BlockSpec.AIR, w.blockAt(P));
        assertEquals(PlaceResult.PLACED, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
        assertEquals("minecraft:stone", w.blockAt(P).blockId());
        assertEquals(List.of("place 1,2,3 minecraft:stone"), w.log);
    }

    @Test
    void deniedAndUnloadedBehaveLikeTheRealWorld() {
        FakeWorld w = new FakeWorld();
        w.deny(P);
        assertEquals(PlaceResult.DENIED, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
        assertEquals(BlockSpec.AIR, w.blockAt(P));
        IntPos far = new IntPos(99, 0, 0);
        w.unload(far);
        assertFalse(w.read(far).loaded());
        assertThrows(IllegalStateException.class, () -> w.place(far, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
    }

    @Test
    void anOccupiedSpotRefusesACollidingWriteButNeverAir() {
        FakeWorld w = new FakeWorld();
        w.occupy(P);
        assertEquals(PlaceResult.BLOCKED_BY_ENTITY, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
        assertEquals(BlockSpec.AIR, w.blockAt(P), "nothing was written");
        assertEquals(PlaceResult.BLOCKED_BY_ENTITY, w.restore(P, BlockSpec.of("minecraft:stone"), ACTOR, true));
        assertEquals(PlaceResult.PLACED, w.restore(P, BlockSpec.AIR, ACTOR, false),
                "air has no collision shape: a removal always goes through");
        w.leave(P);
        assertEquals(PlaceResult.PLACED, w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR));
    }

    @Test
    void placedAndRestoredBlockEntitiesKeepTheirType() {
        FakeWorld w = new FakeWorld().blockEntity("minecraft:chest", "minecraft:chest");
        BlockSpec chest = BlockSpec.of("minecraft:chest");
        w.place(P, chest, Map.of(), ACTOR);
        assertTrue(w.read(P).observed().hasBlockEntity(), "a catalogued block keeps its block entity");
        assertEquals("minecraft:chest", w.read(P).observed().blockEntityType());
        assertTrue(w.read(P).traits().contains(CellTrait.EMPTY_CONTAINER), "no items: an empty container");
        w.putItems(P, 3);
        assertFalse(w.read(P).traits().contains(CellTrait.EMPTY_CONTAINER), "items: no longer empty");

        IntPos q = new IntPos(4, 5, 6);
        w.restore(q, chest, ACTOR, false);
        assertTrue(w.read(q).observed().hasBlockEntity(), "restore keeps the block entity too");
        assertTrue(w.read(q).traits().contains(CellTrait.EMPTY_CONTAINER));

        w.place(P, BlockSpec.of("minecraft:stone"), Map.of(), ACTOR);
        assertFalse(w.read(P).observed().hasBlockEntity(), "an uncatalogued block stays a plain block");
    }
}
