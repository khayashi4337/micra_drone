package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobOutcomeTest {
    @Test
    void aPositionIsReportedOnceAndDenialsAreIndexed() {
        JobOutcome o = new JobOutcome();
        Conflict c = new Conflict(new IntPos(1, 2, 3), BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:dirt")), ConflictKind.PLAYER_MODIFIED);
        assertTrue(o.addConflict(c));
        assertFalse(o.addConflict(c));
        o.skip(new SkippedPlacement(5, new IntPos(0, 0, 0), SkippedPlacement.DENIED));
        o.skip(new SkippedPlacement(6, new IntPos(1, 0, 0), SkippedPlacement.SITE_CHANGED));
        assertEquals(1, o.conflicts().size());
        assertEquals(Set.of(5), o.deniedIndexes());
        assertEquals(2, o.skipped().size());
    }

    @Test
    void onlyRemovalConflictsBlockLaterPlacementsAndASiteConflictCanBeResolved() {
        JobOutcome o = new JobOutcome();
        IntPos site = new IntPos(1, 2, 3);
        IntPos removed = new IntPos(4, 5, 6);
        o.addConflict(new Conflict(site, BlockSpec.of("minecraft:stone"), new ObservedBlock(BlockSpec.of("minecraft:dirt")),
                ConflictKind.PLAYER_MODIFIED));
        o.addRestoreConflict(new Conflict(removed, BlockSpec.of("minecraft:stone"),
                new ObservedBlock(BlockSpec.of("minecraft:gold_block")), ConflictKind.PLAYER_MODIFIED));
        assertFalse(o.hasRestoreConflictAt(site));
        assertTrue(o.hasRestoreConflictAt(removed));
        o.resolveConflictAt(site);
        o.resolveConflictAt(removed);
        assertEquals(1, o.conflicts().size(), "the site conflict is resolved; the removal conflict stays reported");
    }
}
