package io.github.khayashi4337.micradrone.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.net.DebugCommandPayload;
import io.github.khayashi4337.micradrone.drone.net.DebugStatePayload;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SetBreakpointsPayload;
import io.github.khayashi4337.micradrone.drone.net.StopScriptPayload;
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
 *
 * <p>Debugger (issue #6): the editor gutter shows line numbers - click one to toggle a
 * breakpoint (red). The line about to execute is highlighted yellow, live. Pause/Resume, Step,
 * and Step Out (of the current loop) drive the server-side {@code DebugController} through
 * {@link DebugCommandPayload}; state comes back via {@link DebugStatePayload}. The highlight maps
 * lines of the SAVED script, so debugging starts from Save &amp; Run - unsaved edits shift lines.
 *
 * <p>Client-only, so no logic here is unit-testable (the camera math and debugger core are - see
 * IdeCameraMathTest/DebugControllerTest); the screen is verified manually in-game.
 */
public class IdeScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** Width of the line-number/breakpoint gutter to the left of the editor. */
    private static final int GUTTER_WIDTH = 20;
    /** How often (client ticks) the plot size/direction is re-resolved from the blocks. */
    private static final int RESCAN_INTERVAL_TICKS = 20;

    private final BlockPos pos;
    private final String scriptName;
    private final IdeCameraController cameraController;

    private DebugEditBox editor;
    private Button pauseResumeButton;

    // Survive init() re-runs on window resize: the editor widget is rebuilt, its text is not.
    private String editorText = "";
    private boolean sourceRequested = false;

    // Debugger state, driven by DebugStatePayload; breakpoints are the client's working copy.
    private final Set<Integer> breakpoints = new HashSet<>();
    private int debugState = DebugStatePayload.STATE_IDLE;

    // Gutter geometry, computed in init() and reused by render()/mouseClicked().
    private int editorTop;
    private int editorHeight;

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
        int saveRowY = this.height - MARGIN - BUTTON_HEIGHT;
        int debugRowY = saveRowY - BUTTON_HEIGHT - ROW_GAP;
        editorTop = TOP_Y;
        editorHeight = debugRowY - ROW_GAP - TOP_Y;

        editor = new DebugEditBox(this.font, leftX + GUTTER_WIDTH, editorTop, leftW - GUTTER_WIDTH, editorHeight,
                Component.translatable("gui.micradrone.ide_screen.editor_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.editor"));
        editor.setCharacterLimit(DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        editor.setValue(editorText);
        editor.setValueListener(text -> editorText = text);
        editor.setBreakpointLines(breakpoints);
        addRenderableWidget(editor);

        int debugW = (leftW - 3 * ROW_GAP) / 4;
        pauseResumeButton = addRenderableWidget(Button.builder(pauseResumeLabel(), b -> PacketDistributor.sendToServer(
                        new DebugCommandPayload(pos, debugState == DebugStatePayload.STATE_PAUSED
                                ? DebugCommandPayload.COMMAND_RESUME : DebugCommandPayload.COMMAND_PAUSE)))
                .bounds(leftX, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP)))
                .bounds(leftX + debugW + ROW_GAP, debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_step_out"),
                        b -> PacketDistributor.sendToServer(new DebugCommandPayload(pos, DebugCommandPayload.COMMAND_STEP_OUT)))
                .bounds(leftX + 2 * (debugW + ROW_GAP), debugRowY, debugW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.debug_stop"),
                        b -> PacketDistributor.sendToServer(new StopScriptPayload(pos)))
                .bounds(leftX + 3 * (debugW + ROW_GAP), debugRowY, debugW, BUTTON_HEIGHT).build());

        int buttonW = (leftW - 3 * ROW_GAP) / 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save"), b -> save())
                .bounds(leftX, saveRowY, buttonW, BUTTON_HEIGHT).build());
        // Plain Run: runs the SAVED script without touching unsaved editor changes - handy when
        // re-running a debug session repeatedly.
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.run"),
                        b -> PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptName)))
                .bounds(leftX + buttonW + ROW_GAP, saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.save_run"), b -> {
                    save();
                    PacketDistributor.sendToServer(new RunScriptPayload(pos, scriptName));
                })
                .bounds(leftX + 2 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.back"),
                        b -> this.minecraft.setScreen(new DroneScreen(pos)))
                .bounds(leftX + 3 * (buttonW + ROW_GAP), saveRowY, buttonW, BUTTON_HEIGHT).build());

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

    /** Called from {@code MicraDroneClient} when a DebugStatePayload arrives for this controller. */
    public void updateDebugState(BlockPos sourcePos, int state, int currentLine, List<Integer> serverBreakpoints) {
        if (!sourcePos.equals(this.pos)) {
            return;
        }
        debugState = state;
        breakpoints.clear();
        breakpoints.addAll(serverBreakpoints);
        editor.setBreakpointLines(breakpoints);
        editor.setCurrentLine(state == DebugStatePayload.STATE_IDLE ? 0 : currentLine);
        pauseResumeButton.setMessage(pauseResumeLabel());
    }

    private Component pauseResumeLabel() {
        return Component.translatable(debugState == DebugStatePayload.STATE_PAUSED
                ? "gui.micradrone.ide_screen.debug_resume" : "gui.micradrone.ide_screen.debug_pause");
    }

    private void save() {
        PacketDistributor.sendToServer(new SaveScriptPayload(pos, scriptName, editorText));
    }

    private int lineCount() {
        int lines = 1;
        for (int i = 0; i < editorText.length(); i++) {
            if (editorText.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** Gutter clicks toggle a breakpoint on the clicked line; everything else goes to the widgets. */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= MARGIN && mouseX < MARGIN + GUTTER_WIDTH
                && mouseY >= editorTop && mouseY < editorTop + editorHeight) {
            int line = (int) ((mouseY - editorTop - editor.gutterTopPadding() + editor.gutterScroll())
                    / DebugEditBox.LINE_HEIGHT) + 1;
            if (line >= 1 && line <= lineCount()) {
                if (!breakpoints.remove(line)) {
                    breakpoints.add(line);
                }
                editor.setBreakpointLines(breakpoints);
                PacketDistributor.sendToServer(new SetBreakpointsPayload(pos, breakpoints.stream().sorted().toList()));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        renderGutter(guiGraphics);
    }

    /** Line numbers + breakpoint dots, scrolled in sync with the editor text. */
    private void renderGutter(GuiGraphics guiGraphics) {
        guiGraphics.fill(MARGIN, editorTop, MARGIN + GUTTER_WIDTH, editorTop + editorHeight, 0xE0101010);
        int firstTextY = editorTop + editor.gutterTopPadding();
        int scroll = (int) editor.gutterScroll();
        int total = lineCount();
        for (int line = 1; line <= total; line++) {
            int y = firstTextY + (line - 1) * DebugEditBox.LINE_HEIGHT - scroll;
            if (y < editorTop || y + DebugEditBox.LINE_HEIGHT > editorTop + editorHeight) {
                continue;
            }
            if (breakpoints.contains(line)) {
                guiGraphics.fill(MARGIN + 1, y + 1, MARGIN + 6, y + 6, 0xFFCC3333);
            }
            String label = String.valueOf(line);
            guiGraphics.drawString(this.font, label,
                    MARGIN + GUTTER_WIDTH - 2 - this.font.width(label), y, 0xFF808080, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
