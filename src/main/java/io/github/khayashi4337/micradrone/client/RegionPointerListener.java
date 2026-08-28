package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.RegionPointerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
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
        // Suppresses Block#attack in survival (SPK-3: has no effect in creative, a known, documented
        // limitation). Cancelled on every pass: the server-side one is what actually stops the break.
        event.setCanceled(true);
        if (recordsSelection(event)) {
            BlockPos pos = event.getPos();
            RegionSelectionHolder.PENDING.setCorner1(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!holdingPointer(event)) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        if (recordsSelection(event)) {
            BlockPos pos = event.getPos();
            RegionSelectionHolder.PENDING.setCorner2(pos.getX(), pos.getY(), pos.getZ());
        }
    }

    private static boolean holdingPointer(PlayerInteractEvent event) {
        ItemStack heldStack = event.getEntity().getMainHandItem();
        return heldStack.getItem() instanceof RegionPointerItem;
    }

    /**
     * PlayerInteractEvent fires on the integrated server's thread as well as the client's, and a
     * right-click fires once per hand. RegionSelectionHolder.PENDING is client UI state (IdeScreen
     * reads it on the render thread), so only the client-side, main-hand pass writes to it - the
     * other passes still get the cancel/deny calls above, just not the write.
     */
    private static boolean recordsSelection(PlayerInteractEvent event) {
        return event.getLevel().isClientSide() && event.getHand() == InteractionHand.MAIN_HAND;
    }
}
