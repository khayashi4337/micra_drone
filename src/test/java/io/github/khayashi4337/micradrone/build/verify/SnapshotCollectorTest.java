package io.github.khayashi4337.micradrone.build.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotCollectorTest {
    @Test
    void readsInBatchesAndHandsOutBoundedWindows() {
        List<IntPos> positions = new ArrayList<>();
        for (int i = 0; i < SparseSnapshot.MAX_POSITIONS + 5; i++) {
            positions.add(new IntPos(i, 0, 0));
        }
        SnapshotCollector c = new SnapshotCollector(positions);
        int reads = 0;
        while (!c.windowFull()) {
            for (IntPos p : c.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
                c.accept(p, new ObservedBlock(BlockSpec.AIR));
                reads++;
            }
        }
        assertEquals(SparseSnapshot.MAX_POSITIONS, reads);
        SnapshotCollector.Window w = c.takeWindow();
        assertEquals(0, w.fromIndex());
        assertEquals(SparseSnapshot.MAX_POSITIONS, w.toIndexExclusive());
        assertFalse(c.done());
        for (IntPos p : c.nextBatch(SnapshotCollector.DEFAULT_READS_PER_TICK)) {
            c.accept(p, new ObservedBlock(BlockSpec.AIR));
        }
        assertTrue(c.windowFull());
        assertEquals(5, c.takeWindow().snapshot().blocks().size());
        assertTrue(c.done());
    }

    @Test
    void unloadedPositionsAreRememberedForRetry() {
        SnapshotCollector c = new SnapshotCollector(List.of(new IntPos(0, 0, 0), new IntPos(1, 0, 0)));
        List<IntPos> batch = c.nextBatch(10);
        c.accept(batch.get(0), new ObservedBlock(BlockSpec.AIR));
        c.unloaded(batch.get(1));
        assertEquals(List.of(new IntPos(1, 0, 0)), c.unloadedPositions());
        assertFalse(c.windowFull(), "an unloaded position is not read yet");
        c.resetUnloaded();
        assertEquals(List.of(new IntPos(1, 0, 0)), c.nextBatch(10), "it is read again after the chunk loads");
    }
}
