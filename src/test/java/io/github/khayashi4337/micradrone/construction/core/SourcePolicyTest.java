package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order a survival job may take materials in (Task 27a): the owner's inventory is only in the
 * answer at all when the claim allowed it, and then it comes first; supply chests always follow in
 * coordinate order. This is the rule that keeps a child's own belongings from being spent quietly.
 */
class SourcePolicyTest {
    private static Stock inv(String itemId, int count) {
        return new Stock(MaterialPort.INVENTORY, itemId, count);
    }

    private static Stock chest(int x, int y, int z, String itemId, int count) {
        return new Stock(MaterialPort.chest(new IntPos(x, y, z)), itemId, count);
    }

    private static List<String> sourceIds(List<Stock> stocks) {
        return stocks.stream().map(Stock::sourceId).toList();
    }

    @Test
    void withoutPermissionNoInventoryStockIsShown() {
        List<Stock> out = SourcePolicy.visibleStocks(List.of(
                inv("minecraft:cobblestone", 5),
                chest(1, 2, 3, "minecraft:cobblestone", 4),
                chest(2, 2, 3, "minecraft:cobblestone", 1)), false);
        assertEquals(List.of("chest:1,2,3", "chest:2,2,3"), sourceIds(out),
                "disallowed inventory stocks are removed, chests stay");
        assertEquals(5, out.stream().mapToInt(Stock::count).sum(), "the chests keep their own counts");
    }

    @Test
    void withPermissionTheInventoryComesFirstThenChestsInCoordinateOrder() {
        // deliberately scrambled input: a later chest first, the inventory in the middle
        List<Stock> all = List.of(
                chest(5, 1, 1, "minecraft:cobblestone", 1),
                chest(1, 0, 9, "minecraft:cobblestone", 6),
                inv("minecraft:cobblestone", 3),
                chest(2, 1, 1, "minecraft:cobblestone", 2),
                chest(1, 0, 2, "minecraft:cobblestone", 4));
        List<Stock> out = SourcePolicy.visibleStocks(all, true);
        assertEquals(List.of("inventory", "chest:1,0,2", "chest:1,0,9", "chest:2,1,1", "chest:5,1,1"),
                sourceIds(out), "x sorts first, then y, then z; the inventory leads");
        assertEquals(3, out.get(0).count());
        assertEquals(4, out.get(1).count());
    }

    @Test
    void chestsAreCoordinateSortedEvenWithoutPermission() {
        List<Stock> out = SourcePolicy.visibleStocks(List.of(
                chest(7, 0, 0, "a", 1), chest(0, 9, 0, "a", 1), chest(0, 0, 9, "a", 1)), false);
        assertEquals(List.of("chest:0,0,9", "chest:0,9,0", "chest:7,0,0"), sourceIds(out));
    }

    @Test
    void noChestsAndNoPermissionIsEmpty() {
        assertEquals(List.of(), SourcePolicy.visibleStocks(List.of(inv("a", 5)), false));
    }

    @Test
    void theInputListIsNeverMutated() {
        List<Stock> all = new ArrayList<>(List.of(
                chest(9, 0, 0, "a", 1), chest(1, 0, 0, "a", 1), inv("a", 2)));
        List<Stock> before = List.copyOf(all);
        SourcePolicy.visibleStocks(all, true);
        assertEquals(before, all, "the policy returns a new list and leaves its argument alone");
    }
}
