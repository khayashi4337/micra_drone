package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import io.github.khayashi4337.micradrone.drone.net.ScriptEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * The controller's script library chests (issue #6): any container block touching one of the six
 * faces of the controller block or of its paired corner marker. Script scrolls inside them appear
 * in the script list alongside the on-disk files (see DroneControllerBlockEntity), named by their
 * hover name - so a vanilla anvil rename is the rename feature - and are re-resolved by
 * {@code scroll:<chestIndex>:<slot>} id at use time (see {@link ScriptId}), so a stale id after
 * items were moved fails loudly rather than touching the wrong slot. Chest order is deterministic
 * (position-sorted); a double chest is two block entities with 27 slots each, so enumerating block
 * entities covers every slot exactly once.
 */
final class ScriptChestLibrary {
    private ScriptChestLibrary() {
    }

    /** All library containers, position-sorted so scroll ids stay stable while the chests stand still. */
    static List<Container> findChests(ServerLevel level, BlockPos controllerPos) {
        TreeMap<Long, Container> byPosition = new TreeMap<>();
        collectAdjacentContainers(level, controllerPos, byPosition);
        findMarkerPos(level, controllerPos).ifPresent(markerPos -> collectAdjacentContainers(level, markerPos, byPosition));
        return List.copyOf(byPosition.values());
    }

    private static void collectAdjacentContainers(ServerLevel level, BlockPos anchor, TreeMap<Long, Container> out) {
        for (Direction direction : Direction.values()) {
            BlockPos pos = anchor.relative(direction);
            if (level.getBlockEntity(pos) instanceof Container container) {
                out.putIfAbsent(pos.asLong(), container);
            }
        }
    }

    private static Optional<BlockPos> findMarkerPos(ServerLevel level, BlockPos controllerPos) {
        return CornerMarkerScan.findNearestMatch(
                        (dx, dy, dz) -> level.getBlockState(controllerPos.offset(dx, dy, dz))
                                .is(io.github.khayashi4337.micradrone.MicraDrone.CORNER_MARKER_BLOCK.get()),
                        DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE,
                        DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE)
                .map(offset -> controllerPos.offset(offset[0], offset[1], offset[2]));
    }

    /** Every non-blank scroll in the library, as list entries named by hover name (anvil renames show up). */
    static List<ScriptEntry> listScrolls(ServerLevel level, BlockPos controllerPos) {
        List<ScriptEntry> entries = new ArrayList<>();
        List<Container> chests = findChests(level, controllerPos);
        for (int chestIndex = 0; chestIndex < chests.size(); chestIndex++) {
            Container chest = chests.get(chestIndex);
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                Optional<String> source = scrollSource(stack);
                if (source.isPresent()) {
                    String name = stack.getHoverName().getString();
                    entries.add(new ScriptEntry(ScriptId.scrollId(chestIndex, slot), name,
                            ScriptFileStore.describeScript(source.get(), name)));
                }
            }
        }
        return entries;
    }

    /** Resolves a scroll id back to its stack, re-scanning the chests; empty if it no longer points at a written scroll. */
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
        return scrollSource(stack).isPresent() ? Optional.of(stack) : Optional.empty();
    }

    /** {@link #resolveScroll}, unwrapped straight to the scroll's joined script source. */
    static Optional<String> resolveScrollSource(ServerLevel level, BlockPos controllerPos, String scrollId) {
        return resolveScroll(level, controllerPos, scrollId).flatMap(ScriptChestLibrary::scrollSource);
    }

    /**
     * Writes {@code source} back into the scroll a scroll id points at (the IDE's Save on a chest
     * scroll). False if the id no longer resolves to a written scroll.
     */
    static boolean saveScrollSource(ServerLevel level, BlockPos controllerPos, String scrollId, String source) {
        Optional<ItemStack> stack = resolveScroll(level, controllerPos, scrollId);
        if (stack.isEmpty()) {
            return false;
        }
        writeScrollSource(stack.get(), source);
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
