package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PlotColorRulesTest {

    @Test
    void lerpEndpointsReturnTheInputColors() {
        assertEquals(0xFF102030, PlotColorRules.lerp(0xFF102030, 0xFFFFFFFF, 0.0f));
        assertEquals(0xFFFFFFFF, PlotColorRules.lerp(0xFF102030, 0xFFFFFFFF, 1.0f));
    }

    @Test
    void lerpMidpointBlendsEachChannelIndependently() {
        assertEquals(0xFF081018, PlotColorRules.lerp(0xFF000000, 0xFF102030, 0.5f));
    }

    @Test
    void lerpClampsOutOfRangeTInsteadOfExtrapolating() {
        assertEquals(0xFF000000, PlotColorRules.lerp(0xFF000000, 0xFF102030, -5.0f));
        assertEquals(0xFF102030, PlotColorRules.lerp(0xFF000000, 0xFF102030, 5.0f));
    }

    @Test
    void fullyGrownCropsShowTheirMatureColor() {
        assertEquals(PlotColorRules.WHEAT_MATURE, PlotColorRules.wheat(1.0f));
        assertEquals(PlotColorRules.CARROT_MATURE, PlotColorRules.carrot(1.0f));
        assertEquals(PlotColorRules.PUMPKIN_STEM_GROWN, PlotColorRules.pumpkinStem(1.0f));
    }

    @Test
    void freshlyPlantedCropsShowTheirYoungColor() {
        assertEquals(PlotColorRules.WHEAT_YOUNG, PlotColorRules.wheat(0.0f));
        assertEquals(PlotColorRules.CARROT_YOUNG, PlotColorRules.carrot(0.0f));
    }

    @Test
    void halfGrownIsBetweenYoungAndMature() {
        int half = PlotColorRules.wheat(0.5f);
        assertNotEquals(PlotColorRules.WHEAT_YOUNG, half);
        assertNotEquals(PlotColorRules.WHEAT_MATURE, half);
    }

    @Test
    void moistAndDryFarmlandAreVisiblyDifferent() {
        assertNotEquals(PlotColorRules.farmland(true), PlotColorRules.farmland(false));
        assertEquals(PlotColorRules.FARMLAND_MOIST, PlotColorRules.farmland(true));
    }
}
