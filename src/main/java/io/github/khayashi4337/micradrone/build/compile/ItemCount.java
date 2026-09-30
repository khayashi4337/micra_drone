package io.github.khayashi4337.micradrone.build.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** A number of one item (an item id, not a block id). */
public record ItemCount(String itemId, int count) {
    public ItemCount {
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("an item count must be positive: " + count);
        }
    }

    /** The items of both lists added up, in item-id order. */
    public static List<ItemCount> plus(List<ItemCount> a, List<ItemCount> b) {
        TreeMap<String, Long> sum = new TreeMap<>();
        addAll(sum, a);
        addAll(sum, b);
        return list(sum);
    }

    /** What is left of {@code a} after taking {@code b} away, never below zero, in item-id order (what is still owed). */
    public static List<ItemCount> minus(List<ItemCount> a, List<ItemCount> b) {
        TreeMap<String, Long> sum = new TreeMap<>();
        addAll(sum, a);
        for (ItemCount c : b) {
            sum.merge(c.itemId(), -(long) c.count(), Long::sum);
        }
        return list(sum);
    }

    /**
     * Adds each item's count to its running total in {@code sum}, kept as a long so a total past the int
     * range is refused instead of wrapping into a small or negative count (a manifest counts items in int).
     * Shared by {@link BlockToItem#merge} and {@link BomCalculator#bom}.
     */
    static void addAll(TreeMap<String, Long> sum, Iterable<ItemCount> items) {
        for (ItemCount c : items) {
            addTo(sum, c);
        }
    }

    /** Adds one item's count to its running total (see {@link #addAll}). */
    static void addTo(TreeMap<String, Long> sum, ItemCount c) {
        long total = sum.merge(c.itemId(), (long) c.count(), Long::sum);
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("item total exceeds int range: " + c.itemId());
        }
    }

    private static List<ItemCount> list(TreeMap<String, Long> sum) {
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> {
            if (n > 0) {
                out.add(new ItemCount(id, n.intValue()));
            }
        });
        return out;
    }
}
