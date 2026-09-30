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
    void undoUsesTheFirstBeforeOfEachPositionOnce() {
        Journal j = new Journal();
        IntPos p = new IntPos(0, 64, 0);
        j.record(new JournalRecord(1, p, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.AIR, 1, true));
        j.record(new JournalRecord(10, p, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 10));
        assertEquals(List.of(new UndoEntry(p, BlockSpec.of("minecraft:grass_block"))), j.undo(),
                "a position the job wrote twice goes back to its first 'before', once");
    }

    @Test
    void aConflictingReRecordIsRejectedButAnIdenticalRerunIsIgnored() {
        Journal j = new Journal();
        JournalRecord r = rec(0, 0, 64, 0, "minecraft:air", "minecraft:stone");
        assertTrue(j.record(r));
        assertFalse(j.record(r), "the same record again is a rerun");
        assertThrows(IllegalStateException.class,
                () -> j.record(rec(0, 1, 64, 0, "minecraft:air", "minecraft:stone")),
                "another position under the same index would lose this one's 'before'");
        assertThrows(IllegalStateException.class,
                () -> j.record(rec(0, 0, 64, 0, "minecraft:air", "minecraft:oak_planks")),
                "another block under the same index");
        assertThrows(IllegalStateException.class,
                () -> j.record(new JournalRecord(0, new IntPos(0, 64, 0), BlockSpec.AIR, false,
                        BlockSpec.of("minecraft:stone"), 0, true)), "another terrain-cut flag under the same index");
        assertEquals("minecraft:air", j.at(0).orElseThrow().before().blockId(), "the first record stands");
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
