package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A fake owner inventory (and optional supply chest) for the executor's tests. It keeps what is "on disk" apart from
 * what is live: the inventory and its transaction id reach the disk only through {@link #persist(long)}, the chest only
 * through {@link #saveChests()} (a chunk save), so {@link #restart()} shows what a crash would leave. {@code hook} runs
 * before and after every change, which the crash tests use to stop at every step. {@code room} caps how many items one
 * source holds (a full inventory), so a give that does not fit can be tried.
 */
public final class FakeMaterials implements MaterialPort {
    public static final String CHEST = CHEST_PREFIX + "5,64,5";
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private final LinkedHashMap<String, TreeMap<String, Integer>> live = new LinkedHashMap<>();
    private final LinkedHashMap<String, TreeMap<String, Integer>> disk = new LinkedHashMap<>();
    private long diskTx = NO_TX;
    private long loadedTx = NO_TX;
    public Runnable hook = () -> {
    };
    public boolean ownerOnline = true;
    public int room = UNLIMITED;
    /** Test injection: the next {@link #apply} is refused and changes nothing (a post-check failure). */
    public boolean failNextApply = false;

    public FakeMaterials() {
        live.put(INVENTORY, new TreeMap<>());
        disk.put(INVENTORY, new TreeMap<>());
    }

    /** Test setup: items that are already saved (live and on disk). */
    public FakeMaterials with(String item, int count) {
        return add(INVENTORY, item, count);
    }

    public FakeMaterials withChest(String item, int count) {
        return add(CHEST, item, count);
    }

    private FakeMaterials add(String source, String item, int count) {
        live.computeIfAbsent(source, k -> new TreeMap<>()).merge(item, count, Integer::sum);
        disk.computeIfAbsent(source, k -> new TreeMap<>()).merge(item, count, Integer::sum);
        return this;
    }

    public int count(String item) {
        return live.get(INVENTORY).getOrDefault(item, 0);
    }

    public int chestCount(String item) {
        return live.getOrDefault(CHEST, new TreeMap<>()).getOrDefault(item, 0);
    }

    /** Everything the owner can use: the inventory and every chest. */
    public int total(String item) {
        return live.values().stream().mapToInt(m -> m.getOrDefault(item, 0)).sum();
    }

    private int held(String source) {
        return live.getOrDefault(source, new TreeMap<>()).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public List<Stock> stocks(Collection<String> itemIds) {
        List<Stock> out = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, Integer>> source : live.entrySet()) {
            for (String id : new java.util.TreeSet<>(itemIds)) {
                out.add(new Stock(source.getKey(), id, source.getValue().getOrDefault(id, 0)));
            }
        }
        return out;
    }

    @Override
    public Optional<String> giveTarget(List<ItemCount> items) {
        int n = items.stream().mapToInt(ItemCount::count).sum();
        List<String> order = new ArrayList<>();
        if (ownerOnline) {
            order.add(INVENTORY);
        }
        if (live.containsKey(CHEST)) {
            order.add(CHEST);
        }
        for (String source : order) {
            if (room == UNLIMITED || held(source) + n <= room) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean apply(List<Move> moves) {
        if (failNextApply) {
            failNextApply = false;
            return false;
        }
        hook.run();
        for (Move m : moves) {
            int have = live.computeIfAbsent(m.sourceId(), k -> new TreeMap<>()).getOrDefault(m.itemId(), 0);
            if (have + m.delta() < 0) {
                return false;
            }
        }
        for (Move m : moves) {
            live.get(m.sourceId()).merge(m.itemId(), m.delta(), Integer::sum);
        }
        hook.run();
        return true;
    }

    @Override
    public void persist(long tx) {
        hook.run();
        // the inventory and its transaction id are one file, written in one piece
        disk.put(INVENTORY, new TreeMap<>(live.get(INVENTORY)));
        diskTx = tx;
        hook.run();
    }

    @Override
    public long durableTx() {
        return loadedTx;
    }

    /** The owner picks something up or drops it (between runs): the inventory changes outside any ledger operation. */
    public void ownerChanges(String item, int delta) {
        live.get(INVENTORY).merge(item, delta, Integer::sum);
    }

    /** A player save that is not ours (an autosave, a logout): the inventory with the last transaction id it holds. */
    public void saveOwner() {
        disk.put(INVENTORY, new TreeMap<>(live.get(INVENTORY)));
    }

    /** The chunk holding the chest was saved. */
    public void saveChests() {
        for (String source : live.keySet()) {
            if (!source.equals(INVENTORY)) {
                disk.put(source, new TreeMap<>(live.get(source)));
            }
        }
    }

    /** What a restart finds: the saved state only, and the saved transaction id as the loaded one. */
    public FakeMaterials restart() {
        FakeMaterials m = new FakeMaterials();
        m.live.clear();
        m.disk.clear();
        disk.forEach((k, v) -> {
            m.live.put(k, new TreeMap<>(v));
            m.disk.put(k, new TreeMap<>(v));
        });
        m.diskTx = diskTx;
        m.loadedTx = diskTx;
        m.room = room;
        return m;
    }
}
