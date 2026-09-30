package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
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
        r.apply("job-9", new JournalRecord(0, P, BlockSpec.of("minecraft:stone"), false, BlockSpec.AIR, JournalRecord.NO_LEDGER_KEY));
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
}
