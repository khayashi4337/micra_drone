package io.github.khayashi4337.micradrone.client;

import javax.annotation.Nullable;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import io.github.khayashi4337.micradrone.drone.PlotColorRules;
import io.github.khayashi4337.micradrone.drone.PlotGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Samples the REAL plot around a controller for the IDE screen's live bird's-eye view - the
 * minimap-family approach (Xaero's/JourneyMap/vanilla maps): read the block states the server
 * already syncs to the client and turn each cell into a color, rather than rendering the world
 * from a second camera. Crop growth is read straight from the synced AGE properties, so what this
 * shows is by construction the actual state of the farm, refreshed every frame at negligible cost
 * (at most 9x9 cells x 2 block reads).
 *
 * <p>Plot geometry (size/direction) comes from re-running {@link CornerMarkerScan} against the
 * client-side level with the exact same parameters the server uses - blocks are synced, so it
 * resolves the same plot with no extra networking.
 */
final class LivePlotView {
    private final BlockPos controllerPos;
    private CornerMarkerScan.PlotBounds bounds = new CornerMarkerScan.PlotBounds(
            DroneControllerBlockEntity.DEFAULT_WORLD_SIZE, 1, 1, false);

    LivePlotView(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
    }

    CornerMarkerScan.PlotBounds bounds() {
        return bounds;
    }

    /** Re-resolves plot size/direction from the client-side blocks; call on open and periodically. */
    void rescan(Level level) {
        bounds = CornerMarkerScan.scan(
                (dx, dy, dz) -> level.getBlockState(controllerPos.offset(dx, dy, dz)).is(MicraDrone.CORNER_MARKER_BLOCK.get()),
                DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE,
                DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE,
                DroneControllerBlockEntity.DEFAULT_WORLD_SIZE);
    }

    /** One ARGB color per cell (row-major worldSize*worldSize), read from the real blocks right now. */
    int[] sampleColors(Level level) {
        int size = bounds.worldSize();
        int[] colors = new int[size * size];
        for (int gy = 0; gy < size; gy++) {
            for (int gx = 0; gx < size; gx++) {
                int[] offset = PlotGeometry.groundOffset(bounds.dirX(), bounds.dirZ(), gx, gy);
                BlockPos ground = controllerPos.offset(offset[0], 0, offset[1]);
                colors[gy * size + gx] = cellColor(level, ground);
            }
        }
        return colors;
    }

    private int cellColor(Level level, BlockPos ground) {
        BlockPos abovePos = ground.above();
        BlockState above = level.getBlockState(abovePos);

        if (above.getBlock() instanceof CropBlock crop) {
            float growth = crop.getAge(above) / (float) crop.getMaxAge();
            // Any other CropBlock (e.g. potatoes planted by hand) borrows the wheat gradient.
            return above.is(Blocks.CARROTS) ? PlotColorRules.carrot(growth) : PlotColorRules.wheat(growth);
        }
        if (above.getBlock() instanceof StemBlock) {
            return PlotColorRules.pumpkinStem(above.getValue(StemBlock.AGE) / (float) StemBlock.MAX_AGE);
        }
        if (above.is(Blocks.ATTACHED_PUMPKIN_STEM)) {
            return PlotColorRules.pumpkinStem(1.0f); // fruit already popped next to it
        }
        if (above.is(MicraDrone.GIANT_PUMPKIN_BLOCK.get())) {
            return PlotColorRules.GIANT_PUMPKIN;
        }
        if (above.is(MicraDrone.ROTTEN_PUMPKIN_BLOCK.get())) {
            return PlotColorRules.ROTTEN_PUMPKIN;
        }
        if (above.is(Blocks.PUMPKIN)) {
            return PlotColorRules.PUMPKIN_FRUIT;
        }
        if (!above.isAir()) {
            // Anything else sitting in the cell (water, tall grass, a chest...): vanilla's own
            // map color, so the view still reads sensibly outside the happy path.
            return 0xFF000000 | above.getMapColor(level, abovePos).col;
        }

        BlockState groundState = level.getBlockState(ground);
        if (groundState.getBlock() instanceof FarmBlock) {
            return PlotColorRules.farmland(groundState.getValue(FarmBlock.MOISTURE) >= FarmBlock.MAX_MOISTURE);
        }
        return 0xFF000000 | groundState.getMapColor(level, ground).col;
    }

    /**
     * The real drone entity inside the plot's airspace, if the client can see one - used to draw
     * the marker at its exact (smoothly interpolated) position rather than snapped to a cell.
     */
    @Nullable
    DroneEntity findDrone(Level level) {
        int size = bounds.worldSize();
        int[] o1 = PlotGeometry.groundOffset(bounds.dirX(), bounds.dirZ(), 0, 0);
        int[] o2 = PlotGeometry.groundOffset(bounds.dirX(), bounds.dirZ(), size - 1, size - 1);
        AABB area = new AABB(
                controllerPos.getX() + Math.min(o1[0], o2[0]), controllerPos.getY(), controllerPos.getZ() + Math.min(o1[1], o2[1]),
                controllerPos.getX() + Math.max(o1[0], o2[0]) + 1, controllerPos.getY() + 3, controllerPos.getZ() + Math.max(o1[1], o2[1]) + 1);
        return level.getEntitiesOfClass(DroneEntity.class, area).stream().findFirst().orElse(null);
    }

    /** World x/z -> fractional grid coordinate (0.0 = center of cell 0); inverse of {@link PlotGeometry#groundOffset}. */
    double gridXOf(double worldX) {
        return (worldX - (controllerPos.getX() + 0.5)) * bounds.dirX() - 1;
    }

    double gridYOf(double worldZ) {
        return (worldZ - (controllerPos.getZ() + 0.5)) * bounds.dirZ() - 1;
    }
}
