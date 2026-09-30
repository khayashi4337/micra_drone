package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The material ledger of one job (04 F-7), keyed by ledger key and counted in items, so every step settles only what is
 * still owed: a placement is charged up to its cost, a charge is returned at most once in total, and ground cut by
 * terraforming is given once and taken back at most once. Amounts, not flags, because a crash can make only part of an
 * operation durable (the owner's inventory is saved at once, a supply chest only with its chunk); crash recovery then
 * takes the lost part back out of the ledger ({@link #unrecord}) and the executor settles the rest (Task 9).
 */
public final class MaterialLedger {
    public static final int SCHEMA_VERSION = 1;
    /** No write-ahead-log run has been folded into this ledger yet. */
    public static final long NO_RUN = -1L;

    private final TreeMap<Integer, List<ItemCount>> consumed = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> returned = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> yielded = new TreeMap<>();
    private final TreeMap<Integer, List<ItemCount>> reclaimed = new TreeMap<>();
    private long appliedRun = NO_RUN;

    public boolean isConsumed(int key) {
        return consumed.containsKey(key);
    }

    public List<ItemCount> consumed(int key) {
        return consumed.getOrDefault(key, List.of());
    }

    public void recordConsumed(int key, List<ItemCount> items) {
        add(consumed, key, items);
    }

    public List<ItemCount> returned(int key) {
        return returned.getOrDefault(key, List.of());
    }

    /** What a removal still owes back: charged minus already returned. */
    public List<ItemCount> owedReturn(int key) {
        return ItemCount.minus(consumed(key), returned(key));
    }

    public boolean isReturned(int key) {
        return isConsumed(key) && owedReturn(key).isEmpty();
    }

    public void recordReturned(int key, List<ItemCount> items) {
        requireWithin(items, owedReturn(key), "return", key);
        add(returned, key, items);
    }

    /** Returns everything still owed for the key. */
    public void recordReturned(int key) {
        List<ItemCount> owed = owedReturn(key);
        if (owed.isEmpty()) {
            throw new IllegalStateException("ledger key " + key + " owes nothing back (not charged, or returned already)");
        }
        add(returned, key, owed);
    }

    public boolean isYielded(int key) {
        return yielded.containsKey(key);
    }

    public List<ItemCount> yielded(int key) {
        return yielded.getOrDefault(key, List.of());
    }

    public void recordYield(int key, List<ItemCount> items) {
        add(yielded, key, items);
    }

    public List<ItemCount> reclaimed(int key) {
        return reclaimed.getOrDefault(key, List.of());
    }

    /** What putting the ground back still has to take from the owner: given minus already taken back. */
    public List<ItemCount> owedReclaim(int key) {
        return ItemCount.minus(yielded(key), reclaimed(key));
    }

    public boolean isReclaimed(int key) {
        return isYielded(key) && owedReclaim(key).isEmpty();
    }

    public void recordReclaimed(int key, List<ItemCount> items) {
        requireWithin(items, owedReclaim(key), "reclaim", key);
        add(reclaimed, key, items);
    }

    public void recordReclaimed(int key) {
        List<ItemCount> owed = owedReclaim(key);
        if (owed.isEmpty()) {
            throw new IllegalStateException("ledger key " + key + " cannot be reclaimed (nothing given, or taken back already)");
        }
        add(reclaimed, key, owed);
    }

    /** Records a settled operation of the given kind (the executor's commit, and the replay of a durable log run). */
    public void record(MaterialOp op, int key, List<ItemCount> items) {
        switch (op) {
            case CHARGE -> recordConsumed(key, items);
            case YIELD -> recordYield(key, items);
            case RECLAIM -> recordReclaimed(key, items);
            case RETURN -> recordReturned(key, items);
        }
    }

    /** Crash recovery: adds a durable part of an operation without the owed check (parts may come back in any order). */
    public void replay(MaterialOp op, int key, List<ItemCount> items) {
        add(mapOf(op), key, items);
    }

    /** Crash recovery: takes back the part of an operation that never became durable. */
    public void unrecord(MaterialOp op, int key, List<ItemCount> items) {
        TreeMap<Integer, List<ItemCount>> map = mapOf(op);
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("nothing to take back under ledger key " + key);
        }
        List<ItemCount> held = map.get(key);
        if (held == null) {
            throw new IllegalStateException("ledger key " + key + " holds no " + op + " record to take back");
        }
        if (!ItemCount.minus(items, held).isEmpty()) {
            // taking back more than was recorded would erase the entry and let the operation settle again
            throw new IllegalStateException("ledger key " + key + ": cannot unrecord " + items + ", only " + held
                    + " is recorded");
        }
        List<ItemCount> left = ItemCount.minus(held, items);
        if (left.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, List.copyOf(left));
        }
    }

    private TreeMap<Integer, List<ItemCount>> mapOf(MaterialOp op) {
        return switch (op) {
            case CHARGE -> consumed;
            case YIELD -> yielded;
            case RECLAIM -> reclaimed;
            case RETURN -> returned;
        };
    }

    /** The newest write-ahead-log run already folded into this ledger (a replay skips runs up to it). */
    public long appliedRun() {
        return appliedRun;
    }

    public void markAppliedRun(long run) {
        appliedRun = Math.max(appliedRun, run);
    }

    public SortedMap<Integer, List<ItemCount>> consumedView() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(consumed));
    }

    public SortedMap<Integer, List<ItemCount>> returnedView() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(returned));
    }

    public SortedMap<Integer, List<ItemCount>> yieldedView() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(yielded));
    }

    public SortedMap<Integer, List<ItemCount>> reclaimedView() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(reclaimed));
    }

    private static void add(TreeMap<Integer, List<ItemCount>> map, int key, List<ItemCount> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("a ledger entry needs at least one item");
        }
        // the stored list is immutable: a caller who empties a list it was handed must not settle the key twice
        map.put(key, List.copyOf(ItemCount.plus(map.getOrDefault(key, List.of()), items)));
    }

    private static void requireWithin(List<ItemCount> items, List<ItemCount> owed, String what, int key) {
        if (!ItemCount.minus(items, owed).isEmpty()) {
            throw new IllegalStateException("ledger key " + key + ": cannot " + what + " " + items + ", only " + owed + " is owed");
        }
    }
}
