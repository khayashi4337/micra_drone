package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.MaterialPort;
import io.github.khayashi4337.micradrone.construction.core.Move;
import io.github.khayashi4337.micradrone.construction.core.SiteClaim;
import io.github.khayashi4337.micradrone.construction.core.SourcePolicy;
import io.github.khayashi4337.micradrone.construction.core.Stock;
import io.github.khayashi4337.micradrone.construction.core.SupplySettingsBook;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A survival job's materials on the live server (Task 27a, F-7): the owner's inventory - only while the
 * claim's switch allows it - plus every supply chest and barrel inside the claim's box, found by the
 * shared {@link SupplyChests} scan, never by a registry. Takes see sources in {@link SourcePolicy}
 * order (the inventory first when allowed, then chests in coordinate order); gives go to a chest with
 * room first, then the inventory, then nowhere ({@code NO_ROOM}) - items are never dropped.
 * <p>
 * {@link #apply} is atomic from the port's side: every source is resolved and every take and give
 * checked against the world's contents as they are right now, before a single slot changes; a chest
 * broken since the scan refuses the whole batch. An inventory change makes the run dirty; only then
 * does {@link #persist} save the owner with the run's transaction id (a chest-only run has nothing to
 * prove in the player's file - the world save already owns the chests).
 */
final class InventoryMaterials implements MaterialPort {
    /** The tag {@code Entity#getPersistentData} is written under in the saved {@code playerdata/<uuid>.dat}. */
    static final String TX_TAG = "NeoForgeData";
    /** The transaction id saved with the owner's inventory: which WAL run its contents already hold. */
    static final String TX_KEY = "micradrone:lastTx";

    private final MinecraftServer server;
    private final UUID owner;
    /** Null when the claim is gone: no chests, and an unknown claim is never inventory-allowed. */
    private final SiteClaim claim;
    private final SupplyChests chests;
    private final SupplySettingsBook settings;
    /** The transaction id the owner's saved data held, read once (before the next new run). */
    private long savedTx = NO_TX;
    private boolean txRead;
    /** Whether apply changed the owner's inventory; a chest-only run does not need {@link #persist} to write. */
    private boolean dirty;

    InventoryMaterials(MinecraftServer server, UUID owner, SiteClaim claim, SupplyChests chests,
                       SupplySettingsBook settings) {
        this.server = server;
        this.owner = owner;
        this.claim = claim;
        this.chests = chests;
        this.settings = settings;
    }

    @Override
    public List<Stock> stocks(Collection<String> itemIds) {
        List<String> ids = List.copyOf(new TreeSet<>(itemIds));
        List<Stock> all = new ArrayList<>();
        ServerPlayer p = ownerPlayer();
        if (p != null) {
            // counted over every slot: a matching stack can sit in the offhand too
            Inventory inv = p.getInventory();
            for (String id : ids) {
                all.add(new Stock(INVENTORY, id, countOf(inv, id, inv.getContainerSize())));
            }
        }
        ServerLevel level = level();
        if (level != null) {
            for (IntPos pos : chests.positions(claim)) {
                Container c = SupplyChests.containerAt(level, pos);
                if (c == null) {
                    continue;
                }
                String sourceId = MaterialPort.chest(pos);
                for (String id : ids) {
                    all.add(new Stock(sourceId, id, countOf(c, id, c.getContainerSize())));
                }
            }
        }
        return SourcePolicy.visibleStocks(all, inventoryAllowed(), excludedItems());
    }

    @Override
    public Optional<String> giveTarget(List<ItemCount> items) {
        // a give never spends the owner's things, so no permission is needed; the order is chests
        // (coordinate order) first, then the inventory of an online owner, then nowhere at all
        ServerLevel level = level();
        if (level != null) {
            for (IntPos pos : chests.positions(claim)) {
                Container c = SupplyChests.containerAt(level, pos);
                if (c != null && fitsAll(c, items, slotsForGive(c))) {
                    return Optional.of(MaterialPort.chest(pos));
                }
            }
        }
        ServerPlayer p = ownerPlayer();
        if (p != null && fitsAll(p.getInventory(), items, Inventory.INVENTORY_SIZE)) {
            return Optional.of(INVENTORY);
        }
        return Optional.empty();
    }

    @Override
    public boolean apply(List<Move> moves) {
        // every source is resolved against the world as it is now: a chest broken since the scan is a miss
        Map<String, Container> sources = new LinkedHashMap<>();
        for (Move m : moves) {
            if (!sources.containsKey(m.sourceId())) {
                Container c = resolve(m.sourceId());
                if (c == null) {
                    return false;
                }
                sources.put(m.sourceId(), c);
            }
        }
        if (!canApply(moves, sources)) {
            return false;
        }
        // takes first: the give-room the check proved only grows with them, never shrinks
        List<Container> touched = new ArrayList<>();
        for (Move m : moves) {
            if (m.delta() < 0) {
                takeFrom(sources.get(m.sourceId()), m.itemId(), -m.delta(), touched);
            }
        }
        for (Move m : moves) {
            if (m.delta() > 0) {
                giveTo(sources.get(m.sourceId()), m.itemId(), m.delta(), touched);
            }
        }
        dirty = dirty || moves.stream().anyMatch(m -> INVENTORY.equals(m.sourceId()));
        for (Container c : touched) {
            c.setChanged();
        }
        return true;
    }

    @Override
    public void persist(long tx) {
        if (!dirty) {
            // a run that moved only chests has nothing to prove in the player's file
            return;
        }
        ServerPlayer p = ownerPlayer();
        if (p == null) {
            throw new UncheckedIOException(new IOException(
                    "owner " + owner + " went offline before their changed inventory could be saved"));
        }
        p.getPersistentData().putLong(TX_KEY, tx);
        server.getPlayerList().saveAll();
        Path file = playerFile(server, owner);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("the owner's saved inventory could not be forced to the disk", e);
        }
        long onDisk = readTx(file);
        if (onDisk != tx) {
            throw new UncheckedIOException(new IOException(
                    "the saved transaction id reads back " + onDisk + ", expected " + tx));
        }
        dirty = false;
        savedTx = tx;
        txRead = true;
    }

    @Override
    public long durableTx() {
        if (!txRead) {
            savedTx = readTx(playerFile(server, owner));
            txRead = true;
        }
        return savedTx;
    }

    /** The owner's saved playerdata file (shared with {@link RuntimeJobWorld}'s recovery port). */
    static Path playerFile(MinecraftServer server, UUID owner) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(owner + ".dat");
    }

    /** The transaction id a saved playerdata file proves; a missing or torn file proves none. */
    static long readTx(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return NO_TX;
            }
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound(TX_TAG).getLong(TX_KEY);
        } catch (IOException | RuntimeException e) {
            return NO_TX;
        }
    }

    /**
     * Whether every move can go, judged on the sources' contents as they are right now: per source and
     * item, the total take must not exceed what is there and the total give must fit the free room.
     */
    private boolean canApply(List<Move> moves, Map<String, Container> sources) {
        for (Map.Entry<String, Container> src : sources.entrySet()) {
            Map<String, Integer> take = new LinkedHashMap<>();
            Map<String, Integer> give = new LinkedHashMap<>();
            for (Move m : moves) {
                if (m.sourceId().equals(src.getKey())) {
                    (m.delta() < 0 ? take : give).merge(m.itemId(), Math.abs(m.delta()), Integer::sum);
                }
            }
            Container c = src.getValue();
            for (Map.Entry<String, Integer> e : take.entrySet()) {
                if (countOf(c, e.getKey(), c.getContainerSize()) < e.getValue()) {
                    return false;
                }
            }
            for (Map.Entry<String, Integer> e : give.entrySet()) {
                Item item = itemOf(e.getKey());
                if (item == null || item == Items.AIR) {
                    return false;
                }
                if (roomFor(c, item, slotsForGive(c)) < e.getValue()) {
                    return false;
                }
            }
        }
        return true;
    }

    private Container resolve(String sourceId) {
        if (INVENTORY.equals(sourceId)) {
            ServerPlayer p = ownerPlayer();
            return p == null ? null : p.getInventory();
        }
        Optional<IntPos> pos = MaterialPort.chestPos(sourceId);
        ServerLevel level = level();
        if (pos.isEmpty() || level == null) {
            return null;
        }
        return SupplyChests.containerAt(level, pos.get());
    }

    /** The claim's level, or null when it has none or the claim is gone: no chests can be served then. */
    private ServerLevel level() {
        if (claim == null) {
            return null;
        }
        return server.getLevel(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(claim.dimension())));
    }

    private ServerPlayer ownerPlayer() {
        return server.getPlayerList().getPlayer(owner);
    }

    private boolean inventoryAllowed() {
        return claim != null && settings.inventoryAllowed(claim.claimId());
    }

    /**
     * The claim's ruled-out item ids (Task 27b): takes honour them on every source; gives never
     * consult them ({@link #giveTarget} keeps finding room for the excluded item to come back).
     */
    private java.util.Set<String> excludedItems() {
        return claim == null ? java.util.Set.of() : settings.of(claim.claimId()).excludedItems();
    }

    /**
     * The slots a give may write: a container uses all of them, a player inventory only its main
     * compartment - the armor and offhand slots never take building materials here.
     */
    private static int slotsForGive(Container c) {
        return c instanceof Inventory ? Inventory.INVENTORY_SIZE : c.getContainerSize();
    }

    /** Whether the first {@code slots} slots of the container can hold every item of {@code items}. */
    private static boolean fitsAll(Container c, List<ItemCount> items, int slots) {
        for (ItemCount item : items) {
            Item type = itemOf(item.itemId());
            if (type == null || type == Items.AIR || roomFor(c, type, slots) < item.count()) {
                return false;
            }
        }
        return true;
    }

    /** The free room for this item over the first {@code slots} slots: partial stacks plus empty slots. */
    private static int roomFor(Container c, Item item, int slots) {
        ItemStack probe = new ItemStack(item);
        int room = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack s = c.getItem(i);
            int max = c.getMaxStackSize(probe);
            if (s.isEmpty()) {
                if (c.canPlaceItem(i, probe)) {
                    room += max;
                }
            } else if (ItemStack.isSameItemSameComponents(s, probe)) {
                room += Math.max(0, max - s.getCount());
            }
        }
        return room;
    }

    /** How many of the item the first {@code slots} slots hold right now. */
    private static int countOf(Container c, String itemId, int slots) {
        int count = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && itemId.equals(itemIdOf(s))) {
                count += s.getCount();
            }
        }
        return count;
    }

    /** Removes up to {@code count} of the item; {@link #canApply} has already proved there is enough. */
    private static void takeFrom(Container c, String itemId, int count, List<Container> touched) {
        int left = count;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && itemId.equals(itemIdOf(s))) {
                left -= c.removeItem(i, Math.min(left, s.getCount())).getCount();
            }
        }
        touched.add(c);
    }

    /** Adds the item into matching stacks first, then empty slots; the room was proved by the check. */
    private static void giveTo(Container c, String itemId, int count, List<Container> touched) {
        ItemStack probe = new ItemStack(itemOf(itemId));
        int max = c.getMaxStackSize(probe);
        int slots = slotsForGive(c);
        int left = count;
        for (int i = 0; i < slots && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, probe) && s.getCount() < max) {
                int k = Math.min(left, max - s.getCount());
                s.grow(k);
                c.setItem(i, s);
                left -= k;
            }
        }
        for (int i = 0; i < slots && left > 0; i++) {
            if (c.getItem(i).isEmpty() && c.canPlaceItem(i, probe)) {
                int k = Math.min(left, max);
                c.setItem(i, probe.copyWithCount(k));
                left -= k;
            }
        }
        touched.add(c);
    }

    private static Item itemOf(String itemId) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
    }

    private static String itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
