package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class UnlockShopTest {

    @Test
    void findReturnsTheMatchingCatalogEntry() {
        Optional<UnlockShop.Unlock> carrot = UnlockShop.find("carrot");
        assertTrue(carrot.isPresent());
        assertEquals(Map.of("wheat", 20L), carrot.get().cost());
    }

    @Test
    void findReturnsEmptyForAnUnknownId() {
        assertEquals(Optional.empty(), UnlockShop.find("does_not_exist"));
    }

    @Test
    void canAffordIsTrueWhenEveryRequiredCropMeetsTheCost() {
        Map<String, Long> points = Map.of("wheat", 20L);
        Map<String, Long> cost = Map.of("wheat", 20L);
        assertTrue(UnlockShop.canAfford(points, cost));
    }

    @Test
    void canAffordIsFalseWhenOneCropFallsShort() {
        Map<String, Long> points = Map.of("wheat", 30L, "carrot", 10L);
        Map<String, Long> cost = Map.of("wheat", 30L, "carrot", 15L);
        assertFalse(UnlockShop.canAfford(points, cost));
    }

    @Test
    void canAffordTreatsAMissingCropAsZero() {
        Map<String, Long> points = Map.of("wheat", 100L);
        Map<String, Long> cost = Map.of("wheat", 30L, "carrot", 1L);
        assertFalse(UnlockShop.canAfford(points, cost));
    }

    @Test
    void pumpkinCostsWheatAndCarrot() {
        Optional<UnlockShop.Unlock> pumpkin = UnlockShop.find("pumpkin");
        assertTrue(pumpkin.isPresent());
        assertEquals(Map.of("wheat", 30L, "carrot", 15L), pumpkin.get().cost());
    }
}
