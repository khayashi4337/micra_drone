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
 * Block-facing behavior: slotting into a controller is {@link DroneControllerBlock#useItemOn}, and
 * the enchanting-table inscription (issue #8) is entirely event-driven - see
 * {@code EnchantTableWatcher} in the client package: dropping a blank scroll into the vanilla
 * enchanting table's own item slot (the table's normal drag-and-drop GUI, opened completely
 * normally, no interception on the click itself) opens a sample picker in place of it. Used
 * anywhere else (including empty air), this item behaves as a plain {@code WritableBookItem}:
 * right-clicking opens the book-and-quill edit screen.
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
