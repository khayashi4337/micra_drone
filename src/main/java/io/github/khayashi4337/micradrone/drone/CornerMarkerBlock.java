package io.github.khayashi4337.micradrone.drone;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Sizes its paired controller's plot (see {@link DroneControllerBlockEntity#scanForCornerMarker}) and
 * doubles as the entry point for that controller's unlock shop: right-clicking opens
 * {@code ShopScreen}, which resolves back to the paired controller server-side (see
 * {@link DroneControllerBlockEntity#findByCornerMarker}). Putting the shop here instead of a second
 * tab on DroneScreen was a deliberate choice - see that class's history for why.
 */
public class CornerMarkerBlock extends Block {
    public CornerMarkerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            MicraDroneClient.openShopScreen(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
