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
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The enchanting table's sample picker (issue #8), opened by using a BLANK script scroll on the
 * table (see {@code ScriptScrollItem#onItemUseFirst}). Lists every {@link SampleCatalog} entry;
 * ones needing more bookshelves than currently surround the table are greyed out with their
 * requirement shown, so building up the library is a visible goal. The bookshelf count re-runs
 * vanilla's own rule against the synced client level every tick - placing bookshelves while the
 * screen is open unlocks entries live. Inscribing sends {@link EnchantScrollPayload}; the server
 * re-validates everything and takes the lapis (see {@code ScrollEnchanter}).
 * Client-only, so no logic here is unit-testable - verified manually in-game.
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
    private final InteractionHand hand;

    private MultiLineEditBox descriptionBox;
    private SampleListWidget sampleList;
    private Button inscribeButton;
    private int bookshelfCount;

    public EnchantScrollScreen(BlockPos tablePos, InteractionHand hand) {
        super(Component.translatable("gui.micradrone.enchant_scroll.title"));
        this.tablePos = tablePos;
        this.hand = hand;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_WIDTH) / 2;
        recountBookshelves();

        descriptionBox = new MultiLineEditBox(this.font, left, DESCRIPTION_Y, PANEL_WIDTH, DESCRIPTION_HEIGHT,
                Component.translatable("gui.micradrone.drone_screen.script_description_placeholder"),
                Component.translatable("gui.micradrone.drone_screen.script_description"));
        addRenderableWidget(descriptionBox);

        sampleList = new SampleListWidget(Minecraft.getInstance(), PANEL_WIDTH, LIST_HEIGHT, LIST_Y, 16);
        sampleList.setX(left);
        for (int i = 0; i < SampleCatalog.ALL.size(); i++) {
            sampleList.addRow(i);
        }
        addRenderableWidget(sampleList);

        int halfW = (PANEL_WIDTH - 4) / 2;
        inscribeButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.inscribe"), b -> inscribe())
                .bounds(left, BUTTON_Y, halfW, 20)
                .build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.micradrone.enchant_scroll.cancel"), b -> onClose())
                .bounds(left + halfW + 4, BUTTON_Y, halfW, 20)
                .build());

        sampleList.selectFirst();
        refreshInscribeButton();
    }

    private void inscribe() {
        SampleListWidget.Row selected = sampleList.getSelected();
        if (selected != null && SampleCatalog.isUnlocked(selected.index, bookshelfCount)) {
            PacketDistributor.sendToServer(new EnchantScrollPayload(
                    tablePos, selected.index, hand == InteractionHand.MAIN_HAND));
            onClose();
        }
    }

    private void recountBookshelves() {
        if (this.minecraft != null && this.minecraft.level != null) {
            bookshelfCount = ScrollEnchanter.countBookshelves(this.minecraft.level, tablePos);
        }
    }

    private void refreshInscribeButton() {
        SampleListWidget.Row selected = sampleList != null ? sampleList.getSelected() : null;
        if (inscribeButton != null) {
            inscribeButton.active = selected != null && SampleCatalog.isUnlocked(selected.index, bookshelfCount);
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

    /** One row per catalog entry: name on the left, lapis cost (or the bookshelf requirement) on the right. */
    private final class SampleListWidget extends ObjectSelectionList<SampleListWidget.Row> {
        SampleListWidget(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        void addRow(int index) {
            addEntry(new Row(index));
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
            if (selected != null) {
                SampleCatalog.Sample sample = SampleCatalog.ALL.get(selected.index);
                descriptionBox.setValue(ScriptFileStore.describeScript(sample.source(), sample.displayName()));
            } else {
                descriptionBox.setValue("");
            }
            refreshInscribeButton();
        }

        @Override
        public int getRowWidth() {
            return this.width - 10;
        }

        final class Row extends ObjectSelectionList.Entry<Row> {
            final int index;

            Row(int index) {
                this.index = index;
            }

            @Override
            public Component getNarration() {
                SampleCatalog.Sample sample = SampleCatalog.ALL.get(index);
                return Component.literal(sample.displayName() + ": " + status(sample).getString());
            }

            private Component status(SampleCatalog.Sample sample) {
                return SampleCatalog.isUnlocked(index, bookshelfCount)
                        ? Component.translatable("gui.micradrone.enchant_scroll.cost", sample.lapisCost())
                        : Component.translatable("gui.micradrone.enchant_scroll.locked", sample.requiredBookshelves());
            }

            @Override
            public void render(GuiGraphics guiGraphics, int rowIndex, int top, int left, int width, int height,
                    int mouseX, int mouseY, boolean hovering, float partialTick) {
                SampleCatalog.Sample sample = SampleCatalog.ALL.get(index);
                boolean unlocked = SampleCatalog.isUnlocked(index, bookshelfCount);
                int textY = top + (height - 8) / 2;
                guiGraphics.drawString(EnchantScrollScreen.this.font, sample.displayName(),
                        left + 2, textY, unlocked ? 0xFFFFFF : 0x808080);
                Component status = status(sample);
                int statusWidth = EnchantScrollScreen.this.font.width(status);
                guiGraphics.drawString(EnchantScrollScreen.this.font, status,
                        left + width - statusWidth - 6, textY, unlocked ? 0x8090FF : 0x808080);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                SampleListWidget.this.setSelected(this);
                return true;
            }
        }
    }
}
