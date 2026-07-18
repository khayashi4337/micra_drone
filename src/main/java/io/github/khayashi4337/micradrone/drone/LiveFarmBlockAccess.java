package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Maps the drone's grid cell onto real world blocks, reusing vanilla farmland/wheat mechanics
 * (moisture, random-tick growth) instead of a bespoke crop simulation. The plot is a square area
 * starting one block diagonally from the controller, extending toward whichever diagonal quadrant
 * {@link DroneGridState#dirX()}/{@link DroneGridState#dirZ()} points at (see
 * DroneControllerBlockEntity#scanForCornerMarker).
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

    /**
     * Pure coordinate math (no Minecraft types), so it can be unit tested without a real world.
     * Returns {dx, dz}: the offset from the controller to grid cell (gx, gy).
     */
    static int[] groundOffset(int dirX, int dirZ, int gx, int gy) {
        return new int[]{dirX * (1 + gx), dirZ * (1 + gy)};
    }

    private BlockPos groundPos() {
        int[] offset = groundOffset(grid.dirX(), grid.dirZ(), grid.gridX(), grid.gridY());
        return origin.offset(offset[0], 0, offset[1]);
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
