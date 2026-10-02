package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.MaterialPort;
import io.github.khayashi4337.micradrone.construction.core.Move;
import io.github.khayashi4337.micradrone.construction.core.SiteClaim;
import io.github.khayashi4337.micradrone.construction.core.SlotPlan;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
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
 * {@link #apply} is atomic from the port's side: every source is resolved against the world as it is
 * right now, and the whole batch - takes first, then gives - is played out on a written-off slot
 * copy ({@link SlotPlan}) before a single real slot changes; a chest broken since the scan, or a
 * batch that does not fit, refuses everything. Remembered chest ids are normalized to the position
 * {@link SupplyChests#sourceAt} names, so a container merged from two halves is one source, never
 * two. An inventory change makes the run dirty; only then does {@link #persist} save the owner with
 * the run's transaction id (a chest-only run has nothing to prove in the player's file - the world
 * save already owns the chests).
 */
final class InventoryMaterials implements MaterialPort {
    /** The tag {@code Entity#getPersistentData} is written under in the saved {@code playerdata/<uuid>.dat}. */
    static final String TX_TAG = "NeoForgeData";
    /** The transaction id saved with the owner's inventory: which WAL run its contents already hold. */
    static final String TX_KEY = "micradrone:lastTx";
    /** The placeholder source id a give-target check plays its copy-steps under. */
    private static final String GIVE_TARGET = "give-target";
    /** The prefix of the merge identities a snapshot hands out (see {@link #mergeKeyOf}). */
    private static final String MERGE_KEY = "stack#";

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
            Set<IntPos> seen = new HashSet<>();
            for (IntPos pos : chests.positions(claim)) {
                SupplyChests.ResolvedSource r = SupplyChests.sourceAt(level, pos, claim.worldBox());
                if (r == null || !seen.add(r.position())) {
                    // gone, refused for reaching outside the box, or another remembered position
                    // already named the same merged container - counted once, or not at all
                    continue;
                }
                String sourceId = MaterialPort.chest(r.position());
                for (String id : ids) {
                    all.add(new Stock(sourceId, id,
                            countOf(r.container(), id, r.container().getContainerSize())));
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
            Set<IntPos> seen = new HashSet<>();
            for (IntPos pos : chests.positions(claim)) {
                SupplyChests.ResolvedSource r = SupplyChests.sourceAt(level, pos, claim.worldBox());
                if (r == null || !seen.add(r.position())) {
                    continue;
                }
                if (canGive(r.container(), items)) {
                    return Optional.of(MaterialPort.chest(r.position()));
                }
            }
        }
        ServerPlayer p = ownerPlayer();
        if (p != null && canGive(p.getInventory(), items)) {
            return Optional.of(INVENTORY);
        }
        return Optional.empty();
    }

    @Override
    public boolean apply(List<Move> moves) {
        // every source is resolved against the world as it is now - a chest broken since the scan is
        // a miss - and each remembered chest id is folded onto the source position the container
        // answers to: two ids that name the halves of one large chest are the same source
        Map<String, String> normalized = new LinkedHashMap<>();
        Map<String, Container> sources = new LinkedHashMap<>();
        for (Move m : moves) {
            if (normalized.containsKey(m.sourceId())) {
                continue;
            }
            Resolved r = resolve(m.sourceId());
            if (r == null) {
                return false;
            }
            normalized.put(m.sourceId(), r.sourceId());
            sources.putIfAbsent(r.sourceId(), r.container());
        }
        // the whole batch is played out on a written-off copy of the slots first: one miss refuses all
        Map<String, SlotPlan.Source> copies = new LinkedHashMap<>();
        Map<String, List<ItemStack>> mergeReps = new LinkedHashMap<>();
        for (Map.Entry<String, Container> e : sources.entrySet()) {
            List<ItemStack> reps = new ArrayList<>();
            copies.put(e.getKey(), snapshot(e.getValue(), reps));
            mergeReps.put(e.getKey(), reps);
        }
        List<SlotPlan.Step> steps = new ArrayList<>(moves.size());
        for (Move m : moves) {
            SlotPlan.Step step = stepOf(normalized.get(m.sourceId()), m,
                    mergeReps.get(normalized.get(m.sourceId())));
            if (step == null) {
                return false;
            }
            steps.add(step);
        }
        if (!SlotPlan.of(copies, Function.identity()).canApply(steps)) {
            return false;
        }
        // takes first: the give-room the copy proved only grows with them, never shrinks
        List<Container> touched = new ArrayList<>();
        for (Move m : moves) {
            if (m.delta() < 0) {
                int left = takeFrom(sources.get(normalized.get(m.sourceId())), m.itemId(),
                        -m.delta(), touched);
                if (left != 0) {
                    throw new IllegalStateException(
                            "the copy proved " + m + " but the world refused " + left + " of it");
                }
            }
        }
        for (Move m : moves) {
            if (m.delta() > 0) {
                int left = giveTo(sources.get(normalized.get(m.sourceId())), m.itemId(),
                        m.delta(), touched);
                if (left != 0) {
                    throw new IllegalStateException(
                            "the copy proved " + m + " but the world refused " + left + " of it");
                }
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

    /** What a source id names right now: the live container and the normalized id its aliases share. */
    private record Resolved(Container container, String sourceId) {
    }

    private Resolved resolve(String sourceId) {
        if (INVENTORY.equals(sourceId)) {
            ServerPlayer p = ownerPlayer();
            return p == null ? null : new Resolved(p.getInventory(), INVENTORY);
        }
        Optional<IntPos> pos = MaterialPort.chestPos(sourceId);
        ServerLevel level = level();
        if (pos.isEmpty() || level == null) {
            return null;
        }
        SupplyChests.ResolvedSource r = SupplyChests.sourceAt(level, pos.get(), claim.worldBox());
        return r == null ? null : new Resolved(r.container(), MaterialPort.chest(r.position()));
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

    /**
     * Whether the container can hold every item of {@code items} at once, decided on a slot-level
     * copy: the items share the same free slots and merge rules instead of each counting the empty
     * room alone, so two stacks of 64 can never both be offered one empty slot.
     */
    private static boolean canGive(Container c, List<ItemCount> items) {
        List<ItemStack> mergeReps = new ArrayList<>();
        SlotPlan.Source copy = snapshot(c, mergeReps);
        List<SlotPlan.Step> steps = new ArrayList<>(items.size());
        for (ItemCount item : items) {
            SlotPlan.Step step = stepOf(GIVE_TARGET,
                    new Move(GIVE_TARGET, item.itemId(), item.count()), mergeReps);
            if (step == null) {
                return false;
            }
            steps.add(step);
        }
        return SlotPlan.of(Map.of(GIVE_TARGET, copy), Function.identity()).canApply(steps);
    }

    /**
     * The container's slots written off for the simulation (Task 27e): what each slot holds, its
     * cap (the container's own max stack size), whether it accepts an item id at all, and - for a
     * held stack - its merge identity, so the copy merges only where
     * {@link ItemStack#isSameItemSameComponents} would. {@code mergeReps} keeps one representative
     * stack per distinct stack kind, shared with the steps built after it.
     */
    private static SlotPlan.Source snapshot(Container c, List<ItemStack> mergeReps) {
        List<SlotPlan.Slot> slots = new ArrayList<>(c.getContainerSize());
        int max = c.getMaxStackSize();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            Predicate<String> accepts = acceptsAt(c, i);
            slots.add(s.isEmpty() ? SlotPlan.Slot.empty(max, accepts)
                    : new SlotPlan.Slot(itemIdOf(s), mergeKeyOf(s, mergeReps), s.getCount(), max,
                            accepts));
        }
        return new SlotPlan.Source(slots, slotsForGive(c));
    }

    /** Whether slot {@code index} accepts the named item (an unknown id or AIR is refused). */
    private static Predicate<String> acceptsAt(Container c, int index) {
        return id -> {
            Item item = itemOf(id);
            return item != null && item != Items.AIR && c.canPlaceItem(index, new ItemStack(item));
        };
    }

    /**
     * The merge identity of a stack within one simulation: stacks equal by
     * {@link ItemStack#isSameItemSameComponents} share a key, a renamed or enchanted stack keeps
     * its own - so the copy never treats different stack kinds as the same free room.
     */
    private static String mergeKeyOf(ItemStack stack, List<ItemStack> mergeReps) {
        for (int i = 0; i < mergeReps.size(); i++) {
            if (ItemStack.isSameItemSameComponents(mergeReps.get(i), stack)) {
                return MERGE_KEY + i;
            }
        }
        mergeReps.add(stack.copy());
        return MERGE_KEY + (mergeReps.size() - 1);
    }

    /**
     * The simulated form of one move on an already-normalized source. A give of an item id the
     * game does not have is no step at all (null): the caller refuses the whole batch for it.
     */
    private static SlotPlan.Step stepOf(String sourceId, Move m, List<ItemStack> mergeReps) {
        if (m.delta() < 0) {
            return SlotPlan.Step.take(sourceId, m.itemId(), -m.delta());
        }
        Item item = itemOf(m.itemId());
        if (item == null || item == Items.AIR) {
            return null;
        }
        ItemStack probe = new ItemStack(item);
        return SlotPlan.Step.give(sourceId, m.itemId(), m.delta(), mergeKeyOf(probe, mergeReps),
                probe.getMaxStackSize());
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

    /**
     * Removes up to {@code count} of the item and answers what could NOT be taken. The slot copy
     * already proved the whole amount, so a nonzero remainder contradicts it - the caller throws.
     */
    private static int takeFrom(Container c, String itemId, int count, List<Container> touched) {
        int left = count;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && itemId.equals(itemIdOf(s))) {
                left -= c.removeItem(i, Math.min(left, s.getCount())).getCount();
            }
        }
        touched.add(c);
        return left;
    }

    /**
     * Adds the item into matching stacks first, then empty slots, and answers what could NOT fit.
     * The slot copy already proved the whole amount, so a nonzero remainder contradicts it - the
     * caller throws.
     */
    private static int giveTo(Container c, String itemId, int count, List<Container> touched) {
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
        return left;
    }

    private static Item itemOf(String itemId) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
    }

    private static String itemIdOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
