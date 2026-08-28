package io.github.khayashi4337.micradrone.drone;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Purely decorative reskin swapped in over a giant-pumpkin fusion patch (see
 * LiveFarmBlockAccess#applyGiantPumpkinPatch/#attemptGiantPumpkinHarvest). Never placed by a player -
 * the mod places and clears it itself, so it isn't registered with a BlockItem or a recipe.
 *
 * <p>POSITION (0-8) marks where in the patch a given cell sits, in world orientation: 0 NW, 1 NE,
 * 2 SW, 3 SE corner; 4 W, 5 E, 6 N, 7 S edge; 8 center (see
 * GiantPatchDetector#worldOrientedPosition), so patches larger than 3x3 tile the same 9 positions
 * instead of needing one variant per possible patch size. Each position has its own top texture
 * (tools/pumpkin_pipeline.py derives all nine from one function) rather than one corner/edge texture
 * rotated by the blockstate, because the pumpkin's ribs have to run north-south across the whole
 * patch - a rotated edge tile would have them running east-west.
 */
public class GiantPumpkinBlock extends Block {
    public static final IntegerProperty POSITION = IntegerProperty.create("position", 0, 8);

    public GiantPumpkinBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POSITION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POSITION);
    }
}
