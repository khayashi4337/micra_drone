package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A written-off copy of the slots of every container a material batch touches (Task 27e).
 * A {@link Move} batch is only allowed to change the world when the same batch can go through
 * whole on this copy: all takes first, then all gives - the order the real mutation keeps. The
 * simulation is what makes {@code giveTarget} and the pre-check of {@code apply} honest:
 * aggregate "total free room" arithmetic cannot see that two different items fight over one empty
 * slot, or that the same stack kind must merge while a renamed one must not.
 *
 * <p>Pure Java: no ItemStack, no Minecraft. The adapter spells each slot as a {@link Slot}
 * (what it holds, its cap, whether it accepts an item) and each move as a {@link Step}; a merge
 * {@code mergeKey} stands in for stack equality - equal keys merge, different keys never do.
 *
 * <p>Two ids may name the same container (both halves of a remembered double chest). The
 * {@code normalizeId} mapping folds them together so the shared stock is never counted twice.
 */
public final class SlotPlan {
    /**
     * One slot as the simulation sees it: the held item's id (null when empty), the stack's merge
     * identity (null when empty - equal keys merge, different keys never do), the held count, the
     * slot's own cap, and which item ids it accepts at all.
     */
    public record Slot(String itemId, String mergeKey, int count, int maxStack,
                       Predicate<String> accepts) {
        public Slot {
            Objects.requireNonNull(accepts, "accepts");
            if (itemId == null && count != 0) {
                throw new IllegalArgumentException("an empty slot cannot hold a count");
            }
        }

        /** An empty slot with the container's cap and acceptance rule. */
        public static Slot empty(int maxStack, Predicate<String> accepts) {
            return new Slot(null, null, 0, maxStack, accepts);
        }
    }

    /**
     * One container's slot list and how many leading slots a give may write. Takes read every
     * slot; gives write only the first {@code giveSlots} - the adapter keeps a player's
     * armor/offhand/crafting slots out of the give range while the takes still see them.
     */
    public record Source(List<Slot> slots, int giveSlots) {
        public Source {
            slots = List.copyOf(slots);
            if (giveSlots < 0 || giveSlots > slots.size()) {
                throw new IllegalArgumentException("giveSlots out of range: " + giveSlots);
            }
        }
    }

    /**
     * One movement on the copy, derived from a {@link Move}: {@code delta < 0} takes from the
     * source, {@code delta > 0} gives into it. A take needs only the item id; a give also carries
     * the stack's merge identity ({@code mergeKey}) and the item's own stack cap ({@code itemMax},
     * 0 for a take).
     */
    public record Step(String sourceId, String itemId, int delta, String mergeKey, int itemMax) {
        public Step {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(itemId, "itemId");
            if (delta == 0) {
                throw new IllegalArgumentException("a step must move something");
            }
        }

        public static Step take(String sourceId, String itemId, int count) {
            if (count <= 0) {
                throw new IllegalArgumentException("a take count must be positive: " + count);
            }
            return new Step(sourceId, itemId, -count, null, 0);
        }

        public static Step give(String sourceId, String itemId, int count, String mergeKey,
                int itemMax) {
            if (count <= 0) {
                throw new IllegalArgumentException("a give count must be positive: " + count);
            }
            Objects.requireNonNull(mergeKey, "mergeKey");
            return new Step(sourceId, itemId, count, mergeKey, itemMax);
        }
    }

    /**
     * The committed copy state: canonical id to that source's current slot list. Each entry is an
     * {@link ArrayList} the simulation rewrites before committing.
     */
    private final Map<String, List<Slot>> committed;
    /** Canonical id to how many leading slots a give may write. */
    private final Map<String, Integer> giveLimits;
    /** Alias to canonical id; ids absent from the map stand for no known source. */
    private final Function<String, String> normalizeId;

    private SlotPlan(Map<String, List<Slot>> committed, Map<String, Integer> giveLimits,
            Function<String, String> normalizeId) {
        this.committed = committed;
        this.giveLimits = giveLimits;
        this.normalizeId = normalizeId;
    }

    /**
     * A plan over the given sources. The sources are COPIED - the caller's lists are never
     * mutated - and {@code normalizeId} folds the aliases (a double chest's two remembered
     * positions) onto the canonical id under which the source was registered.
     */
    public static SlotPlan of(Map<String, Source> sources, Function<String, String> normalizeId) {
        Map<String, List<Slot>> committed = new LinkedHashMap<>();
        Map<String, Integer> giveLimits = new LinkedHashMap<>();
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            committed.put(e.getKey(), new ArrayList<>(e.getValue().slots()));
            giveLimits.put(e.getKey(), e.getValue().giveSlots());
        }
        return new SlotPlan(committed, giveLimits, normalizeId);
    }

    /** The current slot list the copy holds for {@code sourceId} (for a caller's verification). */
    public List<Slot> slots(String sourceId) {
        return List.copyOf(committed.get(normalizeId.apply(sourceId)));
    }

    /** How many of {@code itemId} the copy currently holds under {@code sourceId}. */
    public int count(String sourceId, String itemId) {
        int total = 0;
        for (Slot s : committed.get(normalizeId.apply(sourceId))) {
            if (itemId.equals(s.itemId())) {
                total += s.count();
            }
        }
        return total;
    }

    /**
     * Plays {@code steps} on the copy - every take first, then every give, the same order the
     * real mutation keeps - and answers whether the whole batch went through. On true the copy
     * holds the result; on false nothing changed, and the caller must change nothing either.
     */
    public boolean canApply(List<Step> steps) {
        Map<String, List<Slot>> working = deepCopy(committed);
        for (Step s : steps) {
            if (s.delta() < 0 && !take(working, s)) {
                return false;
            }
        }
        for (Step s : steps) {
            if (s.delta() > 0 && !give(working, s)) {
                return false;
            }
        }
        committed.clear();
        committed.putAll(working);
        return true;
    }

    private static Map<String, List<Slot>> deepCopy(Map<String, List<Slot>> in) {
        Map<String, List<Slot>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Slot>> e : in.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    /** Removes {@code -delta} of the item across the source's slots; false when the stock runs out. */
    private boolean take(Map<String, List<Slot>> working, Step s) {
        List<Slot> slots = working.get(normalizeId.apply(s.sourceId()));
        if (slots == null) {
            return false;
        }
        int left = -s.delta();
        for (int i = 0; i < slots.size() && left > 0; i++) {
            Slot slot = slots.get(i);
            if (!s.itemId().equals(slot.itemId())) {
                continue;
            }
            int n = Math.min(left, slot.count());
            slots.set(i, slot.count() == n
                    ? new Slot(null, null, 0, slot.maxStack(), slot.accepts())
                    : new Slot(slot.itemId(), slot.mergeKey(), slot.count() - n, slot.maxStack(),
                            slot.accepts()));
            left -= n;
        }
        return left == 0;
    }

    /**
     * Places {@code delta} of the item within the source's give range: first merging into stacks
     * of the same kind, then into empty slots that accept it. False when it cannot all fit.
     */
    private boolean give(Map<String, List<Slot>> working, Step s) {
        List<Slot> slots = working.get(normalizeId.apply(s.sourceId()));
        if (slots == null) {
            return false;
        }
        int limit = giveLimits.getOrDefault(normalizeId.apply(s.sourceId()), slots.size());
        int left = s.delta();
        for (int i = 0; i < limit && left > 0; i++) {
            Slot slot = slots.get(i);
            if (slot.itemId() == null || !s.itemId().equals(slot.itemId())
                    || !s.mergeKey().equals(slot.mergeKey())) {
                continue;
            }
            int cap = Math.min(slot.maxStack(), s.itemMax());
            if (slot.count() >= cap) {
                continue;
            }
            int n = Math.min(left, cap - slot.count());
            slots.set(i, new Slot(slot.itemId(), slot.mergeKey(), slot.count() + n, slot.maxStack(),
                    slot.accepts()));
            left -= n;
        }
        for (int i = 0; i < limit && left > 0; i++) {
            Slot slot = slots.get(i);
            if (slot.itemId() != null || !slot.accepts().test(s.itemId())) {
                continue;
            }
            int cap = Math.min(slot.maxStack(), s.itemMax());
            int n = Math.min(left, cap);
            slots.set(i, new Slot(s.itemId(), s.mergeKey(), n, slot.maxStack(), slot.accepts()));
            left -= n;
        }
        return left == 0;
    }
}
