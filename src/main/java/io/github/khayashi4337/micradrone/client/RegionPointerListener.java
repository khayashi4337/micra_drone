package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.RegionPointerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Writes RegionPointerItem's left/right-click corners into RegionSelectionHolder.PENDING.
 * Instance-level {@code @SubscribeEvent} on a standalone object registered from
 * {@code MicraDroneClient}'s constructor - NOT a class-level {@code @EventBusSubscriber}, and NOT
 * static methods - matching EnchantTableWatcher's documented real-machine crash finding (a static
 * {@code @SubscribeEvent} rejects the whole class at {@code EventBus.register}).
 */
public final class RegionPointerListener {

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !holdingPointer(event)) {
            return;
        }
        // Suppresses Block#attack in survival (SPK-3: has no effect in creative, a known, documented limitation).
        event.setCanceled(true);
        BlockPos pos = event.getPos();
        RegionSelectionHolder.PENDING.setCorner1(pos.getX(), pos.getY(), pos.getZ());
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!holdingPointer(event)) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        BlockPos pos = event.getPos();
        RegionSelectionHolder.PENDING.setCorner2(pos.getX(), pos.getY(), pos.getZ());
    }

    private static boolean holdingPointer(PlayerInteractEvent event) {
        ItemStack heldStack = event.getEntity().getMainHandItem();
        return heldStack.getItem() instanceof RegionPointerItem;
    }
}
