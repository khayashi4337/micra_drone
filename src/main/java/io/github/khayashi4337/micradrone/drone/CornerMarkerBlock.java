package io.github.khayashi4337.micradrone.drone;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
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
 * a standalone identity independent of any controller - see that class's doc for the sequence-number/
 * friendly-name split. This block is the only thing that talks to {@link CornerMarkerNameRegistry}
 * and {@link CornerMarkerSequenceRegistry}: assigning a number and claiming a name on placement
 * ({@link #setPlacedBy}), releasing the name on removal ({@link #onRemove}), since the BlockEntity
 * itself has no way to see other markers in the world.
 */
public class CornerMarkerBlock extends BaseEntityBlock {
    public static final MapCodec<CornerMarkerBlock> CODEC = simpleCodec(CornerMarkerBlock::new);

    /**
     * Redstone output: a script sets this via {@code set_output()}, and the marker then
     * emits full power (15) on every side - both weak (into wire) and strong/direct (straight into a
     * lamp or piston with no wire needed), mirroring how {@link net.minecraft.world.level.block.RedstoneTorchBlock}
     * emits (see {@link #getSignal}/{@link #getDirectSignal}), just without that block's single-face
     * restriction, since this block has no "front" the way a torch does. Reuses vanilla's own
     * {@code POWERED} property rather than inventing a new one.
     */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public CornerMarkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    public MapCodec<CornerMarkerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return getSignal(state, level, pos, side);
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
     * Three things happen here, the only place with both the Level and the placing player at once:
     * <ul>
     *   <li>A brand new marker (never placed before, {@link CornerMarkerBlockEntity#hasSequenceNumber}
     *       still false) draws its permanent number from {@link CornerMarkerSequenceRegistry}. A
     *       marker that already has one (broken and placed again) keeps it - see that class's doc.</li>
     *   <li>Either way, that number is (re-)claimed in {@link CornerMarkerNumberRegistry} at this
     *       position - needed even for a pre-existing number, since the marker may have been broken
     *       and placed somewhere else. Always succeeds (numbers are unique by construction), so unlike
     *       the name below there's nothing to reject here.</li>
     *   <li>If the marker has a friendly name, it's claimed in the world-wide
     *       {@link CornerMarkerNameRegistry}. Rejected (cleared back to "no friendly name", placer
     *       told in chat why) if another marker already owns that name, OR if the name is purely
     *       numeric: {@link CornerMarkerBlockEntity#resolveId} tries the name registry before falling
     *       back to parsing {@code id} as a number, so a marker legitimately named e.g. "3" would make
     *       any OTHER marker whose auto-assigned number happens to be 3 permanently unreachable by
     *       pair_with("3") - both would show that same string from get_plot_id(), but only the named
     *       one could ever be resolved.</li>
     * </ul>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof CornerMarkerBlockEntity be)) {
            return;
        }
        if (!be.hasSequenceNumber()) {
            be.assignSequenceNumber(CornerMarkerSequenceRegistry.get(serverLevel).nextNumber());
        }
        CornerMarkerNumberRegistry.get(serverLevel).claim(be.sequenceNumber(), pos);
        String name = be.friendlyName();
        if (name.isEmpty()) {
            return;
        }
        if (isPurelyNumeric(name)) {
            be.clearFriendlyName();
            if (placer instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal(
                        "[corner marker] the name '" + name + "' looks like a plain number, which could be "
                                + "confused with another marker's auto-assigned id - placed without a name"));
            }
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

    /** True if {@code name} would parse as a number - see {@link CornerMarkerBlockEntity#resolveId}'s fallback. */
    private static boolean isPurelyNumeric(String name) {
        try {
            Integer.parseInt(name);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // A freshly placed marker always starts unpowered (see the constructor), but neighbors still need
    // telling a new signal source exists at all - same reasoning as RedstoneTorchBlock's onPlace.
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), this);
        }
    }

    // Releases this marker's claimed name and number (if any) so a future marker elsewhere can reuse
    // the name (the number is never reused, but releasing keeps the index from pointing at an empty
    // position - see CornerMarkerNumberRegistry), and - if it was powered - tells neighbors the signal
    // just dropped to 0 (same reasoning as RedstoneTorchBlock's onRemove; movedByPiston is excluded
    // because the piston move itself already triggers the neighbor updates once the block lands in
    // its new spot).
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.hasBlockEntity() && !state.is(newState.getBlock())) {
            if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof CornerMarkerBlockEntity be) {
                if (!be.friendlyName().isEmpty()) {
                    CornerMarkerNameRegistry.get(serverLevel).release(be.friendlyName(), pos);
                }
                if (be.hasSequenceNumber()) {
                    CornerMarkerNumberRegistry.get(serverLevel).release(be.sequenceNumber(), pos);
                }
            }
            if (!movedByPiston) {
                for (Direction direction : Direction.values()) {
                    level.updateNeighborsAt(pos.relative(direction), this);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
