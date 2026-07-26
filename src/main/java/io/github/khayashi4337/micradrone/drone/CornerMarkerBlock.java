package io.github.khayashi4337.micradrone.drone;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Sizes its paired controller's plot (see {@link DroneControllerBlockEntity#scanForCornerMarker}) and
 * doubles as an entry point for that controller's unlock shop: right-clicking opens
 * {@code ShopScreen} (the IDE's own Shop button is the other entry point), which resolves back to
 * the paired controller server-side (see {@link DroneControllerBlockEntity#findByCornerMarker}).
 * Putting the shop here instead of a second tab on DroneScreen was a deliberate choice - see that
 * class's history for why.
 * <p>
 * BlockEntity-backed ({@link CornerMarkerBlockEntity}, 林さんの「IDで管理」構想) so a placed marker has
 * a standalone identity independent of any controller - see that class's doc for the id/friendly-name
 * split. This block is the only thing that talks to {@link CornerMarkerNameRegistry}: claiming a
 * name on placement ({@link #setPlacedBy}) and releasing it on removal ({@link #onRemove}), since
 * the BlockEntity itself has no way to see other markers in the world.
 */
public class CornerMarkerBlock extends BaseEntityBlock {
    public static final MapCodec<CornerMarkerBlock> CODEC = simpleCodec(CornerMarkerBlock::new);

    public CornerMarkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<CornerMarkerBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openShopScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CornerMarkerBlockEntity(pos, state);
    }

    // BaseEntityBlock defaults to RenderShape.INVISIBLE, assuming a BlockEntityRenderer will draw the
    // block instead. We don't have one - render the normal baked model from our blockstate/model json.
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Claims this marker's friendly name (if it has one) in the world-wide
     * {@link CornerMarkerNameRegistry}. If another marker already owns that name, the rename is
     * rejected right here - the only place with both the Level and the placing player at once: the
     * name is cleared back to "no friendly name" and the placer is told in chat why, rather than
     * silently keeping a name that couldn't actually be looked up unambiguously.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof CornerMarkerBlockEntity be)) {
            return;
        }
        String name = be.friendlyName();
        if (name.isEmpty()) {
            return;
        }
        if (!CornerMarkerNameRegistry.get(serverLevel).tryClaim(name, pos)) {
            be.clearFriendlyName();
            if (placer instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal(
                        "[corner marker] the name '" + name + "' is already used by another marker - placed without a name"));
            }
        }
    }

    // Releases this marker's claimed name (if any) so a future marker elsewhere can reuse it.
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock())
                && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof CornerMarkerBlockEntity be
                && !be.friendlyName().isEmpty()) {
            CornerMarkerNameRegistry.get(serverLevel).release(be.friendlyName(), pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
