package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SenseNamesTest {

    @Test
    void vanillaNamesLoseTheirNamespaceSoScriptsCanCompareAgainstPlainWords() {
        assertEquals("farmland", SenseNames.simplify("minecraft", "farmland"));
        assertEquals("air", SenseNames.simplify("minecraft", "air"));
        assertEquals("plains", SenseNames.simplify("minecraft", "plains"));
    }

    @Test
    void moddedNamesKeepTheirNamespaceSoTwoModsCanNeverCollide() {
        assertEquals("micradrone:rotten_pumpkin", SenseNames.simplify("micradrone", "rotten_pumpkin"));
        assertEquals("othermod:farmland", SenseNames.simplify("othermod", "farmland"));
    }

    @Test
    void thunderWinsOverRainSinceAThunderstormIsAlsoRaining() {
        assertEquals("thunder", SenseNames.weather(true, true));
        assertEquals("rain", SenseNames.weather(true, false));
        assertEquals("clear", SenseNames.weather(false, false));
        // Not a state Minecraft produces, but the ordering must not depend on that holding.
        assertEquals("thunder", SenseNames.weather(false, true));
    }

    @Test
    void timeOfDayFoldsARunningCounterIntoOneDay() {
        assertEquals(0, SenseNames.timeOfDay(0));
        assertEquals(6000, SenseNames.timeOfDay(6000));
        assertEquals(23999, SenseNames.timeOfDay(23999));
        assertEquals(0, SenseNames.timeOfDay(SenseNames.TICKS_PER_DAY));
        assertEquals(500, SenseNames.timeOfDay(10 * SenseNames.TICKS_PER_DAY + 500));
    }

    @Test
    void aNegativeCounterStillYieldsATimeInsideTheDay() {
        // "/time set" can leave the raw counter negative; a plain % would hand scripts a negative
        // "time of day", which no script would ever think to guard against.
        assertEquals(23999, SenseNames.timeOfDay(-1));
        assertEquals(0, SenseNames.timeOfDay(-SenseNames.TICKS_PER_DAY));
    }
}
