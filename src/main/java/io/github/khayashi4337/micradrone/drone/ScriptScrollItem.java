package io.github.khayashi4337.micradrone.drone;

import java.util.List;

import io.github.khayashi4337.micradrone.MicraDroneClient;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A portable, freely-rewritable carrier for a script (GitHub issue #1): unlike the {@code .mdrone}
 * files under a controller's script folder, this can be handed or traded to another player.
 * Subclasses {@link WritableBookItem} purely to reuse its book-and-quill edit screen for free - its
 * pages never "sign"/lock into a {@code WrittenBookItem}, so it stays rewritable forever, which is
 * exactly the behavior the issue asked for. All the new behavior lives in {@link #useOn}, triggered
 * when the scroll is used on a {@link DroneControllerBlockEntity}:
 * <ul>
 *   <li>blank scroll (no pages, or whitespace only) -&gt; opens {@code ScrollPickScreen} so the
 *       player can fill it from one of that controller's saved scripts, instead of always having to
 *       type one by hand first.</li>
 *   <li>written scroll -&gt; its pages are joined back into one script, saved as that controller's
 *       {@link ScriptScrollContent#SCROLL_SCRIPT_NAME} script, and run immediately.</li>
 * </ul>
 * Any other block (or empty air) falls through to {@code WritableBookItem}'s own {@code use()},
 * which opens the book-and-quill edit screen as normal.
 */
public class ScriptScrollItem extends WritableBookItem {
    public ScriptScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof DroneControllerBlockEntity be)) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        WritableBookContent content = stack.getOrDefault(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent.EMPTY);
        List<String> pages = content.pages().stream().map(Filterable::raw).toList();

        if (ScriptScrollContent.isBlank(pages)) {
            if (level.isClientSide) {
                MicraDroneClient.openScrollPickScreen(context.getClickedPos(), context.getHand());
            }
        } else if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer serverPlayer) {
            be.applyScroll(serverPlayer, ScriptScrollContent.joinPages(pages));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
