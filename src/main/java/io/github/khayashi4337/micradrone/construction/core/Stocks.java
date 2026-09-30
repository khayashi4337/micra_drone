package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where a material operation takes its items from (04 F-7): the sources in the order the port lists them (the owner's
 * inventory first, then the supply chests in their registration order). Pure, so the moves are decided before any item
 * changes hands and can be logged exactly.
 */
public final class Stocks {
    private Stocks() {
    }

    public static TreeSet<String> ids(List<ItemCount> items) {
        TreeSet<String> out = new TreeSet<>();
        items.forEach(c -> out.add(c.itemId()));
        return out;
    }

    public static List<ItemCount> missing(List<ItemCount> need, List<Stock> stocks) {
        List<ItemCount> have = new ArrayList<>();
        stocks.forEach(s -> {
            if (s.count() > 0) {
                have.add(new ItemCount(s.itemId(), s.count()));
            }
        });
        return ItemCount.minus(need, have);
    }

    /** The takes that cover {@code need}, drawing each item from the sources in order. Throws when the stock is short. */
    public static List<Move> takeMoves(List<ItemCount> need, List<Stock> stocks) {
        List<ItemCount> short_ = missing(need, stocks);
        if (!short_.isEmpty()) {
            throw new IllegalArgumentException("not enough " + short_);
        }
        List<Move> out = new ArrayList<>();
        for (ItemCount c : ItemCount.plus(need, List.of())) {
            int left = c.count();
            for (Stock s : stocks) {
                if (left == 0) {
                    break;
                }
                if (s.itemId().equals(c.itemId()) && s.count() > 0) {
                    int n = Math.min(left, s.count());
                    out.add(new Move(s.sourceId(), s.itemId(), -n));
                    left -= n;
                }
            }
        }
        return out;
    }

    public static List<Move> giveMoves(List<ItemCount> items, String target) {
        List<Move> out = new ArrayList<>();
        for (ItemCount c : ItemCount.plus(items, List.of())) {
            out.add(new Move(target, c.itemId(), c.count()));
        }
        return out;
    }

    /** The items the moves carry, whatever the direction, added up per item. */
    public static List<ItemCount> items(List<Move> moves) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        moves.forEach(m -> sum.merge(m.itemId(), Math.abs(m.delta()), Integer::sum));
        List<ItemCount> out = new ArrayList<>();
        sum.forEach((id, n) -> out.add(new ItemCount(id, n)));
        return out;
    }
}
