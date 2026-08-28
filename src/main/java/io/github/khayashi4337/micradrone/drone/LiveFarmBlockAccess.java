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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.FarmCellRules.CellFacts;
import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Patch;

/**
 * Maps the drone's grid cell onto real world blocks, reusing vanilla farmland/crop mechanics
 * (random-tick growth via bonemeal) instead of a bespoke crop simulation. Moisture is the one
 * exception - see boostGrowth() - since requiring a real water source next to every plot (vanilla's
 * normal way of keeping farmland hydrated) was pure friction with no gameplay upside for a mod
 * that's already boosting growth speed unconditionally. The plot is a square area starting one block
 * diagonally from the controller, extending toward whichever diagonal quadrant
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
        return origin.offset(offset[0], grid.groundYOffset(), offset[1]);
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

    // ---- perception (GitHub issue #10) ----

    @Override
    public String groundBlockName() {
        return blockName(groundPos());
    }

    @Override
    public String blockAboveName() {
        return blockName(cropPos());
    }

    private String blockName(BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return SenseNames.simplify(id.getNamespace(), id.getPath());
    }

    @Override
    public long dayTime() {
        return SenseNames.timeOfDay(level.getDayTime());
    }

    @Override
    public String weather() {
        return SenseNames.weather(level.isRaining(), level.isThundering());
    }

    /**
     * Biomes can be defined inline (unregistered) by a datapack, in which case there is no key to
     * report - hand back "unknown" rather than throwing, so a perception script can never crash a
     * run just by standing somewhere unusual.
     */
    @Override
    public String biomeName() {
        return level.getBiome(cropPos()).unwrapKey()
                .map(key -> SenseNames.simplify(key.location().getNamespace(), key.location().getPath()))
                .orElse("unknown");
    }

    /**
     * The same "how bright is it here" number vanilla uses for mob spawning: block light and sky
     * light combined, already dimmed for night and for weather - so a script watching this sees the
     * day/night cycle and a passing storm the way a player standing there would.
     */
    @Override
    public int lightLevel() {
        return level.getMaxLocalRawBrightness(cropPos());
    }

    /** See {@link CornerMarkerBlockEntity#findDisplayId} - shared with the Shop screen so both agree on the same id. */
    @Override
    public String plotId() {
        return level instanceof ServerLevel serverLevel ? CornerMarkerBlockEntity.findDisplayId(serverLevel, origin) : "";
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
        // Cell rules first (no side effects), then the permission: unlocked in the shop, or - the
        // fallback - one real seed item taken from the owner's inventory right now. Taking it at
        // decision time rather than in apply() keeps "a seed was spent" and "planting succeeded"
        // the same event as far as the script's return value is concerned.
        if (cropBlock == null || !FarmCellRules.canPlant(crop, true, readFacts(ground, above))) {
            return Attempt.failure();
        }
        if (!grid.isUnlocked(crop) && !grid.takeSeedFromOwner(crop)) {
            return Attempt.failure();
        }
        return new Attempt(true, () -> level.setBlockAndUpdate(above, cropBlock.defaultBlockState()));
    }

    /**
     * Every crop is a CropBlock placed directly at its default (age 0) state: vanilla's wheat and
     * carrot, and this mod's own {@link PumpkinCropBlock} (see its javadoc for why vanilla's
     * pumpkin stem - which fruits onto a *neighbouring* cell - could not stand in for the original
     * game's ripen-in-place pumpkin). Deliberately a method, not a static field: eagerly
     * initializing a Minecraft-typed static field (or even referencing certain Minecraft interfaces
     * via instanceof) can break this class's verification on the test sourceSet - see PlotGeometry,
     * which exists specifically to stay unaffected by that.
     */
    private static Block simpleCropBlockFor(String crop) {
        return switch (crop) {
            case "wheat" -> Blocks.WHEAT;
            case "carrot" -> Blocks.CARROTS;
            case "pumpkin" -> MicraDrone.PUMPKIN_CROP_BLOCK.get();
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

    /**
     * Blocks.PUMPKIN is kept alongside the mod's own crop only for worlds saved before the crop
     * existed: a vanilla pumpkin a stem had already dropped inside a plot stays harvestable (1 pt).
     */
    private static String cropNameOf(Block block) {
        if (block == Blocks.CARROTS) {
            return "carrot";
        }
        if (block instanceof PumpkinCropBlock || block == Blocks.PUMPKIN) {
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
     * A cell counts as harvestable once its CropBlock (wheat/carrot/pumpkin) reaches max age, or
     * once it's part of a giant-pumpkin patch, or - matching the original game - once it's a rotten
     * pumpkin (harvestable, just yields nothing; see attemptHarvest). Blocks.PUMPKIN: see cropNameOf.
     */
    private static boolean isMatureCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return state.is(Blocks.PUMPKIN) || state.is(MicraDrone.GIANT_PUMPKIN_BLOCK.get())
                || state.is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get());
    }

    /** A ripe, non-rotten pumpkin - the only thing a giant patch is built from. Blocks.PUMPKIN: see cropNameOf. */
    private static boolean isRipePumpkin(BlockState state) {
        return (state.getBlock() instanceof PumpkinCropBlock crop && crop.isMaxAge(state)) || state.is(Blocks.PUMPKIN);
    }

    /**
     * Ages up every immature crop standing on actual farmland within the plot by one bonemeal-style
     * jump, via the generic {@link BonemealableBlock} interface every CropBlock implements - so this
     * needs no per-crop-type special casing (pumpkin's 20% rot-on-ripening lives inside
     * {@link PumpkinCropBlock#performBonemeal} itself, so it fires through this path too).
     * Called periodically from {@link DroneControllerBlockEntity#serverTick} - only once a corner
     * marker has confirmed the plot (see {@code plotConfirmed} there) - to make the claimed area grow
     * faster than vanilla, independent of whether a script is currently running. The farmland check
     * keeps this strictly to cells the drone actually tilled, not just anything sitting inside the
     * plot's bounding square. Also keeps that same farmland at maximum moisture every pass (see
     * waterPlot) - a real vanilla plot needs a water source within 4 blocks to ever get there, and
     * without one, farmland just as easily dries out and reverts to dirt (FarmBlock#randomTick) as it
     * grows crops faster. Forcing moisture sidesteps that trap entirely, plot-wide, with no water
     * block required.
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
                BlockPos ground = origin.offset(offset[0], grid.groundYOffset(), offset[1]);
                BlockState groundState = level.getBlockState(ground);
                if (!groundState.is(Blocks.FARMLAND)) {
                    continue;
                }
                waterPlot(ground, groundState);
                BlockPos above = ground.above();
                BlockState state = level.getBlockState(above);
                if (state.getBlock() instanceof BonemealableBlock bonemealable
                        && bonemealable.isValidBonemealTarget(level, above, state)) {
                    bonemealable.performBonemeal(serverLevel, serverLevel.getRandom(), above, state);
                    state = level.getBlockState(above); // may have just ripened (or rotted)
                }
                maturePumpkin[gx][gy] = isRipePumpkin(state);
            }
        }
        applyGiantPumpkinPatch(maturePumpkin);
    }

    /**
     * Forces a plot cell's farmland straight to {@link FarmBlock#MAX_MOISTURE}, standing in for the
     * "water source within 4 blocks" vanilla normally requires (see FarmBlock#isNearWater) - the
     * player building a whole irrigation network around a small drone plot would be pure friction with
     * no real payoff. No-op once already at max, so this doesn't spam block updates every pass.
     */
    private void waterPlot(BlockPos ground, BlockState groundState) {
        if (groundState.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE) {
            level.setBlock(ground, groundState.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 2);
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
                BlockPos above = origin.offset(offset[0], grid.groundYOffset() + 1, offset[1]);
                int position = GiantPatchDetector.worldOrientedPosition(lx, ly, patch.side(), grid.dirX(), grid.dirZ());
                level.setBlockAndUpdate(above, giantPumpkin.setValue(GiantPumpkinBlock.POSITION, position));
            }
        }
    }
}
