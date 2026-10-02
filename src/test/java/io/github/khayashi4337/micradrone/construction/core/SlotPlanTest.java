package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The slot-copy simulation an {@code apply} proves itself on before touching the world (Task 27e):
 * takes then gives run on a written-off copy of every involved container, so a batch that cannot go
 * through whole is refused without a single real slot changing.
 */
class SlotPlanTest {
    private static final String CHEST_A = "chest:1,0,0";
    private static final String CHEST_B = "chest:2,0,0";

    private static final Predicate<String> ANY = id -> true;
    private static final Predicate<String> NONE = id -> false;

    /** Tests use the item id itself as the merge identity: all stacks of it merge alike. */
    private static SlotPlan.Slot held(String itemId, int count, int maxStack) {
        return new SlotPlan.Slot(itemId, "stack:" + itemId, count, maxStack, ANY);
    }

    private static SlotPlan.Source source(SlotPlan.Slot... slots) {
        return new SlotPlan.Source(List.of(slots), slots.length);
    }

    private static SlotPlan.Source source(List<SlotPlan.Slot> slots, int giveSlots) {
        return new SlotPlan.Source(slots, giveSlots);
    }

    private static SlotPlan.Step take(String sourceId, String itemId, int count) {
        return SlotPlan.Step.take(sourceId, itemId, count);
    }

    private static SlotPlan.Step give(String sourceId, String itemId, int count) {
        return SlotPlan.Step.give(sourceId, itemId, count, "stack:" + itemId, 64);
    }

    // ---- free room is shared across different items -------------------------------------------------

    @Test
    void twoItemsDoNotFitIntoOneSharedEmptySlot() {
        Map<String, SlotPlan.Source> sources = new LinkedHashMap<>();
        sources.put(CHEST_A, source(SlotPlan.Slot.empty(64, ANY)));
        SlotPlan plan = SlotPlan.of(sources, id -> id);
        // each give on its own would fit; both together need two slots
        assertFalse(plan.canApply(List.of(
                give(CHEST_A, "minecraft:dirt", 64), give(CHEST_A, "minecraft:stone", 64))));
        assertEquals(List.of(SlotPlan.Slot.empty(64, ANY)), plan.slots(CHEST_A),
                "a refused batch leaves the copy exactly as it was");
    }

    @Test
    void freeRoomAddsUpAcrossSlotsForOneItem() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A,
                source(List.of(held("minecraft:dirt", 10, 64), SlotPlan.Slot.empty(64, ANY)), 2)),
                id -> id);
        // 54 merge into the held stack, the remaining 16 fill the empty slot
        assertTrue(plan.canApply(List.of(give(CHEST_A, "minecraft:dirt", 70))));
        assertEquals(64, plan.slots(CHEST_A).get(0).count());
        assertEquals(16, plan.slots(CHEST_A).get(1).count());
    }

    // ---- a take must find every item ----------------------------------------------------------------

    @Test
    void aTakeBeyondWhatIsThereFails() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(held("minecraft:dirt", 54, 64))), id -> id);
        assertFalse(plan.canApply(List.of(take(CHEST_A, "minecraft:dirt", 55))));
        assertEquals(54, plan.count(CHEST_A, "minecraft:dirt"), "the copy keeps its stock");
    }

    @Test
    void takeMovesAgainstOneSourceDrawOnTheSamePool() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(held("minecraft:dirt", 54, 64))), id -> id);
        assertFalse(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 30), take(CHEST_A, "minecraft:dirt", 30))),
                "two moves of 30 cannot both draw on one stack of 54");
    }

    // ---- aliases of one container -------------------------------------------------------------------

    @Test
    void twoAliasesOfOneContainerShareTheSameItems() {
        // CHEST_B is the second half of the chest CHEST_A names: both normalize to CHEST_A
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(held("minecraft:dirt", 54, 64))),
                id -> CHEST_B.equals(id) ? CHEST_A : id);
        assertFalse(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 30), take(CHEST_B, "minecraft:dirt", 30))),
                "a double chest's two halves are one 54-slot container, not two");
        assertTrue(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 27), take(CHEST_B, "minecraft:dirt", 27))));
        assertEquals(0, plan.count(CHEST_A, "minecraft:dirt"));
    }

    // ---- a batch that fits --------------------------------------------------------------------------

    @Test
    void aFittingBatchLandsInTheCopyExactly() {
        Map<String, SlotPlan.Source> sources = Map.of(CHEST_A,
                source(List.of(held("minecraft:dirt", 20, 64), SlotPlan.Slot.empty(64, ANY)), 2));
        SlotPlan plan = SlotPlan.of(sources, id -> id);
        // takes run first (slot0 drops to 10), then the give refills it to 64 and 6 land in slot1
        assertTrue(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 10), give(CHEST_A, "minecraft:dirt", 60))));
        assertEquals(64, plan.slots(CHEST_A).get(0).count());
        assertEquals(6, plan.slots(CHEST_A).get(1).count());
        assertEquals(70, plan.count(CHEST_A, "minecraft:dirt"));
    }

    @Test
    void aTakeFreesTheSlotAGiveThenFills() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(held("minecraft:dirt", 64, 64))), id -> id);
        assertTrue(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 64), give(CHEST_A, "minecraft:stone", 64))),
                "the take ran first, so the give may use the freed slot");
        assertEquals(64, plan.count(CHEST_A, "minecraft:stone"));
        assertEquals(0, plan.count(CHEST_A, "minecraft:dirt"));
    }

    // ---- give rules ---------------------------------------------------------------------------------

    @Test
    void aGiveHonoursTheItemsOwnStackCap() {
        // an egg stacks only to 16 even in a 64-cap slot
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A,
                source(SlotPlan.Slot.empty(64, ANY), SlotPlan.Slot.empty(64, ANY))), id -> id);
        assertTrue(plan.canApply(List.of(
                SlotPlan.Step.give(CHEST_A, "minecraft:egg", 20, "stack:egg", 16))));
        assertEquals(16, plan.slots(CHEST_A).get(0).count());
        assertEquals(4, plan.slots(CHEST_A).get(1).count());
    }

    @Test
    void aGiveMergesOnlyIntoTheSameStackKind() {
        // the same item id can still be a different stack (renamed, enchanted): a different key
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(List.of(
                new SlotPlan.Slot("minecraft:dirt", "stack:named", 10, 64, ANY),
                SlotPlan.Slot.empty(64, ANY)), 2)), id -> id);
        assertTrue(plan.canApply(List.of(
                SlotPlan.Step.give(CHEST_A, "minecraft:dirt", 60, "stack:plain", 64))));
        assertEquals(10, plan.slots(CHEST_A).get(0).count(),
                "the named stack is not grown - it is not the same stack");
        assertEquals(60, plan.slots(CHEST_A).get(1).count());
        assertEquals("stack:plain", plan.slots(CHEST_A).get(1).mergeKey());
    }

    @Test
    void aGiveRespectsTheGiveSlotLimit() {
        // an inventory-like source: slot 1 is read by takes but written by no give
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A,
                source(List.of(SlotPlan.Slot.empty(64, ANY), SlotPlan.Slot.empty(64, ANY)), 1)),
                id -> id);
        assertTrue(plan.canApply(List.of(give(CHEST_A, "minecraft:dirt", 64))));
        SlotPlan refused = SlotPlan.of(Map.of(CHEST_A,
                source(List.of(SlotPlan.Slot.empty(64, ANY), SlotPlan.Slot.empty(64, ANY)), 1)),
                id -> id);
        assertFalse(refused.canApply(List.of(
                give(CHEST_A, "minecraft:dirt", 64), give(CHEST_A, "minecraft:stone", 64))),
                "the second give would need slot 1, which no give may write");
    }

    @Test
    void aGiveSlotThatRefusesTheItemStaysEmpty() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(SlotPlan.Slot.empty(64, NONE))), id -> id);
        assertFalse(plan.canApply(List.of(give(CHEST_A, "minecraft:dirt", 1))));
    }

    // ---- unknown sources ----------------------------------------------------------------------------

    @Test
    void anUnknownSourceFailsEveryMove() {
        SlotPlan plan = SlotPlan.of(Map.of(CHEST_A, source(held("minecraft:dirt", 10, 64))), id -> id);
        assertFalse(plan.canApply(List.of(take("chest:9,9,9", "minecraft:dirt", 1))));
        assertFalse(plan.canApply(List.of(give("chest:9,9,9", "minecraft:dirt", 1))));
    }

    // ---- inputs stay untouched -----------------------------------------------------------------------

    @Test
    void theGivenSourcesAreNeverMutated() {
        SlotPlan.Slot held = held("minecraft:dirt", 40, 64);
        List<SlotPlan.Slot> slots = new ArrayList<>(List.of(held, SlotPlan.Slot.empty(64, ANY)));
        SlotPlan.Source source = source(slots, 2);
        Map<String, SlotPlan.Source> sources = new LinkedHashMap<>();
        sources.put(CHEST_A, source);
        SlotPlan plan = SlotPlan.of(sources, id -> id);
        assertTrue(plan.canApply(List.of(
                take(CHEST_A, "minecraft:dirt", 10), give(CHEST_A, "minecraft:stone", 64))));
        assertEquals(List.of(held, SlotPlan.Slot.empty(64, ANY)), source.slots());
        assertEquals(List.of(held, SlotPlan.Slot.empty(64, ANY)), slots,
                "the caller's list is untouched, too");
    }
}
