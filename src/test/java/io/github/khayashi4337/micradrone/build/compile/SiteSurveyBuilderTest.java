package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.List;
import org.junit.jupiter.api.Test;

class SiteSurveyBuilderTest {
    @Test
    void columnsAreHandedOutInBatchesAndTheSurveyMatchesAFlatOne() {
        Box box = new Box(0, 60, 0, 2, 70, 1);
        SiteSurveyBuilder b = new SiteSurveyBuilder(TestManifests.DIM, box);
        int n = 0;
        while (!b.done()) {
            List<int[]> cols = b.nextColumns(4);
            assertTrue(cols.size() <= 4);
            for (int[] c : cols) {
                b.column(c[0], c[1], 63, "minecraft:grass_block", false, false);
                n++;
            }
        }
        assertEquals(6, n);
        assertEquals(SiteSurvey.flat(TestManifests.DIM, box, 63, "minecraft:grass_block").digest(), b.build().digest());
    }

    @Test
    void anUnfinishedSurveyCannotBeBuilt() {
        SiteSurveyBuilder b = new SiteSurveyBuilder(TestManifests.DIM, new Box(0, 0, 0, 1, 1, 1));
        b.nextColumns(1);
        assertFalse(b.done());
        assertThrows(IllegalStateException.class, b::build);
    }
}
