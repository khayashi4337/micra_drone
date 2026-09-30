package io.github.khayashi4337.micradrone.build.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import org.junit.jupiter.api.Test;

class VoxelGridFillerTest {
    @Test
    void fillsEveryCellOnceInBoundedBatches() {
        VoxelGridFiller f = new VoxelGridFiller(new Box(0, 0, 0, 3, 2, 1));
        int reads = 0;
        while (!f.done()) {
            for (IntPos p : f.nextBatch(5)) {
                f.set(p, (byte) (p.x() + p.y()));
                reads++;
            }
        }
        assertEquals(4 * 3 * 2, reads);
        VoxelClassGrid g = f.grid();
        assertEquals(5, g.classAt(new IntPos(3, 2, 1)));
    }

    @Test
    void aGridTooLargeForTheDesignIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new VoxelGridFiller(new Box(0, 0, 0, 1023, 1023, 1023)));
        assertEquals(1_572_864, VoxelClassGrid.MAX_CELLS);
    }
}
