package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fullscreen script IDE (issue #6), opened from DroneScreen's Edit button: the left half is a
 * {@link MultiLineEditBox} editor for one of the controller's scripts; the right half simply IS
 * the real field, seen from straight above - the game's own camera hovers over the plot
 * ({@link IdeCameraController}, 林さん's "float the viewpoint" idea) and this screen skips the
 * vanilla background blur/darkening so the normally-rendered world shows through. Hit Save &amp;
 * Run and watch the real drone work while you edit. Earlier versions also had a 2D tile view and
 * a dry-run simulator here; both were removed at 林さん's request once the real camera proved out
 * (see git history of this file to resurrect them).
 * Client-only, so no logic here is unit-testable (the camera math is - see IdeCameraMathTest);
 * the screen is verified manually in-game.
 */
public class IdeScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** How often (client ticks) the plot size/direction is re-resolved from the blocks. */
    private static final int RESCAN_INTERVAL_TICKS = 20;

    private final BlockPos pos;
    private final String scriptName;
    private final IdeCameraController cameraController;

    private MultiLineEditBox editor;

    // Survive init() re-runs on window resize: the editor widget is rebuilt, its text is not.
    private String editorText = "";
    private boolean sourceRequested = false;

    private CornerMarkerScan.PlotBounds bounds = new CornerMarkerScan.PlotBounds(
            DroneControllerBlockEntity.DEFAULT_WORLD_SIZE, 1, 1, false);
    private int tickCounter = 0;

    public IdeScreen(BlockPos pos, String scriptName) {
        super(Component.translatable("gui.micradrone.ide_screen.title"));
        this.pos = pos;
        this.scriptName = scriptName;
        this.cameraController = new IdeCameraController(pos);
    }

    @Override
    protected void init() {
        int leftX = MARGIN;
        int leftW = this.width / 2 - MARGIN - ROW_GAP;
        int buttonRowY = this.height - MARGIN - BUTTON_HEIGHT;
        int editorH = buttonRowY - ROW_GAP - TOP_Y;

        editor = new MultiLineEditBox(this.font, leftX, TOP_Y, leftW, editorH,
                Component.translatable("gui.micradrone.ide_screen.editor_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.editor"));
        editor.setCharacterLimit(DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        editor.setValue(editorText);
        editor.setValueListener(text -> editorText = text);
        addRenderableWidget(editor);

        int buttonW = (leftW - 2 * ROW_GAP) / 3;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save"), b -> save())
                .bounds(leftX, buttonRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save_run"), b -> {
                    save();
                    PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptName));
                })
                .bounds(leftX + buttonW + ROW_GAP, buttonRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.back"),
                        b -> this.minecraft.setScreen(new DroneScreen(pos)))
                .bounds(leftX + 2 * (buttonW + ROW_GAP), buttonRowY, buttonW, BUTTON_HEIGHT).build());

        if (this.minecraft != null && this.minecraft.level != null) {
            rescanPlot();
            cameraController.update(this.minecraft, bounds); // no first-frame flash from the player's own view
        }

        if (!sourceRequested) {
            sourceRequested = true;
            PacketDistributor.sendToServer(new RequestScriptSourcePayload(pos, scriptName));
        }
    }

    /**
     * Re-resolves plot size/direction by running the same corner-marker scan the server uses
     * against the client-side level (blocks are synced, so it finds the same plot - no networking).
     */
    private void rescanPlot() {
        bounds = CornerMarkerScan.scan(
                (dx, dy, dz) -> this.minecraft.level.getBlockState(pos.offset(dx, dy, dz)).is(MicraDrone.CORNER_MARKER_BLOCK.get()),
                DroneControllerBlockEntity.MAX_MARKER_SCAN_DISTANCE,
                DroneControllerBlockEntity.MAX_MARKER_SCAN_Y_TOLERANCE,
                DroneControllerBlockEntity.DEFAULT_WORLD_SIZE);
    }

    /** Called from {@code MicraDroneClient} when the requested script source arrives. */
    public void updateSource(BlockPos sourcePos, String sourceScriptName, String source) {
        if (sourcePos.equals(this.pos) && sourceScriptName.equals(this.scriptName)) {
            editorText = source;
            editor.setValue(source);
        }
    }

    private void save() {
        PacketDistributor.sendToServer(new SaveScriptPayload(pos, scriptName, editorText));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        tickCounter++;
        if (tickCounter % RESCAN_INTERVAL_TICKS == 0) {
            rescanPlot();
        }
        // Every tick: follows corner-marker changes, FOV changes, and window resizes.
        cameraController.update(this.minecraft, bounds);
    }

    /**
     * The vanilla blur + dark overlay would hide the whole point of this screen - skip them so
     * the world (rendered from the overhead camera) shows through crisply.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    /** Called when this screen is closed or replaced - the viewpoint must always come back. */
    @Override
    public void removed() {
        if (this.minecraft != null) {
            cameraController.restore(this.minecraft);
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.ide_screen.heading", scriptName),
                this.width / 2, MARGIN, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
