package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.drone.FarmCellRules.CellFacts;

class FarmCellRulesTest {

    private static CellFacts facts(boolean groundIsTillable, boolean groundIsFarmland, boolean aboveIsEmpty, boolean aboveIsMatureCrop) {
        return new CellFacts(groundIsTillable, groundIsFarmland, aboveIsEmpty, aboveIsMatureCrop);
    }

    // ---- till() ----

    @Test
    void tillSucceedsOnTillableGroundWithEmptyAbove() {
        assertTrue(FarmCellRules.canTill(facts(true, false, true, false)));
    }

    @Test
    void tillFailsWhenGroundIsNotTillable() {
        assertFalse(FarmCellRules.canTill(facts(false, false, true, false)));
    }

    @Test
    void tillFailsWhenAlreadyFarmland() {
        // farmland is not "tillable" ground (only dirt-like blocks are) - tilling it again fails
        assertFalse(FarmCellRules.canTill(facts(false, true, true, false)));
    }

    @Test
    void tillFailsWhenSomethingOccupiesTheCellAbove() {
        assertFalse(FarmCellRules.canTill(facts(true, false, false, false)));
    }

    // ---- plant() ----

    @Test
    void plantWheatSucceedsOnFarmlandWithEmptyAbove() {
        assertTrue(FarmCellRules.canPlant("wheat", facts(false, true, true, false)));
    }

    @Test
    void plantFailsForUnsupportedCrops() {
        assertFalse(FarmCellRules.canPlant("carrot", facts(false, true, true, false)));
        assertFalse(FarmCellRules.canPlant("", facts(false, true, true, false)));
    }

    @Test
    void plantFailsWhenGroundIsNotFarmland() {
        assertFalse(FarmCellRules.canPlant("wheat", facts(true, false, true, false)));
    }

    @Test
    void plantFailsWhenSomethingAlreadyOccupiesTheCellAbove() {
        assertFalse(FarmCellRules.canPlant("wheat", facts(false, true, false, false)));
    }

    // ---- harvest() / can_harvest() ----

    @Test
    void harvestSucceedsOnlyWhenCropIsMature() {
        assertTrue(FarmCellRules.canHarvest(facts(false, true, false, true)));
    }

    @Test
    void harvestFailsWhenCropIsImmatureOrAbsent() {
        assertFalse(FarmCellRules.canHarvest(facts(false, true, false, false)));
        assertFalse(FarmCellRules.canHarvest(facts(false, true, true, false))); // aboveIsEmpty: nothing planted
    }
}
