package io.github.khayashi4337.micradrone.drone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.khayashi4337.micradrone.MicraDrone;

/**
 * This mod's own pumpkin crop, replacing the vanilla PUMPKIN_STEM the drone used to plant. Vanilla's
 * stem never fruits on its own cell: StemBlock.randomTick (decompiled 1.21.1 source, lines 93-102)
 * picks a random horizontal neighbour and only pops the Pumpkin block there if that cell is air over
 * farmland/dirt - so in a fully planted plot the inner stems could never fruit at all, and border
 * stems fruited *outside* the plot. The original game (The Farmer Was Replaced) is tile = crop: the
 * pumpkin ripens on the cell it was planted on, which is what every other rule here (rot, giant
 * fusion, harvest) assumes. A plain CropBlock (AGE 0-7, same shape as wheat/carrot) gives exactly
 * that, and inherits noCollission from its properties so the drone can fly through it.
 *
 * <p>Rot: the original's "about 1 in 5 pumpkins dies when it grows up" - rolled once, at the
 * moment the crop reaches max age, whichever path got it there (vanilla random tick or the plot's
 * bonemeal-style growth boost, see LiveFarmBlockAccess#boostGrowth). Neither path can run again at
 * max age (isRandomlyTicking / isValidBonemealTarget are both false there), so the roll happens
 * exactly once per pumpkin.
 */
public class PumpkinCropBlock extends CropBlock {
    /**
     * "About 1 in 5 (~20%) pumpkins die right as they finish growing"
     * (thefarmerwasreplaced.wiki.gg/wiki/Pumpkins).
     */
    static final float ROT_CHANCE = 0.2f;

    public PumpkinCropBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected ItemLike getBaseSeedId() {
        return Items.PUMPKIN_SEEDS;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        super.randomTick(state, level, pos, random);
        maybeRot(level, pos, random);
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        super.performBonemeal(level, random, pos, state);
        maybeRot(level, pos, random);
    }

    /** Re-reads the cell: the growth step above may or may not have just reached max age. */
    private void maybeRot(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState now = level.getBlockState(pos);
        if (now.is(this) && isMaxAge(now) && random.nextFloat() < ROT_CHANCE) {
            level.setBlockAndUpdate(pos, MicraDrone.ROTTEN_PUMPKIN_BLOCK.get().defaultBlockState());
            PumpkinEffects.rot(level, pos);
        }
    }
}
