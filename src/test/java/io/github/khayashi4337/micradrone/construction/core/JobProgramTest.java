package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.compile.TestManifests;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobProgramTest {
    @Test
    void aBuildProgramIsTheManifestInOrderKeyedByIndex() {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram p = JobProgram.build(m);
        assertEquals(m.placements().size(), p.size());
        for (int i = 0; i < p.size(); i++) {
            assertFalse(p.isRestore(i));
            assertEquals(i, p.put(i).index());
            assertEquals(i, p.put(i).ledgerKey());
        }
    }

    @Test
    void repairRoundsUseTheirOwnLedgerKeys() {
        PlacementManifest m = TestManifests.smallHut();
        JobProgram r = JobProgram.repair(m, List.of(3, 7), 2);
        assertEquals(2, r.size());
        assertEquals(3, r.put(0).index());
        assertEquals(2 * JobProgram.LEDGER_ROUND_STRIDE + 3, r.put(0).ledgerKey());
        assertTrue(JobProgram.LEDGER_ROUND_STRIDE > 20_000);
    }

    @Test
    void restoresComeBeforePuts() {
        RestoreItem undo = new RestoreItem(new IntPos(0, 64, 0), BlockSpec.of("minecraft:stone"), Set.of(), BlockSpec.AIR,
                "job-1", 0, false);
        JobProgram p = new JobProgram(List.of(undo), JobProgram.build(TestManifests.smallHut()).puts());
        assertTrue(p.isRestore(0));
        assertFalse(p.isRestore(1));
        assertEquals(undo, p.restore(0));
        assertEquals(0, p.put(1).index());
        assertEquals(1 + 25, p.size());
    }
}
