package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import org.junit.jupiter.api.Test;

class SiteSurveyTest {
    private static final Box BOX = new Box(-2, 55, -2, 4, 70, 4);

    @Test
    void flatSurveyAnswersEveryColumnAndNothingOutside() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        assertEquals(63, s.surfaceAt(0, 0));
        assertEquals(63, s.surfaceAt(4, -2));
        assertTrue(s.covers(-2, 4));
        assertFalse(s.covers(5, 0));
        assertThrows(IllegalArgumentException.class, () -> s.surfaceAt(5, 0));
    }

    @Test
    void theDigestFollowsTheContentOnly() {
        SiteSurvey a = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        SiteSurvey b = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        assertEquals(a.digest(), b.digest());
        assertNotEquals(a.digest(), a.withColumn(1, 1, 64, "minecraft:grass_block").digest());
        assertNotEquals(a.digest(), SiteSurvey.flat("minecraft:the_nether", BOX, 63, "minecraft:grass_block").digest(),
                "the dimension is part of the pinned survey (D-27)");
        assertEquals(64, a.withColumn(1, 1, 64, "minecraft:grass_block").surfaceAt(1, 1));
        assertEquals(63, a.surfaceAt(1, 1), "withColumn copies; the original is unchanged");
    }

    @Test
    void theArraysAreDefensivelyCopiedAndShapeChecked() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        s.surfaceY()[0][0] = 99;
        assertEquals(63, s.surfaceAt(-2, -2), "the accessor hands out a copy");
        int[][] wrong = new int[2][2];
        assertThrows(IllegalArgumentException.class, () -> SiteSurvey.of(TestManifests.DIM, BOX, wrong, new String[2][2],
                new boolean[2][2], new boolean[2][2]));
    }

    @Test
    void withColumnRejectsAColumnOutsideTheSurvey() {
        SiteSurvey s = SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block");
        IllegalArgumentException low = assertThrows(IllegalArgumentException.class,
                () -> s.withColumn(BOX.minA() - 1, 0, 64, "minecraft:stone"));
        IllegalArgumentException high = assertThrows(IllegalArgumentException.class,
                () -> s.withColumn(0, BOX.maxC() + 1, 64, "minecraft:stone"));
        assertTrue(low.getMessage().contains("-3,0"), "the message names the column's coordinates");
        assertTrue(high.getMessage().contains("0,5"), "the message names the column's coordinates");
    }

    @Test
    void airSurveyPutsTheSurfaceBelowTheBox() {
        SiteSurvey air = SiteSurvey.air(TestManifests.DIM, BOX);
        assertEquals(BOX.minB() - 1, air.surfaceAt(0, 0));
        assertFalse(air.hasGround(0, 0), "an air column has no ground: terrain prep leaves it alone");
        assertTrue(SiteSurvey.flat(TestManifests.DIM, BOX, 63, "minecraft:grass_block").hasGround(0, 0));
        assertThrows(IllegalArgumentException.class, () -> air.hasGround(5, 0));
        assertEquals(new SurveyRef(air.digest(), 7L), air.ref(7L));
    }
}
