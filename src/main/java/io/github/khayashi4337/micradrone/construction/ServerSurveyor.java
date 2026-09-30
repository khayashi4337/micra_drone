package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.build.compile.SiteSurveyBuilder;
import io.github.khayashi4337.micradrone.build.model.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads the site's surface column by column into a SiteSurveyBuilder, spread over ticks (01 sections 7 and 11).
 * It only ever looks at loaded chunks: reading an unloaded one would make the server generate terrain.
 */
public final class ServerSurveyor {
    /** The block id a column with no ground inside the box reports; SiteSurvey.hasGround stays false for it. */
    private static final String NO_GROUND = "minecraft:air";

    private ServerSurveyor() {
    }

    /**
     * True when every chunk the box touches is loaded. Asked before a survey starts, so the answer can tell the
     * player to come closer instead of generating chunks.
     */
    public static boolean allLoaded(ServerLevel level, Box box) {
        for (int cx = box.minA() >> 4; cx <= box.maxA() >> 4; cx++) {
            for (int cz = box.minC() >> 4; cz <= box.maxC() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Surveys at most maxColumns more columns. False when a column's chunk has gone unloadable mid-survey; the
     * submission then ends with E-SITE-BLOCKED#unloaded instead of waiting for terrain.
     */
    public static boolean step(ServerLevel level, SiteSurveyBuilder builder, int maxColumns) {
        for (int[] column : builder.nextColumns(maxColumns)) {
            int x = column[0];
            int z = column[1];
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            surveyColumn(level, builder, x, z);
        }
        return true;
    }

    /**
     * Scans one column downward from the surface heightmap, capped at the box top. Fluids flag {@code water}, leaves
     * and logs flag {@code tree}; air and replaceable blocks (plants, snow layers) pass silently. The fluid check
     * comes before canBeReplaced because liquid blocks are replaceable too. A column with no ground inside the box
     * records {@link #NO_GROUND} one below the box bottom.
     */
    private static void surveyColumn(ServerLevel level, SiteSurveyBuilder builder, int x, int z) {
        Box box = builder.worldBounds();
        boolean water = false;
        boolean tree = false;
        for (int y = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, box.maxB());
             y >= box.minB(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                water = true;
            } else if (state.isAir() || state.canBeReplaced()) {
                // plants, snow layers: not ground, not water, not a tree
            } else if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                tree = true;
            } else {
                builder.column(x, z, y, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), water, tree);
                return;
            }
        }
        builder.column(x, z, box.minB() - 1, NO_GROUND, water, tree);
    }
}
