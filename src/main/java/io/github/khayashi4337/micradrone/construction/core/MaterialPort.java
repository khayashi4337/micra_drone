package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The owner's materials (inventory and supply chests, F-7), called on the main thread only. The pure core decides the
 * exact moves ({@link Stocks}); the port only counts, applies, and saves the owner's inventory on request. Items are
 * never dropped on the ground inside a material operation: a give goes only to a source with room for all of it.
 */
public interface MaterialPort {
    /** The owner's inventory; its changes are made durable by {@link #persist(long)}. */
    String INVENTORY = "inventory";
    /** Supply chests ({@code chest:x,y,z}); their changes are durable only at a durable point (a flushed save). */
    String CHEST_PREFIX = "chest:";
    /** The creative stand-in: holds everything, keeps nothing. */
    String FREE_SOURCE = "free";
    /** No transaction has been saved with the owner's data yet. */
    long NO_TX = 0L;

    MaterialPort FREE = new MaterialPort() {
        @Override
        public List<Stock> stocks(Collection<String> itemIds) {
            List<Stock> out = new ArrayList<>();
            itemIds.forEach(id -> out.add(new Stock(FREE_SOURCE, id, Integer.MAX_VALUE)));
            return out;
        }

        @Override
        public Optional<String> giveTarget(List<ItemCount> items) {
            return Optional.of(FREE_SOURCE);
        }

        @Override
        public boolean apply(List<Move> moves) {
            return true;
        }

        @Override
        public void persist(long tx) {
        }

        @Override
        public long durableTx() {
            return NO_TX;
        }
    };

    /** The source id of the supply chest at {@code pos}. */
    static String chest(IntPos pos) {
        return CHEST_PREFIX + pos.x() + "," + pos.y() + "," + pos.z();
    }

    /** The position of a supply chest's source id; empty for the inventory and the free source. */
    static Optional<IntPos> chestPos(String sourceId) {
        if (!sourceId.startsWith(CHEST_PREFIX)) {
            return Optional.empty();
        }
        String[] xyz = sourceId.substring(CHEST_PREFIX.length()).split(",");
        if (xyz.length != 3) {
            throw new IllegalArgumentException("not a chest source: " + sourceId);
        }
        return Optional.of(new IntPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
    }

    /** Every source's count of these items, sources in the order items are drawn from (the inventory first). */
    List<Stock> stocks(Collection<String> itemIds);

    /**
     * A source with room for all of these items (the owner's inventory while online, else a supply chest), or empty:
     * then the caller pauses (NO_ROOM) instead of dropping anything.
     */
    Optional<String> giveTarget(List<ItemCount> items);

    /** Applies exactly these moves; false, with nothing changed, if a source is short or a target has no room. */
    boolean apply(List<Move> moves);

    /**
     * Saves the owner's inventory now, synchronously, together with the transaction id {@code tx} in the owner's own
     * saved data (the same file, written in one piece), so recovery can tell from the id alone whether the inventory
     * on disk holds the moves of run {@code tx}. Throws if the save cannot be confirmed.
     */
    void persist(long tx);

    /** The transaction id the owner's saved data held when it was loaded (read once, before any new run). */
    long durableTx();
}
