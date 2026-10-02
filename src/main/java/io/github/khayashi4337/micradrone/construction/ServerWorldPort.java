package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.PlaceResult;
import io.github.khayashi4337.micradrone.construction.core.WorldCell;
import io.github.khayashi4337.micradrone.construction.core.WorldPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The core's WorldPort on a ServerLevel (F-1: the caller runs on the server's main thread). Reads go through
 * ServerStateReader; writes go through PlacementGuard so protection events see the owner. A placement carrying
 * block-entity config is refused: the allowed keys are S-5's decision (P10) and no P4 part uses them.
 */
public final class ServerWorldPort implements WorldPort {
    private final ServerLevel level;
    private final PlacementGuard guard;

    public ServerWorldPort(ServerLevel level, PlacementGuard guard) {
        this.level = level;
        this.guard = guard;
    }

    @Override
    public WorldCell read(IntPos pos) {
        return ServerStateReader.read(level, pos);
    }

    @Override
    public PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor) {
        if (!blockEntityConfig.isEmpty()) {
            return PlaceResult.INVALID;
        }
        BlockState state = toState(block);
        if (state == null) {
            return PlaceResult.INVALID;
        }
        BlockPos target = toBlockPos(pos);
        return clearForWrite(target, state) ? guard.place(level, target, state, actor) : PlaceResult.BLOCKED_BY_ENTITY;
    }

    @Override
    public PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst) {
        BlockState state = toState(block);
        if (state == null) {
            return PlaceResult.INVALID;
        }
        BlockPos target = toBlockPos(pos);
        return clearForWrite(target, state) ? guard.restore(level, target, state, actor, dropContentsFirst)
                : PlaceResult.BLOCKED_BY_ENTITY;
    }

    @Override
    public void settle(List<IntPos> positions) {
        List<BlockPos> targets = new ArrayList<>(positions.size());
        for (IntPos pos : positions) {
            targets.add(toBlockPos(pos));
        }
        guard.settle(level, targets);
    }

    /** The single IntPos to BlockPos conversion of this adapter (read shares it). */
    static BlockPos toBlockPos(IntPos pos) {
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    /**
     * Vanilla's placement occupancy check (the same one BlockItem.canPlace runs for a player): false while an entity
     * that blocks placement — a player, a mob, a boat — overlaps the state's collision shape at pos. Air and other
     * states without a collision shape are always clear, so a removal to air is never held up.
     */
    private boolean clearForWrite(BlockPos pos, BlockState state) {
        return level.isUnobstructed(state, pos, CollisionContext.empty());
    }

    /** Null when the spec cannot be a block state of this game: BlockStates.toState throws IllegalArgumentException. */
    private static BlockState toState(BlockSpec block) {
        try {
            return BlockStates.toState(block);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
