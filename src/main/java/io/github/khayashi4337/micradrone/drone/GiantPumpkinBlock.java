package io.github.khayashi4337.micradrone.drone;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.GiantPatchDetector.Square;

/**
 * One cell of a giant-pumpkin fusion patch (see LiveFarmBlockAccess#applyGiantPumpkinPatches /
 * #attemptGiantPumpkinHarvest). Never placed by a player - the mod places and clears it itself, so
 * it isn't registered with a BlockItem or a recipe, and it drops nothing when broken.
 *
 * <p>POSITION (0-8) marks where in the patch a given cell sits, in world orientation: 0 NW, 1 NE,
 * 2 SW, 3 SE corner; 4 W, 5 E, 6 N, 7 S edge; 8 center (see
 * GiantPatchDetector#worldOrientedPosition), so patches larger than 3x3 tile the same 9 positions
 * instead of needing one variant per possible patch size. Each position has its own top texture
 * (tools/pumpkin_pipeline.py derives all nine from one function) rather than one corner/edge texture
 * rotated by the blockstate, because the pumpkin's ribs have to run north-south across the whole
 * patch - a rotated edge tile would have them running east-west. The markers double as the record
 * of the patch's geometry: GiantPatchDetector#resolveSquare reads a whole square back from them,
 * which is how harvest() knows the side length without a BlockEntity.
 *
 * <p>A fused patch is one pumpkin, not a stack of blocks: breaking any cell by hand (or anything
 * else outside the mod's own harvest/fusion passes) destroys the whole patch - every cell goes,
 * no points, no drops. The original game has no such thing as breaking a giant pumpkin from
 * outside a script, so this is the mod's own rule, chosen so the giant reads as a single object
 * the way it looks.
 */
public class GiantPumpkinBlock extends Block {
    public static final IntegerProperty POSITION = IntegerProperty.create("position", 0, 8);
    /** Upper bound on a patch side when walking POSITION markers - plots are far smaller than this. */
    private static final int MAX_PATCH_SIDE = 64;

    /**
     * Set while the mod itself is repainting or harvesting a patch (server main thread only, the
     * only place blocks change), so those bulk edits don't look like a player breaking cells and
     * trigger the whole-patch destruction in {@link #onRemove}.
     */
    private static boolean patchMutationInProgress;

    public GiantPumpkinBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POSITION, GiantPatchDetector.POS_NW));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POSITION);
    }

    /** Runs {@code edit} with the unfuse-on-remove reaction switched off - the mod's own patch edits. */
    static void withPatchMutation(Runnable edit) {
        boolean previous = patchMutationInProgress;
        patchMutationInProgress = true;
        try {
            edit.run();
        } finally {
            patchMutationInProgress = previous;
        }
    }

    /** The ripe (max-age) state of the mod's pumpkin crop - what a fused cell turns back into. */
    static BlockState ripePumpkinState() {
        PumpkinCropBlock crop = MicraDrone.PUMPKIN_CROP_BLOCK.get();
        return crop.getStateForAge(crop.getMaxAge());
    }

    /** POSITION of the giant-pumpkin block at (x, y, z), or {@link GiantPatchDetector#NOT_GIANT}. */
    static int positionAt(Level level, int x, int y, int z) {
        BlockState state = level.getBlockState(new BlockPos(x, y, z));
        return state.getBlock() instanceof GiantPumpkinBlock ? state.getValue(POSITION) : GiantPatchDetector.NOT_GIANT;
    }

    /** The square the giant-pumpkin block at {@code pos} belongs to, read from the POSITION markers around it. */
    static Optional<Square> squareAt(Level level, BlockPos pos) {
        return GiantPatchDetector.resolveSquare(
                (x, z) -> positionAt(level, x, pos.getY(), z), pos.getX(), pos.getZ(), MAX_PATCH_SIDE);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level.isClientSide() || newState.is(this) || patchMutationInProgress) {
            return; // a POSITION repaint, or one of the mod's own bulk edits - not a player
        }
        // This cell is already gone from the level, so answer its old POSITION for it ourselves.
        int removedPosition = state.getValue(POSITION);
        Optional<Square> square = GiantPatchDetector.resolveSquare(
                (x, z) -> x == pos.getX() && z == pos.getZ() ? removedPosition : positionAt(level, x, pos.getY(), z),
                pos.getX(), pos.getZ(), MAX_PATCH_SIDE);
        if (square.isEmpty()) {
            return;
        }
        Square s = square.get();
        withPatchMutation(() -> {
            for (int lx = 0; lx < s.side(); lx++) {
                for (int lz = 0; lz < s.side(); lz++) {
                    BlockPos cell = new BlockPos(s.originX() + lx, pos.getY(), s.originZ() + lz);
                    if (!cell.equals(pos) && level.getBlockState(cell).getBlock() instanceof GiantPumpkinBlock) {
                        level.setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        });
    }
}
