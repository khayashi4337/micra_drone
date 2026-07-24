package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.SampleCatalog;
import io.github.khayashi4337.micradrone.drone.ScriptFileStore;
import io.github.khayashi4337.micradrone.drone.ScrollEnchanter;
import io.github.khayashi4337.micradrone.drone.net.EnchantScrollPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The enchanting table's scroll picker (issue #8, extended by the GUI-reduction follow-up):
 * dropping a BLANK script scroll into the vanilla enchanting table's own item slot - the normal
 * drag-and-drop way that GUI already works - opens this screen in place of it (see
 * {@code EnchantTableWatcher}). Lists every {@link SampleCatalog} entry (ones needing more
 * bookshelves than currently surround the table are greyed out with their requirement shown, so
 * building up the library is a visible goal - the bookshelf count re-runs vanilla's own rule
 * against the synced client level every tick, so placing bookshelves while the screen is open
 * unlocks entries live), followed by every already-written scroll sitting in a chiseled bookshelf
 * around the table ({@link ScrollEnchanter#findCopySources}, a flat-cost copy with no lock -
 * computed once at open time, not re-scanned every tick like the sample lock does, since
 * rearranging a table's bookshelf contents mid-picker is not a case worth the extra complexity).
 * Inscribing sends {@link EnchantScrollPayload}; the server re-validates everything, takes the
 * lapis, and writes straight into the scroll still sitting in the table's slot (see
 * {@code ScrollEnchanter}). Since this screen stands in for the vanilla {@code EnchantmentScreen}
 * without ever telling the server that menu closed, {@link #onClose} closes it properly (same
 * packet vanilla's own Escape/X would send) - that's also what hands the (now-inscribed, or
 * still-blank on Cancel) scroll back to the player, via vanilla's normal container-close item
 * return. Client-only, so no logic here is unit-testable - verified manually in-game.
 */
public class EnchantScrollScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int HEADING_Y = 8;
    private static final int BOOKSHELVES_Y = 20;
    private static final int LIST_Y = 32;
    private static final int LIST_HEIGHT = 112;
    private static final int DESCRIPTION_Y = LIST_Y + LIST_HEIGHT + 6;
    private static final int DESCRIPTION_HEIGHT = 28;
    private static final int BUTTON_Y = DESCRIPTION_Y + DESCRIPTION_HEIGHT + 8;

    private final BlockPos tablePos;

    private MultiLineEditBox descriptionBox;
    private PickerListWidget pickerList;
    private Button inscribeButton;
    private int bookshelfCount;

    public EnchantScrollScreen(BlockPos tablePos) {
        super(Component.translatable("gui.micradrone.enchant_scroll.title"));
        this.tablePos = tablePos;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        recountBookshelves();

        descriptionBox = new MultiLineEditBox(this.font, left, DESCRIPTION_Y, PANEL_WIDTH, DESCRIPTION_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.script_description_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.script_description"));
        addRenderableWidget(descriptionBox);

        pickerList = new PickerListWidget(Minecraft.getInstance(), PANEL_WIDTH, LIST_HEIGHT, LIST_Y, 16);
        pickerList.setX(left);
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            pickerList.addSampleRow(i);
        }
        if (this.minecraft != null && this.minecraft.level != null) {
            for (ScrollEnchanter.CopySource copy : ScrollEnchanter.findCopySources(this.minecraft.level, tablePos)) {
                pickerList.addCopyRow(copy);
            }
        }
        addRenderableWidget(pickerList);

        int halfW = (PANEL_WIDTH - 4) / 2;
        inscribeButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.inscribe"), b -> inscribe())
                .bounds(left, BUTTON_Y, halfW, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.cancel"), b -> onClose())
                .bounds(left + halfW + 4, BUTTON_Y, halfW, 20)
                .build());

        pickerList.selectFirst();
        refreshInscribeButton();
    }

    private void inscribe() {
        PickerListWidget.Row selected = pickerList.getSelected();
        if (selected == null || selected.locked(bookshelfCount)) {
            return;
        }
        if (selected.copy != null) {
            PacketDistributor.sendToServer(
                    new EnchantScrollPayload(tablePos, -1, selected.copy.bookshelfOffsetIndex(), selected.copy.slot()));
        } else {
            PacketDistributor.sendToServer(new EnchantScrollPayload(tablePos, selected.sampleIndex, -1, -1));
        }
        onClose();
    }

    /**
     * This screen stands in for the vanilla enchanting menu's own screen without the server ever
     * being told that menu closed - closing it here for real (not just swapping the client's
     * displayed Screen) is what returns the (now-inscribed) scroll to the player, exactly like
     * closing a real enchanting table normally does.
     */
    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.closeContainer();
        } else {
            super.onClose();
        }
    }

    private void recountBookshelves() {
        if (this.minecraft != null && this.minecraft.level != null) {
            bookshelfCount = ScrollEnchanter.countBookshelves(this.minecraft.level, tablePos);
        }
    }

    private void refreshInscribeButton() {
        PickerListWidget.Row selected = pickerList != null ? pickerList.getSelected() : null;
        if (inscribeButton != null) {
            inscribeButton.active = selected != null && !selected.locked(bookshelfCount);
        }
    }

    @Override
    public void tick() {
        super.tick();
        recountBookshelves();
        refreshInscribeButton();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, HEADING_Y, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.enchant_scroll.bookshelves",
                        bookshelfCount, SampleCatalog.MAX_BOOKSHELVES),
                this.width / 2, BOOKSHELVES_Y, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * One row per entry: {@link SampleCatalog} samples first (locked by bookshelf count, name +
     * cost/requirement), then bookshelf copy candidates (never locked, flat
     * {@link ScrollEnchanter#COPY_LAPIS_COST}).
     */
    private final class PickerListWidget extends ObjectSelectionList<PickerListWidget.Row> {
        PickerListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void addSampleRow(int sampleIndex) {
            addEntry(new Row(sampleIndex, null));
        }

        void addCopyRow(ScrollEnchanter.CopySource copy) {
            addEntry(new Row(-1, copy));
        }

        void selectFirst() {
            if (getItemCount() > 0) {
                setSelected(getEntry(0));
            }
        }

        /** Single hook point for every way the selection can change (same pattern as DroneScreen). */
        @Override
        public void setSelected(Row selected) {
            super.setSelected(selected);
            descriptionBox.setValue(selected != null ? selected.description() : "");
            refreshInscribeButton();
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            final int sampleIndex;
            final ScrollEnchanter.CopySource copy;

            Row(int sampleIndex, ScrollEnchanter.CopySource copy) {
                this.sampleIndex = sampleIndex;
                this.copy = copy;
            }

            boolean locked(int bookshelfCount) {
                return copy == null && !SampleCatalog.isUnlocked(sampleIndex, bookshelfCount);
            }

            String displayName() {
                return copy != null ? copy.displayName() : SampleCatalog.ALL.get(sampleIndex).displayName();
            }

            String description() {
                return copy != null ? copy.description()
                        : ScriptFileStore.describeScript(SampleCatalog.ALL.get(sampleIndex).source(), displayName());
            }

            @Override
            public Component getNarration() {
                return Component.literal(displayName() + ": " + status().getString());
            }

            private Component status() {
                if (copy != null) {
                    return Component.translatable("gui.micradrone.enchant_scroll.cost", ScrollEnchanter.COPY_LAPIS_COST);
                }
                SampleCatalog.Sample sample = SampleCatalog.ALL.get(sampleIndex);
                return locked(bookshelfCount)
                        ? Component.translatable("gui.micradrone.enchant_scroll.locked", sample.requiredBookshelves())
                        : Component.translatable("gui.micradrone.enchant_scroll.cost", sample.lapisCost());
            }

            @Override
            public void render(GuiGraphics guiGraphics, int rowIndex, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                boolean unlocked = !locked(bookshelfCount);
                int textY = top + (height - 8) / 2;
                guiGraphics.drawString(EnchantScrollScreen.this.font, displayName(),
                        left + 2, textY, unlocked ? 0xFFFFFF : 0x808080);
                Component status = status();
                int statusWidth = EnchantScrollScreen.this.font.width(status);
                guiGraphics.drawString(EnchantScrollScreen.this.font, status,
                        left + width - statusWidth - 6, textY, unlocked ? 0x8090FF : 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                PickerListWidget.this.setSelected(this);
                return true;
            }
        }
    }
}
