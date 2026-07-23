package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.WritableBookItem;

/**
 * A portable, freely-rewritable carrier for a script (GitHub issue #1): unlike the {@code .mdrone}
 * files under a controller's script folder, this can be handed or traded to another player.
 * Subclasses {@link WritableBookItem} purely to reuse its book-and-quill edit screen for free - its
 * pages never "sign"/lock into a {@code WrittenBookItem}, so it stays rewritable forever, which is
 * exactly the behavior the issue asked for.
 * <p>
 * Block-facing behavior lives in two verified-dispatch-order places (see the issue-#1 saga for why
 * this matters): slotting into a controller is {@link DroneControllerBlock#useItemOn}, and the
 * enchanting-table inscription (issue #8) is {@code MicraDrone#onRightClickBlock}, a
 * {@code PlayerInteractEvent.RightClickBlock} handler that fires before vanilla's own
 * hand-dispatch logic even starts - unlike a per-item {@code onItemUseFirst} override (this class's
 * first attempt, real-machine-tested and found to miss the off-hand case: vanilla only tries a
 * SINGLE hand's onItemUseFirst before the block's own useWithoutItem already consumes the click and
 * opens the vanilla enchanting screen), that handler inspects both of the player's hands directly,
 * so which hand holds the blank scroll doesn't matter. Used anywhere else (including empty air),
 * this item behaves as a plain {@code WritableBookItem}: right-clicking opens the book-and-quill
 * edit screen.
 */
public class ScriptScrollItem extends WritableBookItem {
    public ScriptScrollItem(Properties properties) {
        super(properties);
    }

    /** True for a {@code ScriptScrollItem} with no written content yet - the enchanting table's trigger condition. */
    public static boolean isBlank(ItemStack stack) {
        return stack.getItem() instanceof ScriptScrollItem && ScriptChestLibrary.scrollSource(stack).isEmpty();
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.micradrone.script_scroll.tooltip"));
    }
}
