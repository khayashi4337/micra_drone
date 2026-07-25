package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import io.github.khayashi4337.micradrone.drone.ScriptScrollItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Enchanting-table scroll-drop trigger (issue #8): a standalone instance registered on
 * {@code NeoForge.EVENT_BUS} from {@code MicraDroneClient}'s constructor. Deliberately NOT folded
 * into {@code MicraDroneClient} itself - a real-machine crash confirmed that class's own static
 * {@code @SubscribeEvent} methods (registered to the FML mod bus via its class-level
 * {@code @EventBusSubscriber}) make {@code EventBus.register(this)} reject the WHOLE class outright
 * ("Expected @SubscribeEvent method ... to NOT be static", thrown during mod construction, which
 * then cascaded into every other client-side event delivery breaking, Sodium's own config
 * registration included - the "Sodium mod config not found" crash traced back to this). Keeping
 * every {@code @SubscribeEvent} method here at the instance level, with no class-level
 * {@code @EventBusSubscriber} at all, avoids that conflict entirely.
 * <p>
 * Mechanism: {@link #onRightClickBlock} (not cancelled - vanilla's own enchanting screen opens
 * completely normally) remembers which table the player just clicked, purely because
 * {@link EnchantmentMenu} doesn't expose its own block position. {@link #onContainerOpen} then
 * watches that menu's item slot (slot 0) AND lapis slot (slot 1) via vanilla's own
 * {@link ContainerListener} API, and only swaps to the picker once BOTH a blank script scroll and
 * at least one lapis lazuli are sitting in the table (real-machine report: gating on the scroll
 * alone opened the picker - a bare screen with no slot UI - the instant the scroll landed, which
 * made it impossible to place lapis afterward; this lets the player fill either slot first in any
 * order, exactly like they would with vanilla's own item+lapis enchanting). Vanilla's own
 * {@code EnchantmentMenu} rejects blank scrolls as "not enchantable" (real-machine screenshot
 * confirmed all three enchant slots read as 0-cost for one) so this never fights the real
 * enchanting flow - only intercepts the one case vanilla can't do anything useful with anyway.
 * <p>
 * The slot-changed callback only records the table position and does NOT call
 * {@code Minecraft.setScreen} directly - a real-machine crash (NullPointerException on
 * {@code this.minecraft} inside the freshly-opened screen's very first render) confirmed that
 * swapping the active screen from deep inside vanilla's own container-click handling (the call
 * stack that reaches {@link ContainerListener#slotChanged} when a slot's contents change) is
 * unsafe - vanilla's slot-click code keeps working with the screen it started with after that
 * point, and something in that continued work leaves the new screen half-initialized.
 * {@link #onClientTick} instead performs the actual screen swap on the next tick, well outside
 * any input-handling call stack, which is the standard way to defer this kind of reentrant
 * {@code setScreen} call.
 */
public final class EnchantTableWatcher {
    /** {@link EnchantmentMenu} slot indices - see the vanilla source (slot 0 = item, slot 1 = lapis). */
    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;

    private BlockPos pendingEnchantTablePos;
    private BlockPos pendingScreenOpen;

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().getBlockState(event.getPos()).is(Blocks.ENCHANTING_TABLE)) {
            pendingEnchantTablePos = event.getPos();
        }
    }

    /**
     * Stays registered for the whole menu-open session (not one-shot) - {@link EnchantScrollScreen}
     * itself hands control back to vanilla's own enchanting screen when the scroll leaves the item
     * slot, so the player can place a scroll, take it out, and place one again without reopening the
     * table; each time the blank+lapis condition newly holds, this re-arms the picker. The
     * already-showing-our-screen guard in {@link #onClientTick} stops it from tearing down and
     * rebuilding the picker on redundant slot-change events while it's already open.
     */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        BlockPos tablePos = pendingEnchantTablePos;
        if (tablePos == null || !(event.getContainer() instanceof EnchantmentMenu menu)) {
            return;
        }
        menu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu changedMenu, int slotIndex, ItemStack stack) {
                if (slotIndex != ITEM_SLOT && slotIndex != LAPIS_SLOT) {
                    return;
                }
                if (ScriptScrollItem.isBlank(changedMenu.getSlot(ITEM_SLOT).getItem())
                        && !changedMenu.getSlot(LAPIS_SLOT).getItem().isEmpty()) {
                    pendingScreenOpen = tablePos;
                }
            }

            @Override
            public void dataChanged(AbstractContainerMenu changedMenu, int dataSlotIndex, int value) {
            }
        });
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (pendingScreenOpen != null) {
            BlockPos tablePos = pendingScreenOpen;
            pendingScreenOpen = null;
            if (!(Minecraft.getInstance().screen instanceof EnchantScrollScreen)) {
                MicraDroneClient.openEnchantScrollScreen(tablePos);
            }
        }
    }
}
