package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sight and sound for the pumpkin events the mod triggers itself. A block a player breaks by hand
 * gets vanilla's crumbs and break sound for free, but every other way a pumpkin appears or vanishes
 * here is the mod calling setBlock, which is silent - and a giant pumpkin that just winks out of
 * existence doesn't read as anything having happened at all. Server side only: particles go out
 * through ServerLevel#sendParticles and sounds through Level#playSound, both of which broadcast to
 * the clients nearby (and the mod's block edits only ever run on the server anyway).
 *
 * <p>Every size-scaled sound follows one rule, {@link PumpkinEffectTuning#pitchForSide}: the bigger
 * the pumpkin, the deeper the sound.
 */
final class PumpkinEffects {
    private static final int HARVEST_CRUMBS_PER_CELL = 12;
    private static final int HARVEST_SPARKLES_PER_CELL = 4;
    private static final int FUSION_SPARKLES_PER_CELL = 6;
    private static final int ROT_SMOKE_PUFFS = 8;
    /** Random spread of a cell's particles around its centre, in blocks. */
    private static final double CRUMB_SPREAD = 0.3;
    private static final double SPARKLE_SPREAD = 0.45;
    private static final double CRUMB_SPEED = 0.05;
    private static final double SPARKLE_SPEED = 0.0;
    private static final double SMOKE_SPEED = 0.01;
    private static final float FULL_VOLUME = 1.0f;
    private static final float ROT_VOLUME = 0.8f;
    private static final float ROT_PITCH = 0.6f;

    private PumpkinEffects() {
    }

    /**
     * A giant pumpkin cracking open (broken by hand): once, at the cell the player hit, before the
     * ripple starts. The pumpkin's own break sound (vanilla pumpkins are SoundType.WOOD), only
     * deeper for a bigger pumpkin - a pottery/glass shatter was tried first and read as far too
     * hard and brittle for something organic.
     */
    static void giantCrack(ServerLevel level, BlockPos at, int side) {
        level.playSound(null, at, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, FULL_VOLUME,
                PumpkinEffectTuning.pitchForSide(side));
    }

    /**
     * Clears a cell the way a player breaking it would look and sound (vanilla's crumbs + the
     * block's break sound via Level#destroyBlock), minus the drops. Used both for each cell of a
     * collapsing giant and for an ordinary harvest() - where before the crop just went silently.
     */
    static void breakCell(ServerLevel level, BlockPos cell) {
        level.destroyBlock(cell, false);
    }

    /**
     * A whole giant harvested by script: crumbs of its own texture and green sparkles over every
     * cell, and one level-up chime, deeper the bigger it was. Call before the cells are cleared -
     * the crumbs are read from what is standing there.
     */
    static void giantHarvest(ServerLevel level, List<BlockPos> cells, int side) {
        for (BlockPos cell : cells) {
            BlockState state = level.getBlockState(cell);
            spawnAtCell(level, new BlockParticleOption(ParticleTypes.BLOCK, state), cell, HARVEST_CRUMBS_PER_CELL,
                    CRUMB_SPREAD, CRUMB_SPEED);
            spawnAtCell(level, ParticleTypes.HAPPY_VILLAGER, cell, HARVEST_SPARKLES_PER_CELL, SPARKLE_SPREAD, SPARKLE_SPEED);
        }
        playAtCentre(level, cells, SoundEvents.PLAYER_LEVELUP, PumpkinEffectTuning.pitchForSide(side));
    }

    /** Ripe pumpkins just fused into a giant, or a giant just grew: sparkles over the square and a chime, deeper for bigger squares. */
    static void fusion(ServerLevel level, List<BlockPos> cells, int side) {
        for (BlockPos cell : cells) {
            spawnAtCell(level, ParticleTypes.HAPPY_VILLAGER, cell, FUSION_SPARKLES_PER_CELL, SPARKLE_SPREAD, SPARKLE_SPEED);
        }
        playAtCentre(level, cells, SoundEvents.AMETHYST_BLOCK_CHIME, PumpkinEffectTuning.pitchForSide(side));
    }

    /** A pumpkin that rotted as it ripened: a puff of smoke and a squelch, so it can be spotted from across the plot. */
    static void rot(ServerLevel level, BlockPos cell) {
        spawnAtCell(level, ParticleTypes.SMOKE, cell, ROT_SMOKE_PUFFS, CRUMB_SPREAD, SMOKE_SPEED);
        level.playSound(null, cell, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.BLOCKS, ROT_VOLUME, ROT_PITCH);
    }

    private static void spawnAtCell(ServerLevel level, ParticleOptions particle, BlockPos cell, int count,
            double spread, double speed) {
        level.sendParticles(particle, cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, count,
                spread, spread, spread, speed);
    }

    /** One sound from the middle of the cells' bounding box (a square has no centre cell when its side is even). */
    private static void playAtCentre(ServerLevel level, List<BlockPos> cells, SoundEvent sound, float pitch) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            minX = Math.min(minX, cell.getX());
            maxX = Math.max(maxX, cell.getX());
            minZ = Math.min(minZ, cell.getZ());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        double x = (minX + maxX + 1) / 2.0;
        double z = (minZ + maxZ + 1) / 2.0;
        double y = cells.get(0).getY() + 0.5;
        level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, FULL_VOLUME, pitch);
    }
}
