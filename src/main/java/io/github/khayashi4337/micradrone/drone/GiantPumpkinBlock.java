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
 * <p>POSITION (0-8) marks where in the patch a given cell sits: 4 corners, 4 edges, 1 center (see
 * GiantPatchDetector#classifyPosition), so patches larger than 3x3 tile the same 9 positions instead
 * of needing one variant per possible patch size. All 9 currently render identically (reusing
 * vanilla's own pumpkin texture - see the block model - since this mod has no custom art assets); the
 * property exists so distinct per-position textures can be dropped in later with no Java/logic changes.
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
