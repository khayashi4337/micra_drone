package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.khayashi4337.micradrone.drone.FarmCellRules.CellFacts;

/**
 * Maps the drone's grid cell onto real world blocks, reusing vanilla farmland/crop mechanics
 * (moisture, random-tick growth) instead of a bespoke crop simulation. The plot is a square area
 * starting one block diagonally from the controller, extending toward whichever diagonal quadrant
 * {@link DroneGridState#dirX()}/{@link DroneGridState#dirZ()} points at (see
 * DroneControllerBlockEntity#scanForCornerMarker). Reads the real blocks into {@link CellFacts} and
 * leaves the actual till/plant/harvest decisions to {@link FarmCellRules}.
 */
public final class LiveFarmBlockAccess implements FarmBlockAccess {
    /** Flat rate for every crop for now; a per-crop table can replace this if crops need to differ. */
    private static final long POINTS_PER_HARVEST = 1;

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
        Block cropBlock = simpleCropBlockFor(crop);
        if (cropBlock == null || !FarmCellRules.canPlant(crop, grid.isUnlocked(crop), readFacts(ground, above))) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(above, cropBlock.defaultBlockState()));
    }

    /**
     * Crops whose block-placement is a plain "set the block to its default (age 0) state" - wheat and
     * carrot both work exactly like this. Pumpkin deliberately isn't here: vanilla pumpkins grow via
     * PumpkinStemBlock (a vine that pops out a separate Pumpkin block), a different mechanic entirely,
     * so plant("pumpkin") intentionally fails until that's wired up separately. Deliberately a method,
     * not a static field: a field initializer touching Minecraft classes would run at class-load time,
     * which breaks calling this class's Minecraft-free static methods (groundOffset/allGroundOffsets)
     * from the test sourceSet, which has no net.minecraft.* on its classpath.
     */
    private static Block simpleCropBlockFor(String crop) {
        return switch (crop) {
            case "wheat" -> Blocks.WHEAT;
            case "carrot" -> Blocks.CARROTS;
            default -> null;
        };
    }

    @Override
    public Attempt attemptHarvest() {
        BlockPos ground = groundPos();
        BlockPos above = cropPos();
        if (!FarmCellRules.canHarvest(readFacts(ground, above))) {
            return Attempt.failure();
        }
        String cropName = cropNameOf(level.getBlockState(above).getBlock());
        // Runs on the main thread (via the paced action queue), same as every other grid-state
        // mutation here - see DroneGridState's other writers for why that matters.
        return new Attempt(true, () -> {
            level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState());
            grid.addPoints(cropName, POINTS_PER_HARVEST);
        });
    }

    private static String cropNameOf(Block block) {
        if (block == Blocks.CARROTS) {
            return "carrot";
        }
        return "wheat"; // covers Blocks.WHEAT and, defensively, anything unexpected
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
     * Ages up every immature crop standing on actual farmland within the plot by one bonemeal-style
     * jump (the same {@link CropBlock#growCrops} vanilla bonemeal itself uses). Called periodically
     * from {@link DroneControllerBlockEntity#serverTick} - only once a corner marker has confirmed the
     * plot (see {@code plotConfirmed} there) - to make the claimed area grow faster than vanilla,
     * independent of whether a script is currently running. The farmland check keeps this strictly to
     * cells the drone actually tilled, not just anything sitting inside the plot's bounding square.
     */
    public void boostGrowth() {
        for (int[] offset : allGroundOffsets(grid.dirX(), grid.dirZ(), grid.worldSize())) {
            BlockPos ground = origin.offset(offset[0], 0, offset[1]);
            if (!level.getBlockState(ground).is(Blocks.FARMLAND)) {
                continue;
            }
            BlockPos above = ground.above();
            BlockState state = level.getBlockState(above);
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                crop.growCrops(level, above, state);
            }
        }
    }
}
