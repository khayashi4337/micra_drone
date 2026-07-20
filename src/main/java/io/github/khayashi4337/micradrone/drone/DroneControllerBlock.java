package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
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

    // Client-only: open the Run/Stop/log screen. The screen itself sends network payloads for
    // everything that touches server state (see MicraDroneClient/DroneScreen).
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openDroneScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Handles a held {@link ScriptScrollItem} (GitHub issue #1); any other item (including an empty
     * hand) falls through to {@link #useWithoutItem}. This has to live here rather than as an
     * override on {@code ScriptScrollItem#useOn} - the vanilla dispatch order
     * (see {@code ServerPlayerGameMode#useItemOn}) tries a block's item-aware hook (this method)
     * first, and if it passes, tries {@link #useWithoutItem} next - which this block always answers
     * (it opens {@code DroneScreen} unconditionally) - before an item's own {@code useOn} would ever
     * get a turn. So without this override, right-clicking with any item, scroll included, just always
     * opened the normal Run screen and the scroll's own logic was unreachable.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof ScriptScrollItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<String> pages = content.pages().stream().map(Filterable::raw).toList();

        if (ScriptScrollContent.isBlank(pages)) {
            // Sneak = pick one of this controller's saved scripts (ScrollPickScreen); plain click =
            // write freely in the normal book-and-quill screen (the discoverable, primary action).
            if (player.isSecondaryUseActive()) {
                if (level.isClientSide) {
                    MicraDroneClient.openScrollPickScreen(pos, hand);
                }
            } else {
                player.openItemGui(stack, hand);
            }
        } else if (!level.isClientSide && level.getBlockEntity(pos) instanceof DroneControllerBlockEntity be
                && player instanceof ServerPlayer serverPlayer) {
            be.applyScroll(serverPlayer, ScriptScrollContent.joinPages(pages));
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
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
