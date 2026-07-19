package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.khayashi4337.micradrone.drone.FarmCellRules.CellFacts;

/**
 * Maps the drone's grid cell onto real world blocks, reusing vanilla farmland/wheat mechanics
 * (moisture, random-tick growth) instead of a bespoke crop simulation. The plot is a square area
 * starting one block diagonally from the controller, extending toward whichever diagonal quadrant
 * {@link DroneGridState#dirX()}/{@link DroneGridState#dirZ()} points at (see
 * DroneControllerBlockEntity#scanForCornerMarker). Reads the real blocks into {@link CellFacts} and
 * leaves the actual till/plant/harvest decisions to {@link FarmCellRules}.
 */
public final class LiveFarmBlockAccess implements FarmBlockAccess {
    // Only "wheat" exists right now, so a flat rate covers it; a per-crop table can replace this
    // once a second crop is added.
    private static final long POINTS_PER_WHEAT_HARVEST = 1;

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

    /**
     * Pure coordinate math: every {dx, dz} ground offset in a worldSize x worldSize plot, in no
     * particular order. Used to sweep the whole plot at once (see {@link #boostGrowth()}) rather
     * than just the drone's current cell.
     */
    static List<int[]> allGroundOffsets(int dirX, int dirZ, int worldSize) {
        List<int[]> offsets = new ArrayList<>(worldSize * worldSize);
        for (int gx = 0; gx < worldSize; gx++) {
            for (int gy = 0; gy < worldSize; gy++) {
                offsets.add(groundOffset(dirX, dirZ, gx, gy));
            }
        }
        return offsets;
    }

    private BlockPos groundPos() {
        int[] offset = groundOffset(grid.dirX(), grid.dirZ(), grid.gridX(), grid.gridY());
        return origin.offset(offset[0], 0, offset[1]);
    }

    private BlockPos cropPos() {
        return groundPos().above();
    }

    private CellFacts readFacts(BlockPos ground, BlockPos above) {
        BlockState groundState = level.getBlockState(ground);
        BlockState aboveState = level.getBlockState(above);
        return new CellFacts(
                groundState.is(BlockTags.DIRT),
                groundState.is(Blocks.FARMLAND),
                aboveState.isAir(),
                isMatureCrop(aboveState));
    }

    @Override
    public Attempt attemptTill() {
        BlockPos ground = groundPos();
        if (!FarmCellRules.canTill(readFacts(ground, ground.above()))) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState()));
    }

    @Override
    public Attempt attemptPlant(String crop) {
        BlockPos ground = groundPos();
        BlockPos above = cropPos();
        if (!FarmCellRules.canPlant(crop, readFacts(ground, above))) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(above, Blocks.WHEAT.defaultBlockState()));
    }

    @Override
    public Attempt attemptHarvest() {
        BlockPos ground = groundPos();
        BlockPos above = cropPos();
        if (!FarmCellRules.canHarvest(readFacts(ground, above))) {
            return Attempt.failure();
        }
        // Runs on the main thread (via the paced action queue), same as every other grid-state
        // mutation here - see DroneGridState's other writers for why that matters.
        return new Attempt(true, () -> {
            level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState());
            grid.addPoints(POINTS_PER_WHEAT_HARVEST);
        });
    }

    @Override
    public boolean canHarvest() {
        BlockPos ground = groundPos();
        return FarmCellRules.canHarvest(readFacts(ground, cropPos()));
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /**
     * Ages up every immature crop in the plot by one bonemeal-style jump (the same
     * {@link CropBlock#growCrops} vanilla bonemeal itself uses). Called periodically from
     * {@link DroneControllerBlockEntity#serverTick} to make the claimed plot grow faster than
     * vanilla, independent of whether a script is currently running.
     */
    public void boostGrowth() {
        for (int[] offset : allGroundOffsets(grid.dirX(), grid.dirZ(), grid.worldSize())) {
            BlockPos above = origin.offset(offset[0], 1, offset[1]);
            BlockState state = level.getBlockState(above);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                crop.growCrops(level, above, state);
            }
        }
    }
}
