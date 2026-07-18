package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps the drone's grid cell onto real world blocks, reusing vanilla farmland/wheat mechanics
 * (moisture, random-tick growth) instead of a bespoke crop simulation. The plot is a fixed 5x5
 * area starting one block diagonally from the controller (MVP: no facing-awareness yet).
 */
public final class LiveFarmBlockAccess implements FarmBlockAccess {
    private final Level level;
    private final BlockPos origin;
    private final DroneGridState grid;

    public LiveFarmBlockAccess(Level level, BlockPos origin, DroneGridState grid) {
        this.level = level;
        this.origin = origin;
        this.grid = grid;
    }

    private BlockPos groundPos() {
        return origin.offset(1 + grid.gridX(), 0, 1 + grid.gridY());
    }

    private BlockPos cropPos() {
        return groundPos().above();
    }

    @Override
    public Attempt attemptTill() {
        BlockPos ground = groundPos();
        BlockState groundState = level.getBlockState(ground);
        boolean tillable = groundState.is(BlockTags.DIRT) && level.getBlockState(ground.above()).isAir();
        if (!tillable) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState()));
    }

    @Override
    public Attempt attemptPlant(String crop) {
        if (!"wheat".equals(crop)) {
            return Attempt.failure();
        }
        BlockPos ground = groundPos();
        BlockPos above = cropPos();
        boolean plantable = level.getBlockState(ground).is(Blocks.FARMLAND) && level.getBlockState(above).isAir();
        if (!plantable) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(above, Blocks.WHEAT.defaultBlockState()));
    }

    @Override
    public Attempt attemptHarvest() {
        BlockPos above = cropPos();
        if (!isMatureCrop(level.getBlockState(above))) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState()));
    }

    @Override
    public boolean canHarvest() {
        return isMatureCrop(level.getBlockState(cropPos()));
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }
}
