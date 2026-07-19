package io.github.khayashi4337.micradrone.drone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.FarmCellRules.CellFacts;
import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Patch;

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
    /**
     * Matches the original game: "about 1 in 5 (~20%) pumpkins die right as they finish growing"
     * (thefarmerwasreplaced.wiki.gg/wiki/Pumpkins, confirmed via live web research). See
     * {@link #maybeRotAFreshPumpkin}.
     */
    private static final float PUMPKIN_ROT_CHANCE = 0.2f;

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
        // A rotten pumpkin counts as "empty" for planting purposes: per the original game, "planting
        // a new plant in its place automatically removes the dead pumpkin, so there is no need to
        // harvest it" - no separate clear step, plant() just overwrites it.
        boolean clearable = aboveState.isAir() || aboveState.is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get());
        return new CellFacts(
                groundState.is(BlockTags.DIRT),
                groundState.is(Blocks.FARMLAND),
                clearable,
                isMatureCrop(aboveState));
    }

    @Override
    public boolean isRotten() {
        return level.getBlockState(cropPos()).is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get());
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
        BlockState aboveState = level.getBlockState(above);
        if (aboveState.is(MicraDrone.GIANT_PUMPKIN_BLOCK.get())) {
            return attemptGiantPumpkinHarvest(above);
        }
        if (!FarmCellRules.canHarvest(readFacts(ground, above))) {
            return Attempt.failure();
        }
        // Matches the original game: a dead pumpkin can be harvested (the attempt succeeds) but
        // "won't drop anything when harvested" - no points, just clear the cell.
        if (aboveState.is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get())) {
            return new Attempt(true, () -> level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState()));
        }
        String cropName = cropNameOf(aboveState.getBlock());
        // Runs on the main thread (via the paced action queue), same as every other grid-state
        // mutation here - see DroneGridState's other writers for why that matters.
        return new Attempt(true, () -> {
            level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState());
            grid.addPoints(cropName, POINTS_PER_HARVEST);
        });
    }

    /**
     * A giant-pumpkin patch is harvested as a whole: flood-fill every connected giant_pumpkin cell
     * from wherever the drone called harvest() (the patch is always an axis-aligned square by
     * construction, but flood-fill needs no assumption about that), clear it all to air, and award
     * one lump-sum bonus (see GiantPatchDetector#bonusPoints) instead of the normal per-cell rate.
     */
    private Attempt attemptGiantPumpkinHarvest(BlockPos anyCellInPatch) {
        List<BlockPos> patchCells = floodFillGiantPumpkin(anyCellInPatch);
        int side = (int) Math.round(Math.sqrt(patchCells.size()));
        long bonus = GiantPatchDetector.bonusPoints(side);
        return new Attempt(true, () -> {
            for (BlockPos cell : patchCells) {
                level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
            }
            grid.addPoints("pumpkin", bonus);
        });
    }

    private List<BlockPos> floodFillGiantPumpkin(BlockPos start) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        Block giantPumpkin = MicraDrone.GIANT_PUMPKIN_BLOCK.get();
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (seen.add(next) && level.getBlockState(next).is(giantPumpkin)) {
                    queue.add(next);
                }
            }
        }
        return found;
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
     * stages; only the stem that spawned it did), or once it's part of a giant-pumpkin patch, or -
     * matching the original game - once it's a rotten pumpkin (harvestable, just yields nothing; see
     * attemptHarvest).
     */
    private static boolean isMatureCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return state.is(Blocks.PUMPKIN) || state.is(MicraDrone.GIANT_PUMPKIN_BLOCK.get())
                || state.is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get());
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
        int worldSize = grid.worldSize();
        boolean[][] maturePumpkin = new boolean[worldSize][worldSize];
        for (int gx = 0; gx < worldSize; gx++) {
            for (int gy = 0; gy < worldSize; gy++) {
                int[] offset = PlotGeometry.groundOffset(grid.dirX(), grid.dirZ(), gx, gy);
                BlockPos ground = origin.offset(offset[0], 0, offset[1]);
                if (!level.getBlockState(ground).is(Blocks.FARMLAND)) {
                    continue;
                }
                BlockPos above = ground.above();
                BlockState state = level.getBlockState(above);
                if (state.getBlock() instanceof BonemealableBlock bonemealable
                        && bonemealable.isValidBonemealTarget(level, above, state)) {
                    bonemealable.performBonemeal(serverLevel, serverLevel.getRandom(), above, state);
                    maybeRotAFreshPumpkin(above, serverLevel.getRandom());
                    state = level.getBlockState(above); // may have just matured into a fruit (or rotted)
                }
                maturePumpkin[gx][gy] = state.is(Blocks.PUMPKIN);
            }
        }
        applyGiantPumpkinPatch(maturePumpkin);
    }

    /**
     * If {@code stemPos} just turned into an ATTACHED_PUMPKIN_STEM (vanilla's own sign that its
     * StemBlock just popped a fresh Pumpkin onto the neighboring cell its FACING points at - see
     * StemBlock.randomTick, not reimplemented here), rolls the original game's ~20% "grew defective"
     * chance and, on a hit, swaps that fresh Pumpkin for {@link MicraDrone#ROTTEN_PUMPKIN_BLOCK}
     * instead. Matches the original: dying only ever happens right as a pumpkin finishes growing,
     * never before.
     */
    private void maybeRotAFreshPumpkin(BlockPos stemPos, RandomSource random) {
        BlockState stemNowState = level.getBlockState(stemPos);
        if (!stemNowState.is(Blocks.ATTACHED_PUMPKIN_STEM)) {
            return;
        }
        Direction facing = stemNowState.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos fruitPos = stemPos.relative(facing);
        if (level.getBlockState(fruitPos).is(Blocks.PUMPKIN) && random.nextFloat() < PUMPKIN_ROT_CHANCE) {
            level.setBlockAndUpdate(fruitPos, MicraDrone.ROTTEN_PUMPKIN_BLOCK.get().defaultBlockState());
        }
    }

    /**
     * Reskins the largest square of simultaneously-mature pumpkins (if any, see GiantPatchDetector)
     * with {@link MicraDrone#GIANT_PUMPKIN_BLOCK} so it reads as one fused patch. Deliberately a
     * simplification of the original game's "grew together with zero deaths" rule: this only checks
     * which cells are mature right now, not growth history (see LiveFarmBlockAccess's Phase 3 commit
     * for why). Only ever called from boostGrowth(), which is itself only active once a corner marker
     * has confirmed the plot - so this can't affect anything outside the claimed farming area.
     */
    private void applyGiantPumpkinPatch(boolean[][] maturePumpkin) {
        Optional<Patch> found = GiantPatchDetector.findLargestSquare(maturePumpkin);
        if (found.isEmpty()) {
            return;
        }
        Patch patch = found.get();
        BlockState giantPumpkin = MicraDrone.GIANT_PUMPKIN_BLOCK.get().defaultBlockState();
        for (int lx = 0; lx < patch.side(); lx++) {
            for (int ly = 0; ly < patch.side(); ly++) {
                int gx = patch.originGx() + lx;
                int gy = patch.originGy() + ly;
                int[] offset = PlotGeometry.groundOffset(grid.dirX(), grid.dirZ(), gx, gy);
                BlockPos above = origin.offset(offset[0], 1, offset[1]);
                int position = GiantPatchDetector.classifyPosition(lx, ly, patch.side());
                level.setBlockAndUpdate(above, giantPumpkin.setValue(GiantPumpkinBlock.POSITION, position));
            }
        }
    }
}
