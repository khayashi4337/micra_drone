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
        TreeMap<String, Integer> sum = new TreeMap<>();
        a.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        b.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        return list(sum);
    }

    /** What is left of {@code a} after taking {@code b} away, never below zero, in item-id order (what is still owed). */
    public static List<ItemCount> minus(List<ItemCount> a, List<ItemCount> b) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        a.forEach(c -> sum.merge(c.itemId(), c.count(), Integer::sum));
        b.forEach(c -> sum.merge(c.itemId(), -c.count(), Integer::sum));
        return list(sum);
    }

    private static List<ItemCount> list(TreeMap<String, Integer> sum) {
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> {
            if (n > 0) {
                out.add(new ItemCount(id, n));
            }
        });
        return out;
    }
}
