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
 * All the scroll-specific behavior when used on a {@link DroneControllerBlockEntity} lives on
 * {@link DroneControllerBlock#useItemOn} instead of an override here - see that method's comment for
 * why an {@code Item#useOn} override on this class would never actually be reached. Used anywhere
 * else (including empty air), this item behaves as a plain {@code WritableBookItem}: right-clicking
 * opens the book-and-quill edit screen.
 */
public class ScriptScrollItem extends WritableBookItem {
    public ScriptScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.micradrone.script_scroll.tooltip"));
    }
}
