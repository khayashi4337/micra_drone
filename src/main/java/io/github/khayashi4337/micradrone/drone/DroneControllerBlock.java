package io.github.khayashi4337.micradrone.drone;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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

    // Client-only: open the IDE straight away, editing the slotted scroll (issue #8 - the editor
    // IS the controller's default screen; the list/log screen is behind its Scripts button). The
    // screen itself sends network payloads for everything that touches server state.
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openIdeScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Jukebox-style scroll slotting (issue #7): plain right-click WITH a scroll in hand inserts it
     * into the controller; every other item PASSes so the empty-hand/other-item click still opens
     * DroneScreen via {@link #useWithoutItem}. Design note versus the issue-#1 dispatch saga: the
     * two historical bugs were a consuming useWithoutItem with no useItemOn at all, and a
     * sneak-modifier scheme (sneak+item bypasses block interaction entirely, see
     * doesSneakBypassUse). This hook is the vanilla jukebox/flower-pot pattern - one item type,
     * no modifier keys - and stays inside the dispatch order verified in ServerPlayerGameMode.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
            be.insertScroll(player, stack);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    // Redstone Run/Stop (issue #7): lever ON runs the slotted scroll, OFF stops it - the GUI-free
    // control path. Edge detection lives in the BlockEntity (RedstoneEdge).
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
            be.onNeighborSignalChange(level.hasNeighborSignal(pos));
        }
    }

    // Clean up the visible DroneEntity so it doesn't linger after the controller that owns it is
    // gone, and pop the slotted scroll out so it isn't lost with the block.
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be) {
            be.dropSlottedScroll();
            be.discardDroneEntity();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
