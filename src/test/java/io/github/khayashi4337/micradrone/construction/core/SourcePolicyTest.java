package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The order a survival job may take materials in (Task 27a): the owner's inventory is only in the
 * answer at all when the claim allowed it, and then it comes first; supply chests always follow in
 * coordinate order. This is the rule that keeps a child's own belongings from being spent quietly.
 * Task 27b adds the excluded-items set: a "don't use this" applies to every source alike.
 */
class SourcePolicyTest {
    private static final Set<String> NONE = Set.of();

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
                chest(2, 2, 3, "minecraft:cobblestone", 1)), false, NONE);
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
        List<Stock> out = SourcePolicy.visibleStocks(all, true, NONE);
        assertEquals(List.of("inventory", "chest:1,0,2", "chest:1,0,9", "chest:2,1,1", "chest:5,1,1"),
                sourceIds(out), "x sorts first, then y, then z; the inventory leads");
        assertEquals(3, out.get(0).count());
        assertEquals(4, out.get(1).count());
    }

    @Test
    void chestsAreCoordinateSortedEvenWithoutPermission() {
        List<Stock> out = SourcePolicy.visibleStocks(List.of(
                chest(7, 0, 0, "a", 1), chest(0, 9, 0, "a", 1), chest(0, 0, 9, "a", 1)), false, NONE);
        assertEquals(List.of("chest:0,0,9", "chest:0,9,0", "chest:7,0,0"), sourceIds(out));
    }

    /**
     * The canonical name of a double chest (Task 27e): both of its halves - whichever way the
     * question is asked - resolve to the one source position, the smaller by {@link #POSITION_ORDER}.
     */
    @Test
    void aDoubleChestsTwoHalvesShareOneSourcePosition() {
        IntPos left = new IntPos(10, 64, 20);
        IntPos right = new IntPos(11, 64, 20);
        assertEquals(left, SourcePolicy.sharedSource(left, right));
        assertEquals(left, SourcePolicy.sharedSource(right, left),
                "the half that finds the other still names the shared container by its smaller half");
        IntPos upper = new IntPos(10, 65, 20);
        assertEquals(left, SourcePolicy.sharedSource(left, upper));
    }

    @Test
    void noChestsAndNoPermissionIsEmpty() {
        assertEquals(List.of(), SourcePolicy.visibleStocks(List.of(inv("a", 5)), false, NONE));
    }

    @Test
    void theInputListIsNeverMutated() {
        List<Stock> all = new ArrayList<>(List.of(
                chest(9, 0, 0, "a", 1), chest(1, 0, 0, "a", 1), inv("a", 2)));
        List<Stock> before = List.copyOf(all);
        SourcePolicy.visibleStocks(all, true, NONE);
        assertEquals(before, all, "the policy returns a new list and leaves its argument alone");
    }

    @Test
    void anExcludedItemIsDroppedFromTheInventoryToo() {
        List<Stock> out = SourcePolicy.visibleStocks(List.of(
                inv("minecraft:diamond", 5), inv("minecraft:cobblestone", 3)),
                true, Set.of("minecraft:diamond"));
        assertEquals(List.of("inventory"), sourceIds(out));
        assertTrue(out.stream().allMatch(s -> s.itemId().equals("minecraft:cobblestone")),
                "an explicit no is stronger than the inventory permission");
    }

    @Test
    void anExcludedItemIsDroppedFromChestsToo() {
        List<Stock> out = SourcePolicy.visibleStocks(List.of(
                chest(1, 0, 0, "minecraft:diamond", 4),
                chest(2, 0, 0, "minecraft:cobblestone", 2),
                chest(3, 0, 0, "minecraft:diamond", 6)), false, Set.of("minecraft:diamond"));
        assertEquals(List.of("chest:2,0,0"), sourceIds(out),
                "a chest stock of an excluded item is not a supply the job may draw on");
    }

    @Test
    void excludingEverythingLeavesNothing() {
        assertEquals(List.of(), SourcePolicy.visibleStocks(List.of(
                inv("minecraft:diamond", 5), chest(1, 0, 0, "minecraft:diamond", 4)),
                true, Set.of("minecraft:diamond")),
                "excluded and still counted would make the job spend the forbidden item");
    }

    @Test
    void theExclusionOnlyNarrowsTheTakeView() {
        // the give side (terraform leftovers, returned surplus) never consults this view: the same
        // input list, unmutated, is what a give target still sees - only the take order is filtered
        List<Stock> all = new ArrayList<>(List.of(
                inv("minecraft:diamond", 5), chest(1, 0, 0, "minecraft:diamond", 4)));
        List<Stock> before = List.copyOf(all);
        List<Stock> out = SourcePolicy.visibleStocks(all, true, Set.of("minecraft:diamond"));
        assertEquals(List.of(), out);
        assertEquals(before, all, "exclusion removes nothing from the source list itself");
    }
}
