package io.github.khayashi4337.micradrone.construction;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import io.github.khayashi4337.micradrone.construction.core.PlaceResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Every world write of the construction goes through here, with the owner as the acting player so protection mods can
 * refuse it (04 F-4 (c), S-9). A placement is captured like vanilla's BlockItem (CommonHooks.onPlaceItemIntoWorld): the
 * block is set while snapshots are captured, the place event decides, and only an accepted placement runs onPlace and the
 * neighbour updates. A removal posts the break event first, drops the contents it holds, and changes the block without
 * neighbour shape updates or drops; {@link #settle} runs the neighbour updates once a whole piece is removed.
 */
public final class PlacementGuard {
    private final MinecraftServer server;
    private final Function<UUID, String> ownerName;

    public PlacementGuard(MinecraftServer server, Function<UUID, String> ownerName) {
        this.server = server;
        this.ownerName = ownerName;
    }

    public ServerPlayer actor(ServerLevel level, UUID owner) {
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        return online != null ? online : FakePlayerFactory.get(level, new GameProfile(owner, ownerName.apply(owner)));
    }

    /** A removal changes one position quietly: no neighbour shape updates (16), no drops (32); settle() updates later. */
    static final int QUIET_REMOVAL_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    /** Same recursion budget vanilla passes when it replays captured placements (CommonHooks.onPlaceItemIntoWorld). */
    static final int NEIGHBOUR_UPDATE_DEPTH = 512;

    public PlaceResult place(ServerLevel level, BlockPos pos, BlockState state, UUID owner) {
        level.captureBlockSnapshots = true;
        boolean set;
        try {
            set = level.setBlock(pos, state, Block.UPDATE_ALL);
        } finally {
            level.captureBlockSnapshots = false;
        }
        List<BlockSnapshot> snapshots = new ArrayList<>(level.capturedBlockSnapshots);
        level.capturedBlockSnapshots.clear();
        if (!set || snapshots.isEmpty()) {
            return PlaceResult.INVALID;
        }
        if (level.getBlockState(pos).getBlock() != state.getBlock()) {
            undo(level, snapshots);
            return PlaceResult.INVALID;
        }
        ServerPlayer actor = actor(level, owner);
        boolean cancelled = snapshots.size() > 1 ? EventHooks.onMultiBlockPlace(actor, snapshots, Direction.UP)
                : EventHooks.onBlockPlace(actor, snapshots.get(0), Direction.UP);
        if (cancelled) {
            undo(level, snapshots);
            return PlaceResult.DENIED;
        }
        for (BlockSnapshot snap : snapshots) {
            BlockState old = snap.getState();
            BlockState now = level.getBlockState(snap.getPos());
            now.onPlace(level, snap.getPos(), old, false);
            level.markAndNotifyBlock(snap.getPos(), level.getChunkAt(snap.getPos()), old, now, snap.getFlags(),
                    NEIGHBOUR_UPDATE_DEPTH);
        }
        return PlaceResult.PLACED;
    }

    private static void undo(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (BlockSnapshot snap : Lists.reverse(snapshots)) {
            level.restoringBlockSnapshots = true;
            try {
                snap.restore(snap.getFlags() | Block.UPDATE_CLIENTS);
            } finally {
                level.restoringBlockSnapshots = false;
            }
        }
    }

    public PlaceResult restore(ServerLevel level, BlockPos pos, BlockState state, UUID owner, boolean dropContentsFirst) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), actor(level, owner));
        if (NeoForge.EVENT_BUS.post(event).isCanceled()) {
            return PlaceResult.DENIED;
        }
        // the items are held, not dropped, until the block is really gone: a failed write puts them back
        List<ItemStack> held = dropContentsFirst ? takeContents(level, pos) : List.of();
        boolean set = level.setBlock(pos, state, QUIET_REMOVAL_FLAGS);
        if (!set || level.getBlockState(pos) != state) {
            putBack(level, pos, held);
            return PlaceResult.INVALID;
        }
        for (ItemStack stack : held) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
        }
        return PlaceResult.PLACED;
    }

    /** Runs the neighbour updates the quiet removals skipped, once for each position of a finished piece. */
    public void settle(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            BlockState now = level.getBlockState(pos);
            level.updateNeighborsAt(pos, now.getBlock());
            now.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        }
    }

    /** Takes every item out through the item-handler capability (vanilla chests, Create's vault and depot alike). */
    static List<ItemStack> takeContents(ServerLevel level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (items != null) {
            for (int slot = 0; slot < items.getSlots(); slot++) {
                for (ItemStack s = items.extractItem(slot, Integer.MAX_VALUE, false); !s.isEmpty();
                     s = items.extractItem(slot, Integer.MAX_VALUE, false)) {
                    out.add(s);
                }
            }
        } else if (level.getBlockEntity(pos) instanceof Container c) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack s = c.removeItemNoUpdate(slot);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Puts held items back into the block that stayed; what does not fit is dropped, never deleted. */
    static void putBack(ServerLevel level, BlockPos pos, List<ItemStack> held) {
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        for (ItemStack stack : held) {
            ItemStack rest = items == null ? stack : ItemHandlerHelper.insertItem(items, stack, false);
            if (!rest.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, rest);
            }
        }
    }
}
