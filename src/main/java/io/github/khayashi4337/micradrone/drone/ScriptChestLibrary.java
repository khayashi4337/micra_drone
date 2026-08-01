package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.khayashi4337.micradrone.drone.net.ScriptEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * The controller's script library containers (issue #7): the plot's controller and corner marker
 * span a square, and any container block (chest, barrel, shulker box - a shulker box makes the
 * library a PORTABLE package, contents and anvil-given name included) standing along either of the
 * two axis lines running from the controller toward the marker's corner is the library - scanned
 * outward up to the same max distance the marker scan itself uses, exactly like
 * {@link CornerMarkerScan#findNearestMatch} (real-machine feedback: requiring the EXACT vertex
 * meant relocating the shulker box every time the plot was resized by moving the marker; scanning
 * the whole axis line means placing it once, anywhere along the way, is enough - resizing the plot
 * afterward never requires moving it again). No marker paired = no library corners (the
 * controller's own scroll slot covers that case). Script scrolls inside appear in the script list,
 * named by their hover name - so a vanilla anvil rename is the rename feature - and are
 * re-resolved by {@code scroll:<chestIndex>:<slot>} id at use time (see {@link ScriptId}), so a
 * stale id after items were moved fails loudly rather than touching the wrong slot. Corner order
 * (same-X-as-marker first) keeps ids deterministic; a double chest is two block entities with 27
 * slots each, so both halves enumerate exactly once.
 * <p>
 * A second, independent source (林さんの要望): each viewing player's OWN inventory, listed and
 * resolved via {@code inv:<slot>} ids (see {@link ScriptId}) - {@link #listInventoryScrolls} etc.
 * take a {@link Player} directly rather than scanning any world position, since inventory contents
 * are inherently per-player, not part of the shared library. Unlike the cached container list
 * ({@code DroneControllerBlockEntity#availableScripts}), inventory entries are computed fresh for
 * whichever player is being sent a snapshot (see {@code pushLogSnapshotTo}) - cheap (36 slots, no
 * world access) and avoids leaking one viewer's inventory contents into another's list.
 */
final class ScriptChestLibrary {
    private ScriptChestLibrary() {
    }

    /** The containers found scanning outward along the plot square's two free axis lines, in corner order. */
    static List<Container> findChests(ServerLevel level, BlockPos controllerPos) {
        Optional<int[]> markerOffset = findMarkerOffset(level, controllerPos);
        if (markerOffset.isEmpty()) {
            return List.of();
        }
        int dirX = Integer.signum(markerOffset.get()[0]);
        int dirZ = Integer.signum(markerOffset.get()[2]);
        List<Container> containers = new ArrayList<>();
        containerAlongAxis(level, controllerPos, dirX, 0).ifPresent(containers::add);
        containerAlongAxis(level, controllerPos, 0, dirZ).ifPresent(containers::add);
        return containers;
    }

    /**
     * The first container found scanning outward from the controller along a single axis
     * (exactly one of {@code dirX}/{@code dirZ} is nonzero), up to
     * {@link DroneControllerBlockEntity#MAX_MARKER_SCAN_DISTANCE}, searching the same +-Y band the
     * marker scan tolerates at each step - nearest level first, deterministic even on uneven terrain.
     */
    private static Optional<Container> containerAlongAxis(ServerLevel level, BlockPos controllerPos, int dirX, int dirZ) {
        for (int dist = 1; dist <= DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE; dist++) {
            int dx = dirX * dist;
            int dz = dirZ * dist;
            for (int yDist = 0; yDist <= DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE; yDist++) {
                for (int dy : yDist == 0 ? new int[]{0} : new int[]{yDist, -yDist}) {
                    if (level.getBlockEntity(controllerPos.offset(dx, dy, dz)) instanceof Container container) {
                        return Optional.of(container);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Package-visible: the sample-library auto-placement (issue #7) reuses this to find the free corners. */
    static Optional<int[]> findMarkerOffset(ServerLevel level, BlockPos controllerPos) {
        return CornerMarkerScan.findNearestMatch(
                (dx, dy, dz) -> level.getBlockState(controllerPos.offset(dx, dy, dz))
                        .is(io.github.khayashi4337.micradrone.MicraDrone.CORNER_MARKER_BLOCK.get()),
                DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE,
                DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE);
    }

    /**
     * Every script scroll in the library - written ones as usual, PLUS blank ones (林さん's request:
     * a blank scroll sitting in the library should be pickable straight from the list, ready to
     * write into, instead of staying invisible until it already has content). Named by hover name
     * either way (anvil renames show up); {@link ScriptEntry#isNew} tells the list which is which.
     */
    static List<ScriptEntry> listScrolls(ServerLevel level, BlockPos controllerPos) {
        List<ScriptEntry> entries = new ArrayList<>();
        List<Container> chests = findChests(level, controllerPos);
        for (int chestIndex = 0; chestIndex < chests.size(); chestIndex++) {
            Container chest = chests.get(chestIndex);
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (!(stack.getItem() instanceof ScriptScrollItem)) {
                    continue;
                }
                String name = stack.getHoverName().getString();
                Optional<String> source = scrollSource(stack);
                String description = source.isPresent()
                        ? ScriptFileStore.describeScript(source.get(), name)
                        : "";
                entries.add(new ScriptEntry(ScriptId.scrollId(chestIndex, slot), name, description, source.isEmpty()));
            }
        }
        return entries;
    }

    /** Resolves a scroll id back to its stack (written OR blank), re-scanning the chests; empty if it no longer points at a scroll. */
    static Optional<ItemStack> resolveScroll(ServerLevel level, BlockPos controllerPos, String scrollId) {
        int chestIndex = ScriptId.scrollChestIndex(scrollId);
        int slot = ScriptId.scrollSlot(scrollId);
        List<Container> chests = findChests(level, controllerPos);
        if (chestIndex < 0 || chestIndex >= chests.size()) {
            return Optional.empty();
        }
        Container chest = chests.get(chestIndex);
        if (slot >= chest.getContainerSize()) {
            return Optional.empty();
        }
        ItemStack stack = chest.getItem(slot);
        return stack.getItem() instanceof ScriptScrollItem ? Optional.of(stack) : Optional.empty();
    }

    /** {@link #resolveScroll}, unwrapped to the scroll's joined script source - empty text (not a missing Optional) for a blank scroll. */
    static Optional<String> resolveScrollSource(ServerLevel level, BlockPos controllerPos, String scrollId) {
        return resolveScroll(level, controllerPos, scrollId).map(stack -> scrollSource(stack).orElse(""));
    }

    /**
     * Writes {@code source} back into the scroll a scroll id points at (the IDE's Save on a chest
     * scroll). False if the id no longer resolves to a written scroll.
     */
    static boolean saveScrollSource(ServerLevel level, BlockPos controllerPos, String scrollId, String source) {
        int chestIndex = ScriptId.scrollChestIndex(scrollId);
        int slot = ScriptId.scrollSlot(scrollId);
        List<Container> chests = findChests(level, controllerPos);
        if (chestIndex < 0 || chestIndex >= chests.size()) {
            return false;
        }
        Container chest = chests.get(chestIndex);
        if (slot >= chest.getContainerSize()) {
            return false;
        }
        ItemStack stack = chest.getItem(slot);
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            return false;
        }
        writeScrollSource(stack, source);
        chest.setChanged();
        return true;
    }

    /**
     * Renames the scroll a scroll id points at - the same effect as a vanilla anvil rename (both set
     * {@code CUSTOM_NAME}, see {@link #listScrolls}), just from the IDE title bar. False if the id
     * no longer resolves to a scroll.
     */
    static boolean renameScroll(ServerLevel level, BlockPos controllerPos, String scrollId, String newName) {
        int chestIndex = ScriptId.scrollChestIndex(scrollId);
        int slot = ScriptId.scrollSlot(scrollId);
        List<Container> chests = findChests(level, controllerPos);
        if (chestIndex < 0 || chestIndex >= chests.size()) {
            return false;
        }
        Container chest = chests.get(chestIndex);
        if (slot >= chest.getContainerSize()) {
            return false;
        }
        ItemStack stack = chest.getItem(slot);
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            return false;
        }
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(newName));
        chest.setChanged();
        return true;
    }

    /** Every script scroll (written or blank) in {@code player}'s own inventory - see the class doc. */
    static List<ScriptEntry> listInventoryScrolls(Player player) {
        List<ScriptEntry> entries = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof ScriptScrollItem)) {
                continue;
            }
            String name = stack.getHoverName().getString();
            Optional<String> source = scrollSource(stack);
            String description = source.isPresent() ? ScriptFileStore.describeScript(source.get(), name) : "";
            entries.add(new ScriptEntry(ScriptId.inventoryScrollId(slot), name, description, source.isEmpty()));
        }
        return entries;
    }

    /** Resolves an inventory scroll id back to its stack, re-checking {@code player}'s current inventory. */
    static Optional<ItemStack> resolveInventoryScroll(Player player, String scrollId) {
        int slot = ScriptId.inventorySlot(scrollId);
        Inventory inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return Optional.empty();
        }
        ItemStack stack = inventory.getItem(slot);
        return stack.getItem() instanceof ScriptScrollItem ? Optional.of(stack) : Optional.empty();
    }

    /** {@link #resolveInventoryScroll}, unwrapped to the scroll's joined script source - empty text for a blank scroll. */
    static Optional<String> resolveInventoryScrollSource(Player player, String scrollId) {
        return resolveInventoryScroll(player, scrollId).map(stack -> scrollSource(stack).orElse(""));
    }

    /** Writes {@code source} back into an inventory scroll. False if the id no longer resolves to a scroll. */
    static boolean saveInventoryScrollSource(Player player, String scrollId, String source) {
        Optional<ItemStack> stack = resolveInventoryScroll(player, scrollId);
        if (stack.isEmpty()) {
            return false;
        }
        writeScrollSource(stack.get(), source);
        return true;
    }

    /** {@link #renameScroll}, for an inventory scroll. */
    static boolean renameInventoryScroll(Player player, String scrollId, String newName) {
        Optional<ItemStack> stack = resolveInventoryScroll(player, scrollId);
        if (stack.isEmpty()) {
            return false;
        }
        stack.get().set(DataComponents.CUSTOM_NAME, Component.literal(newName));
        return true;
    }

    /** Replaces a scroll's pages with {@code source} - also used for the controller's slotted scroll (issue #7). */
    static void writeScrollSource(ItemStack stack, String source) {
        List<Filterable<String>> pages = ScriptScrollContent
                .splitIntoPages(source, WritableBookContent.PAGE_EDIT_LENGTH)
                .stream().map(Filterable::passThrough).toList();
        stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
    }

    /**
     * The joined script text of a written scroll; empty for non-scrolls and blank scrolls.
     * Package-visible: the controller's slotted scroll (issue #7) reads through this too.
     */
    static Optional<String> scrollSource(ItemStack stack) {
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            return Optional.empty();
        }
        WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<String> pages = content.pages().stream().map(Filterable::raw).toList();
        return ScriptScrollContent.isBlank(pages) ? Optional.empty() : Optional.of(ScriptScrollContent.joinPages(pages));
    }
}
