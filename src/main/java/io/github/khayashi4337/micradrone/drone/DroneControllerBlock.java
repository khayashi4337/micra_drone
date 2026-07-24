package io.github.khayashi4337.micradrone.drone;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Placed to claim a farm plot; holds the drone's script and grid state via {@link DroneControllerBlockEntity}. */
public class DroneControllerBlock extends BaseEntityBlock {
    public static final MapCodec<DroneControllerBlock> CODEC = simpleCodec(DroneControllerBlock::new);
    /**
     * Docked (idle) vs active (a script is currently RUNNING) texture - see
     * {@link DroneControllerBlockEntity#serverTick}, which keeps this in sync with
     * {@code DroneScriptRunner.State.RUNNING} every tick (same pattern as a furnace's LIT property;
     * no vanilla BlockStateProperties constant fits, so this mirrors how ChiseledBookShelfBlock
     * defines its own slot-occupied properties directly rather than reusing a shared one).
     */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public DroneControllerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    public MapCodec<DroneControllerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DroneControllerBlockEntity(pos, state);
    }

    // BaseEntityBlock defaults to RenderShape.INVISIBLE, assuming a BlockEntityRenderer will draw the
    // block instead. We don't have one (yet) - render the normal baked model from our blockstate/model json.
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, MicraDrone.DRONE_CONTROLLER_BLOCK_ENTITY.get(), DroneControllerBlockEntity::serverTick);
    }

    // Client-only: open the IDE straight away, editing whichever script is currently selected
    // (GUI reduction: the jukebox-style item slot is gone - the IDE's own list picks what's
    // selected). The screen itself sends network payloads for everything that touches server state.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openIdeScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // Redstone Run/Stop (issue #7): lever ON runs the currently selected script, OFF stops it -
    // the GUI-free control path. Edge detection lives in the BlockEntity (RedstoneEdge).
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
            be.onNeighborSignalChange(level.hasNeighborSignal(pos));
        }
    }

    // Clean up the visible DroneEntity so it doesn't linger after the controller that owns it is gone.
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
            be.discardDroneEntity();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
