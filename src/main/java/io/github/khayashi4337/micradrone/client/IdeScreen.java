package io.github.khayashi4337.micradrone.client;

import java.util.ArrayList;
import java.util.List;

import io.github.khayashi4337.micradrone.drone.DroneControllerBlockEntity;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import io.github.khayashi4337.micradrone.drone.PlotColorRules;
import io.github.khayashi4337.micradrone.drone.net.RequestScriptSourcePayload;
import io.github.khayashi4337.micradrone.drone.net.RunScriptPayload;
import io.github.khayashi4337.micradrone.drone.net.SaveScriptPayload;
import io.github.khayashi4337.micradrone.drone.sim.ScriptSimulator;
import io.github.khayashi4337.micradrone.drone.sim.SimFrame;
import io.github.khayashi4337.micradrone.drone.sim.SimLogLine;
import io.github.khayashi4337.micradrone.drone.sim.SimTrace;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fullscreen script IDE (issue #6), opened from DroneScreen's Edit button: the left half is a
 * {@link MultiLineEditBox} editor for one of the controller's scripts, the right half is a
 * bird's-eye view of the plot with three modes:
 * <ul>
 *   <li><b>3D (default)</b> - the game's own camera hovers straight above the REAL field
 *       ({@link IdeCameraController}, 林さん's "float the viewpoint" proposal) and this screen's
 *       background goes fully transparent, so the right half of the screen simply IS the world,
 *       rendered normally: real blocks, real growth, the real drone flying. No second-view
 *       framebuffer machinery involved.</li>
 *   <li><b>2D</b> - minimap-style tiles sampled from client-synced block states every frame
 *       ({@link LivePlotView}): still the real farm, as flat colored cells.</li>
 *   <li><b>Sim</b> - a dry-run preview ({@link ScriptSimulator} - real interpreter on an
 *       in-memory plot; the actual farm is never touched) with playback controls.</li>
 * </ul>
 * North is up in every mode: grid y grows southward/downward exactly like the real plot's grid
 * coordinates. Client-only, so no logic here is unit-testable (the simulation, palette, and
 * camera math are - see ScriptSimulatorTest/PlotColorRulesTest/IdeCameraMathTest); the screen is
 * verified manually in-game.
 */
public class IdeScreen extends Screen {
    private enum ViewMode { LIVE_3D, LIVE_2D, SIM }

    private static final int MARGIN = 8;
    private static final int TOP_Y = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    /** Playback tick intervals per speed step: 5, 10, 20 frames per second. */
    private static final int[] TICKS_PER_FRAME = {4, 2, 1};
    private static final String[] SPEED_LABELS = {"1x", "2x", "4x"};
    private static final int MIN_PLOT = 3;
    private static final int MAX_PLOT = 9;
    private static final int DEFAULT_PLOT = 5;
    /** How often (client ticks) the live view re-resolves plot size/direction from the blocks. */
    private static final int LIVE_RESCAN_INTERVAL_TICKS = 20;

    private final BlockPos pos;
    private final String scriptName;
    private final LivePlotView livePlot;
    private final IdeCameraController cameraController;

    private MultiLineEditBox editor;
    private MultiLineEditBox simLogBox;
    private Button playPauseButton;
    private Button viewToggleButton;
    /** Everything that only makes sense while the sim view is showing - hidden in live view. */
    private final List<AbstractWidget> simOnlyWidgets = new ArrayList<>();

    // Survive init() re-runs on window resize: the editor widget is rebuilt, its text is not.
    private String editorText = "";
    private boolean sourceRequested = false;

    private ViewMode mode = ViewMode.LIVE_3D;
    private SimTrace trace;
    private int frameIndex = 0;
    private boolean playing = false;
    private int speedIndex = 0;
    private int plotSize = DEFAULT_PLOT;
    private int tickCounter = 0;
    /** The frame the sim log box was last rebuilt for, so it only re-renders text on change. */
    private int logRenderedForFrame = -1;

    // Grid panel geometry, computed in init() and reused by render().
    private int gridX;
    private int gridY;
    private int gridPanelSize;
    private int statusY;

    public IdeScreen(BlockPos pos, String scriptName) {
        super(Component.translatable("gui.micradrone.ide_screen.title"));
        this.pos = pos;
        this.scriptName = scriptName;
        this.livePlot = new LivePlotView(pos);
        this.cameraController = new IdeCameraController(pos);
    }

    @Override
    protected void init() {
        simOnlyWidgets.clear();

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

        int rightX = this.width / 2 + ROW_GAP;
        int rightW = this.width - MARGIN - rightX;

        // The grid panel takes what the two control rows + status lines + a minimum log box leave.
        int reservedBelowGrid = 2 * (BUTTON_HEIGHT + ROW_GAP) + 22 + 40 + MARGIN;
        gridPanelSize = Math.max(60, Math.min(rightW, this.height - TOP_Y - reservedBelowGrid));
        gridX = rightX + (rightW - gridPanelSize) / 2;
        gridY = TOP_Y;

        int controls1Y = gridY + gridPanelSize + ROW_GAP;
        int quarterW = (rightW - 3 * ROW_GAP) / 4;
        viewToggleButton = addRenderableWidget(Button.builder(viewToggleLabel(), b -> setMode(switch (mode) {
                    case LIVE_3D -> ViewMode.LIVE_2D;
                    case LIVE_2D -> ViewMode.SIM;
                    case SIM -> ViewMode.LIVE_3D;
                }))
                .bounds(rightX, controls1Y, quarterW, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.simulate"), b -> {
                    setMode(ViewMode.SIM);
                    simulate();
                })
                .bounds(rightX + quarterW + ROW_GAP, controls1Y, quarterW, BUTTON_HEIGHT).build());
        playPauseButton = addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.play"), b -> togglePlay())
                .bounds(rightX + 2 * (quarterW + ROW_GAP), controls1Y, quarterW, BUTTON_HEIGHT).build());
        simOnlyWidgets.add(playPauseButton);
        simOnlyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.step"), b -> step())
                .bounds(rightX + 3 * (quarterW + ROW_GAP), controls1Y, quarterW, BUTTON_HEIGHT).build()));

        int controls2Y = controls1Y + BUTTON_HEIGHT + ROW_GAP;
        int thirdW = (rightW - 2 * ROW_GAP) / 3;
        simOnlyWidgets.add(addRenderableWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.reset"), b -> {
                    frameIndex = 0;
                    playing = false;
                    updatePlayPauseLabel();
                })
                .bounds(rightX, controls2Y, thirdW, BUTTON_HEIGHT).build()));
        simOnlyWidgets.add(addRenderableWidget(Button.builder(speedLabel(), b -> {
                    speedIndex = (speedIndex + 1) % TICKS_PER_FRAME.length;
                    b.setMessage(speedLabel());
                })
                .bounds(rightX + thirdW + ROW_GAP, controls2Y, thirdW, BUTTON_HEIGHT).build()));
        simOnlyWidgets.add(addRenderableWidget(Button.builder(plotSizeLabel(), b -> {
                    plotSize = plotSize >= MAX_PLOT ? MIN_PLOT : plotSize + 1;
                    b.setMessage(plotSizeLabel());
                })
                .bounds(rightX + 2 * (thirdW + ROW_GAP), controls2Y, thirdW, BUTTON_HEIGHT).build()));

        statusY = controls2Y + BUTTON_HEIGHT + ROW_GAP;
        int logTopY = statusY + 22;
        int logH = Math.max(30, this.height - MARGIN - logTopY);
        simLogBox = new MultiLineEditBox(this.font, rightX, logTopY, rightW, logH,
                Component.translatable("gui.micradrone.ide_screen.sim_log_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.sim_log"));
        addRenderableWidget(simLogBox);
        simOnlyWidgets.add(simLogBox);
        logRenderedForFrame = -1;

        applyViewVisibility();
        if (this.minecraft != null && this.minecraft.level != null) {
            livePlot.rescan(this.minecraft.level);
            if (mode == ViewMode.LIVE_3D) {
                cameraController.update(this.minecraft, livePlot.bounds()); // no first-frame flash from the player's own view
            }
        }

        if (!sourceRequested) {
            sourceRequested = true;
            PacketDistributor.sendToServer(new RequestScriptSourcePayload(pos, scriptName));
        }
    }

    private void setMode(ViewMode newMode) {
        if (mode == ViewMode.LIVE_3D && newMode != ViewMode.LIVE_3D && this.minecraft != null) {
            cameraController.restore(this.minecraft);
        }
        mode = newMode;
        applyViewVisibility();
    }

    private void applyViewVisibility() {
        for (AbstractWidget widget : simOnlyWidgets) {
            widget.visible = mode == ViewMode.SIM;
        }
        viewToggleButton.setMessage(viewToggleLabel());
    }

    private Component viewToggleLabel() {
        return Component.translatable(switch (mode) {
            case LIVE_3D -> "gui.micradrone.ide_screen.view_3d";
            case LIVE_2D -> "gui.micradrone.ide_screen.view_2d";
            case SIM -> "gui.micradrone.ide_screen.view_sim";
        });
    }

    /**
     * In 3D mode the vanilla blur + dark overlay would hide the whole point of the view - skip
     * them entirely so the world (rendered from the overhead camera) shows through crisply.
     */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (mode != ViewMode.LIVE_3D) {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    /** Called when this screen is closed or replaced - the viewpoint must always come back. */
    @Override
    public void removed() {
        if (this.minecraft != null) {
            cameraController.restore(this.minecraft);
        }
        super.removed();
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

    private void simulate() {
        trace = ScriptSimulator.run(editorText, plotSize);
        frameIndex = 0;
        playing = trace.error() == null && trace.frames().size() > 1;
        tickCounter = 0;
        logRenderedForFrame = -1;
        updatePlayPauseLabel();
    }

    private void togglePlay() {
        if (trace == null) {
            simulate();
            return;
        }
        if (!playing && frameIndex >= trace.frames().size() - 1) {
            frameIndex = 0; // replay from the top when play is hit at the end
        }
        playing = !playing;
        updatePlayPauseLabel();
    }

    private void step() {
        playing = false;
        updatePlayPauseLabel();
        if (trace != null && frameIndex < trace.frames().size() - 1) {
            frameIndex++;
        }
    }

    private void updatePlayPauseLabel() {
        playPauseButton.setMessage(Component.translatable(
                playing ? "gui.micradrone.ide_screen.pause" : "gui.micradrone.ide_screen.play"));
    }

    private Component speedLabel() {
        return Component.translatable("gui.micradrone.ide_screen.speed", SPEED_LABELS[speedIndex]);
    }

    private Component plotSizeLabel() {
        return Component.translatable("gui.micradrone.ide_screen.plot_size", plotSize, plotSize);
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        if (mode != ViewMode.SIM) {
            if (tickCounter % LIVE_RESCAN_INTERVAL_TICKS == 0 && this.minecraft != null && this.minecraft.level != null) {
                livePlot.rescan(this.minecraft.level);
            }
            if (mode == ViewMode.LIVE_3D && this.minecraft != null) {
                // Every tick: follows corner-marker changes, FOV changes, and window resizes.
                cameraController.update(this.minecraft, livePlot.bounds());
            }
            return;
        }
        if (!playing || trace == null) {
            return;
        }
        if (tickCounter % TICKS_PER_FRAME[speedIndex] == 0) {
            if (frameIndex < trace.frames().size() - 1) {
                frameIndex++;
            } else {
                playing = false;
                updatePlayPauseLabel();
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("gui.micradrone.ide_screen.heading", scriptName),
                this.width / 2, MARGIN, 0xFFFFFF);
        switch (mode) {
            case LIVE_3D -> {
                // The right half IS the world, seen from the overhead camera - draw nothing over it.
            }
            case LIVE_2D -> {
                renderLiveGrid(guiGraphics);
                renderLiveStatus(guiGraphics);
            }
            case SIM -> {
                renderSimGrid(guiGraphics);
                renderSimStatus(guiGraphics);
                refreshSimLogIfNeeded();
            }
        }
    }

    // ---- live view (the real plot) ----

    private void renderLiveGrid(GuiGraphics guiGraphics) {
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        guiGraphics.fill(gridX - 1, gridY - 1, gridX + gridPanelSize + 1, gridY + gridPanelSize + 1, 0xFF202020);

        int size = livePlot.bounds().worldSize();
        if (size <= 0) {
            return; // a touching-diagonal marker leaves zero farmable cells - nothing to draw
        }
        int[] colors = livePlot.sampleColors(this.minecraft.level);
        int cell = gridPanelSize / size;
        for (int gy = 0; gy < size; gy++) {
            for (int gx = 0; gx < size; gx++) {
                int x0 = gridX + gx * cell;
                int y0 = gridY + gy * cell;
                guiGraphics.fill(x0 + 1, y0 + 1, x0 + cell - 1, y0 + cell - 1, colors[gy * size + gx]);
            }
        }

        DroneEntity drone = livePlot.findDrone(this.minecraft.level);
        if (drone != null) {
            // The marker follows the drone's actual position (fractional cells), not a cell snap.
            double fx = livePlot.gridXOf(drone.getX());
            double fy = livePlot.gridYOf(drone.getZ());
            int cx = gridX + (int) Math.round((fx + 0.5) * cell);
            int cy = gridY + (int) Math.round((fy + 0.5) * cell);
            int half = Math.max(2, cell / 4);
            guiGraphics.fill(cx - half, cy - half, cx + half, cy + half, 0xFFFFFFFF);
        }
    }

    private void renderLiveStatus(GuiGraphics guiGraphics) {
        int rightX = this.width / 2 + ROW_GAP;
        int size = livePlot.bounds().worldSize();
        guiGraphics.drawString(this.font,
                Component.translatable("gui.micradrone.ide_screen.status_live", size, size),
                rightX, statusY, 0xFFFFFF);
        if (!livePlot.bounds().markerFound()) {
            guiGraphics.drawString(this.font,
                    Component.translatable("gui.micradrone.ide_screen.status_live_default"),
                    rightX, statusY + 11, 0xA0A0A0);
        }
    }

    // ---- sim view (dry run) ----

    private void renderSimGrid(GuiGraphics guiGraphics) {
        guiGraphics.fill(gridX - 1, gridY - 1, gridX + gridPanelSize + 1, gridY + gridPanelSize + 1, 0xFF202020);

        int size = trace != null ? trace.worldSize() : plotSize;
        SimFrame frame = currentFrame();
        int cell = gridPanelSize / size;
        for (int gy = 0; gy < size; gy++) {
            for (int gx = 0; gx < size; gx++) {
                byte state = frame != null ? frame.cells()[gy * size + gx] : SimFrame.CELL_UNTILLED;
                int x0 = gridX + gx * cell;
                int y0 = gridY + gy * cell;
                guiGraphics.fill(x0 + 1, y0 + 1, x0 + cell - 1, y0 + cell - 1, simCellColor(state));
            }
        }

        if (frame != null) {
            // Drone marker: a white square filling the middle of its cell.
            int x0 = gridX + frame.droneX() * cell;
            int y0 = gridY + frame.droneY() * cell;
            int inset = Math.max(2, cell / 4);
            guiGraphics.fill(x0 + inset, y0 + inset, x0 + cell - inset, y0 + cell - inset, 0xFFFFFFFF);
        }
    }

    /** The sim only knows growing/mature, so "growing" renders as the halfway gradient color. */
    private static int simCellColor(byte state) {
        return switch (state) {
            case SimFrame.CELL_TILLED -> PlotColorRules.farmland(true);
            case SimFrame.CELL_WHEAT_GROWING -> PlotColorRules.wheat(0.5f);
            case SimFrame.CELL_WHEAT_MATURE -> PlotColorRules.wheat(1.0f);
            case SimFrame.CELL_CARROT_GROWING -> PlotColorRules.carrot(0.5f);
            case SimFrame.CELL_CARROT_MATURE -> PlotColorRules.carrot(1.0f);
            case SimFrame.CELL_PUMPKIN_GROWING -> PlotColorRules.pumpkinStem(0.5f);
            case SimFrame.CELL_PUMPKIN_MATURE -> PlotColorRules.PUMPKIN_FRUIT;
            case SimFrame.CELL_PUMPKIN_ROTTEN -> PlotColorRules.ROTTEN_PUMPKIN;
            default -> PlotColorRules.UNTILLED;
        };
    }

    private void renderSimStatus(GuiGraphics guiGraphics) {
        int rightX = this.width / 2 + ROW_GAP;
        if (trace == null) {
            guiGraphics.drawString(this.font,
                    Component.translatable("gui.micradrone.ide_screen.status_idle"), rightX, statusY, 0xA0A0A0);
            return;
        }
        if (trace.error() != null) {
            guiGraphics.drawString(this.font, Component.literal(trace.error()), rightX, statusY, 0xFF5555);
            return;
        }
        SimFrame frame = currentFrame();
        String actionText = frame == null ? "" : frame.action() + (frame.succeeded() ? "" : " -> False");
        guiGraphics.drawString(this.font,
                Component.translatable("gui.micradrone.ide_screen.status_frame",
                        frameIndex, trace.frames().size() - 1, actionText),
                rightX, statusY, 0xFFFFFF);
        if (trace.truncated()) {
            guiGraphics.drawString(this.font,
                    Component.translatable("gui.micradrone.ide_screen.status_truncated"), rightX, statusY + 11, 0xFFAA00);
        }
    }

    /** Rebuilds the sim log text only when the visible frame changed - print lines appear in playback sync. */
    private void refreshSimLogIfNeeded() {
        if (trace == null || logRenderedForFrame == frameIndex) {
            return;
        }
        logRenderedForFrame = frameIndex;
        StringBuilder text = new StringBuilder();
        for (SimLogLine line : trace.logLines()) {
            if (line.frameIndex() <= frameIndex) {
                text.append(line.text()).append('\n');
            }
        }
        simLogBox.setValue(text.toString());
    }

    private SimFrame currentFrame() {
        if (trace == null || trace.frames().isEmpty()) {
            return null;
        }
        return trace.frames().get(Math.min(frameIndex, trace.frames().size() - 1));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
