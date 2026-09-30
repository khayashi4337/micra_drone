package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacedRegistryTest {
    private static final IntPos P = new IntPos(0, 64, 0);

    @Test
    void theFirstPlacementRemembersThePreBuildBlockAndLaterChangesKeepIt() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.of("minecraft:stone"), 0));
        r.apply("job-2", new JournalRecord(4, P, BlockSpec.of("minecraft:stone"), false, BlockSpec.of("minecraft:oak_planks"), 4));
        PlacedEntry e = r.at(P).orElseThrow();
        assertEquals("minecraft:oak_planks", e.placed().blockId());
        assertEquals("minecraft:grass_block", e.before().blockId(), "the original ground, not the first build's stone");
        assertEquals("job-2", e.jobId(), "the latest placer's ledger answers for the current block");
        assertEquals(4, e.placementIndex());
    }

    @Test
    void restoringThePreBuildBlockForgetsThePosition() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        r.apply("job-9", new JournalRecord(JournalRecord.restoreIndex(0), P, BlockSpec.of("minecraft:stone"), false,
                BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));
        assertFalse(r.contains(P));
        assertEquals(0, r.size());
    }

    @Test
    void aRemovalRunAgainAfterACrashDoesNotClaimTheGround() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.of("minecraft:grass_block"), false, BlockSpec.of("minecraft:stone"), 0));
        JournalRecord undo = new JournalRecord(JournalRecord.restoreIndex(0), P, BlockSpec.of("minecraft:stone"), false,
                BlockSpec.of("minecraft:grass_block"), JournalRecord.NO_LEDGER_KEY);
        r.apply("job-2", undo);
        r.apply("job-2", undo);
        assertFalse(r.contains(P), "the grass put back is not a project block, however often the removal runs");
    }

    @Test
    void placingWhatWasAlreadyThereRecordsNothing() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        r.apply("job-1", new JournalRecord(0, P, BlockSpec.AIR, false, BlockSpec.AIR, 0));
        assertFalse(r.contains(P), "an air placement on air is not a project block");
        assertTrue(r.assemblies().isEmpty());
    }

    @Test
    void theSameJobWritingAPositionAgainKeepsItsEarlierKey() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        // the ground cut (key 1, dirt handed over) and then the foundation on it (key 10)
        r.apply("job-1", new JournalRecord(1, P, grass, false, BlockSpec.AIR, 1, true));
        r.apply("job-1", new JournalRecord(10, P, BlockSpec.AIR, false, BlockSpec.of("minecraft:cobblestone"), 10));
        PlacedEntry e = r.at(P).orElseThrow();
        assertEquals(10, e.placementIndex());
        assertEquals(java.util.List.of(1), e.earlierKeys(), "a removal settles the cut ground as well as the foundation");
        assertEquals(grass, e.before());
        r.apply("job-2", new JournalRecord(5, P, BlockSpec.of("minecraft:cobblestone"), false, BlockSpec.of("minecraft:stone"), 5));
        assertEquals(java.util.List.of(), r.at(P).orElseThrow().earlierKeys(), "another job's keys belong to its own ledger");
    }

    @Test
    void aReAppliedWriteIsANoOpNotARewind() {
        PlacedRegistry r = new PlacedRegistry("claim-1");
        BlockSpec grass = BlockSpec.of("minecraft:grass_block");
        BlockSpec cobble = BlockSpec.of("minecraft:cobblestone");
        JournalRecord cut = new JournalRecord(1, P, grass, false, BlockSpec.AIR, 1, true);
        JournalRecord foundation = new JournalRecord(10, P, BlockSpec.AIR, false, cobble, 10);
        r.apply("job-1", cut);
        r.apply("job-1", foundation);
        assertEquals(List.of(1), r.at(P).orElseThrow().earlierKeys());
        r.apply("job-1", foundation);
        assertEquals(10, r.at(P).orElseThrow().placementIndex());
        assertEquals(List.of(1), r.at(P).orElseThrow().earlierKeys(),
                "re-applying the current key must not duplicate it into the earlier keys");
        r.apply("job-1", cut);
        assertEquals(10, r.at(P).orElseThrow().placementIndex(), "an old replay must not rewind the owner");
        assertEquals(List.of(1), r.at(P).orElseThrow().earlierKeys());
        assertThrows(IllegalArgumentException.class,
                () -> r.apply("job-1", new JournalRecord(10, P, BlockSpec.AIR, false,
                        BlockSpec.of("minecraft:stone"), 10)), "the same key with a different block is a bug");
        assertEquals(10, r.at(P).orElseThrow().placementIndex());
        assertThrows(IllegalStateException.class,
                () -> r.apply("job-1", new JournalRecord(20, P, BlockSpec.of("minecraft:gold_block"), false,
                        BlockSpec.of("minecraft:dirt"), 20)),
                "a first-round write that did not see the held block belongs to another history");
        assertEquals(10, r.at(P).orElseThrow().placementIndex());
    }

    @Test
    void thePlacedViewIsOrderedByPositionNotByInsertion() {
        IntPos a = new IntPos(0, 64, 0);
        IntPos b = new IntPos(0, 65, 0);
        IntPos c = new IntPos(1, 63, 2);
        PlacedRegistry first = new PlacedRegistry("claim-1");
        PlacedRegistry second = new PlacedRegistry("claim-1");
        for (IntPos p : List.of(c, b, a)) {
            first.apply("job-1", new JournalRecord(0, p, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        }
        for (IntPos p : List.of(a, b, c)) {
            second.apply("job-1", new JournalRecord(0, p, BlockSpec.AIR, false, BlockSpec.of("minecraft:stone"), 0));
        }
        assertEquals(new ArrayList<>(first.placed().keySet()), new ArrayList<>(second.placed().keySet()),
                "the public view never leaks the insertion order");
        assertEquals(List.of(a, b, c), List.copyOf(first.placed().keySet()), "x, then y, then z");
    }
}
