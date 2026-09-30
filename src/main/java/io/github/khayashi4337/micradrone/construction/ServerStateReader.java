package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.analyze.VoxelGridFiller;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.CellTrait;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The single place the adapter turns a world position into the core's WorldCell (01 section 8): the block with all
 * its states, the block-entity kind, and the traits the replacement rules look at. Shared by ServerWorldPort.read
 * and ServerSurveyor. Reads never generate chunks: an unloaded position is reported unloaded.
 */
public final class ServerStateReader {
    private ServerStateReader() {
    }

    public static WorldCell read(ServerLevel level, IntPos pos) {
        BlockPos bp = ServerWorldPort.toBlockPos(pos);
        if (!level.isLoaded(bp)) {
            return WorldCell.unloaded();
        }
        BlockState state = level.getBlockState(bp);
        BlockEntity entity = level.getBlockEntity(bp);
        ObservedBlock observed = new ObservedBlock(BlockStates.toSpec(state), entity != null,
                entity == null ? "" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString());
        return new WorldCell(true, observed, traitsOf(level, bp, state, entity));
    }

    /**
     * One tick's worth of a range read (F-21): the filler's next batch of cells, each read and classified (the
     * classifier - P6 - also decides what an unloaded cell means). True once the whole grid is filled.
     */
    public static boolean step(ServerLevel level, VoxelGridFiller filler, int maxReads,
                               ToIntFunction<WorldCell> classifier) {
        for (IntPos pos : filler.nextBatch(maxReads)) {
            filler.set(pos, (byte) classifier.applyAsInt(read(level, pos)));
        }
        return filler.done();
    }

    private static Set<CellTrait> traitsOf(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity) {
        Set<CellTrait> traits = EnumSet.noneOf(CellTrait.class);
        if (state.canBeReplaced()) {
            traits.add(CellTrait.REPLACEABLE);
        }
        if (!state.getFluidState().isEmpty()) {
            traits.add(CellTrait.FLUID);
        }
        if (state.is(BlockTags.LEAVES)) {
            traits.add(CellTrait.LEAVES);
        }
        if (state.is(BuildTags.TERRAFORMABLE)) {
            traits.add(CellTrait.TERRAFORMABLE);
        }
        if (state.getDestroySpeed(level, pos) < 0) {
            traits.add(CellTrait.UNBREAKABLE);
        }
        if (isEmptyContainer(level, pos, entity)) {
            traits.add(CellTrait.EMPTY_CONTAINER);
        }
        return traits;
    }

    /**
     * A holder whose stores are all empty: the item-handler capability (Create's vault and depot expose it), a
     * vanilla Container block entity, and - when the fluid capability is present - empty tanks too. Fluids and lit
     * fuel cannot be taken out, so the removal confirmation announces them first (Task 30).
     */
    private static boolean isEmptyContainer(ServerLevel level, BlockPos pos, BlockEntity entity) {
        boolean holder = false;
        boolean empty = true;
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (items != null) {
            holder = true;
            for (int slot = 0; slot < items.getSlots(); slot++) {
                if (!items.getStackInSlot(slot).isEmpty()) {
                    empty = false;
                    break;
                }
            }
        } else if (entity instanceof Container container) {
            holder = true;
            empty = container.isEmpty();
        }
        IFluidHandler fluids = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
        if (fluids != null) {
            holder = true;
            for (int tank = 0; tank < fluids.getTanks(); tank++) {
                if (!fluids.getFluidInTank(tank).isEmpty()) {
                    empty = false;
                }
            }
        }
        return holder && empty;
    }
}
