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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Placed to claim a farm plot; holds the drone's script and grid state via {@link DroneControllerBlockEntity}. */
public class DroneControllerBlock extends BaseEntityBlock {
    public static final MapCodec<DroneControllerBlock> CODEC = simpleCodec(DroneControllerBlock::new);

    public DroneControllerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<DroneControllerBlock> codec() {
        return CODEC;
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

    // Client-only: open the Run/Stop/log screen, regardless of what's in the player's hand (see
    // GitHub issue #1's design notes: an earlier version special-cased a held ScriptScrollItem here
    // via useItemOn, but that depends on vanilla's Item/Block interaction dispatch order and sneak
    // semantics in ways that broke twice in real-machine testing. DroneScreen now owns all
    // scroll<->controller interaction via explicit buttons instead - see DroneScreen's Copy/Run Scroll
    // buttons and FillScrollPayload/RunScrollPayload). The screen itself sends network payloads for
    // everything that touches server state.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openDroneScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
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
