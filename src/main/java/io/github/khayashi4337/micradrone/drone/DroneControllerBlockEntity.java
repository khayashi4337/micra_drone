package io.github.khayashi4337.micradrone.drone;

import io.github.khayashi4337.micradrone.MicraDrone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds the drone's grid position for the farm plot claimed by this controller. */
public class DroneControllerBlockEntity extends BlockEntity {
    private int gridX = 0;
    private int gridY = 0;

    public DroneControllerBlockEntity(BlockPos pos, BlockState state) {
        super(MicraDrone.DRONE_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
    }

    public int getGridX() {
        return gridX;
    }

    public int getGridY() {
        return gridY;
    }

    public void setGridPos(int x, int y) {
        this.gridX = x;
        this.gridY = y;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        gridX = tag.getInt("GridX");
        gridY = tag.getInt("GridY");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("GridX", gridX);
        tag.putInt("GridY", gridY);
    }
}
