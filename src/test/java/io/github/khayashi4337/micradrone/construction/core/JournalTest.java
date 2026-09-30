package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalTest {
    static JournalRecord rec(int index, int x, int y, int z, String before, String placed) {
        return new JournalRecord(index, new IntPos(x, y, z), BlockSpec.of(before), false, BlockSpec.of(placed), index);
    }

    @Test
    void theFirstRecordOfAPositionIsKeptSoThePreBuildStateSurvives() {
        Journal j = new Journal();
        assertTrue(j.record(rec(0, 0, 64, 0, "minecraft:air", "minecraft:stone")));
        assertFalse(j.record(rec(0, 0, 64, 0, "minecraft:stone", "minecraft:stone")), "a repeat never overwrites 'before'");
        assertEquals("minecraft:air", j.at(0).orElseThrow().before().blockId());
        assertEquals(1, j.size());
    }

    @Test
    void undoRunsTopDown() {
        Journal j = new Journal();
        j.record(rec(0, 1, 64, 0, "minecraft:air", "minecraft:stone"));
        j.record(rec(1, 0, 65, 1, "minecraft:air", "minecraft:stone"));
        j.record(rec(2, 0, 65, 0, "minecraft:grass_block", "minecraft:stone"));
        assertEquals(List.of(new UndoEntry(new IntPos(0, 65, 0), BlockSpec.of("minecraft:grass_block")),
                new UndoEntry(new IntPos(0, 65, 1), BlockSpec.AIR), new UndoEntry(new IntPos(1, 64, 0), BlockSpec.AIR)),
                j.undo());
    }

    @Test
    void theJournalHasACap() {
        Journal j = new Journal(2);
        j.record(rec(0, 0, 64, 0, "minecraft:air", "minecraft:stone"));
        j.record(rec(1, 1, 64, 0, "minecraft:air", "minecraft:stone"));
        assertThrows(IllegalStateException.class, () -> j.record(rec(2, 2, 64, 0, "minecraft:air", "minecraft:stone")));
        assertEquals(20_000, Journal.MAX_ENTRIES);
    }
}
