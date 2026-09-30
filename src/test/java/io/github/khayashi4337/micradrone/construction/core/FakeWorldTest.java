package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
