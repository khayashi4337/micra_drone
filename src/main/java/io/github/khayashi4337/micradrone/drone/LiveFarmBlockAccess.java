package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
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

    private BlockPos groundPos() {
        int[] offset = PlotGeometry.groundOffset(grid.dirX(), grid.dirZ(), grid.gridX(), grid.gridY());
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
     * Crops whose block-placement is a plain "set the block to its default (age 0) state" - this
     * covers wheat and carrot (a CropBlock placed directly) and, just as simply, pumpkin: vanilla
     * plants a PumpkinStemBlock (AGE 0-7, same shape as CropBlock) on the cell, which - once fully
     * grown via boostGrowth()'s generic BonemealableBlock handling below - pops an actual Pumpkin
     * block onto a free adjacent farmland/dirt cell on its own, using vanilla's own StemBlock logic
     * (verified in decompiled sources; not reimplemented here). Deliberately a method, not a static
     * field: eagerly initializing a Minecraft-typed static field (or even referencing certain
     * Minecraft interfaces via instanceof) can break this class's verification on the test sourceSet
     * - see PlotGeometry, which exists specifically to stay unaffected by that.
     */
    private static Block simpleCropBlockFor(String crop) {
        return switch (crop) {
            case "wheat" -> Blocks.WHEAT;
            case "carrot" -> Blocks.CARROTS;
            case "pumpkin" -> Blocks.PUMPKIN_STEM;
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
        if (block == Blocks.PUMPKIN) {
            return "pumpkin";
        }
        return "wheat"; // covers Blocks.WHEAT and, defensively, anything unexpected
    }

    @Override
    public boolean canHarvest() {
        BlockPos ground = groundPos();
        return FarmCellRules.canHarvest(readFacts(ground, cropPos()));
    }

    /**
     * A cell counts as harvestable once its CropBlock (wheat/carrot) reaches max age, or - for
     * pumpkin - once an actual Pumpkin block exists there at all (the fruit itself has no growth
     * stages; only the stem that spawned it did).
     */
    private static boolean isMatureCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return state.is(Blocks.PUMPKIN);
    }

    /**
     * Ages up every immature crop standing on actual farmland within the plot by one bonemeal-style
     * jump, via the generic {@link BonemealableBlock} interface both CropBlock (wheat/carrot) and
     * StemBlock (pumpkin) implement - so this needs no per-crop-type special casing, and for pumpkin
     * specifically, reaching max age through this same call is what makes vanilla's own StemBlock
     * logic pop an actual Pumpkin block onto a free neighboring cell (see simpleCropBlockFor's note).
     * Called periodically from {@link DroneControllerBlockEntity#serverTick} - only once a corner
     * marker has confirmed the plot (see {@code plotConfirmed} there) - to make the claimed area grow
     * faster than vanilla, independent of whether a script is currently running. The farmland check
     * keeps this strictly to cells the drone actually tilled, not just anything sitting inside the
     * plot's bounding square.
     */
    public void boostGrowth() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int[] offset : PlotGeometry.allGroundOffsets(grid.dirX(), grid.dirZ(), grid.worldSize())) {
            BlockPos ground = origin.offset(offset[0], 0, offset[1]);
            if (!level.getBlockState(ground).is(Blocks.FARMLAND)) {
                continue;
            }
            BlockPos above = ground.above();
            BlockState state = level.getBlockState(above);
            if (state.getBlock() instanceof BonemealableBlock bonemealable
                    && bonemealable.isValidBonemealTarget(level, above, state)) {
                bonemealable.performBonemeal(serverLevel, serverLevel.getRandom(), above, state);
            }
        }
    }
}
