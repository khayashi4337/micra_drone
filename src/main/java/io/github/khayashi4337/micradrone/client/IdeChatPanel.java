package io.github.khayashi4337.micradrone.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.ai.BuildChatFlow;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.SchemaGenerator;
import io.github.khayashi4337.micradrone.build.parts.SchemaLimits;
import io.github.khayashi4337.micradrone.chat.ChatCompactor;
import io.github.khayashi4337.micradrone.chat.ChatContextBuilder;
import io.github.khayashi4337.micradrone.chat.ChatHistoryStore;
import io.github.khayashi4337.micradrone.chat.ChatMessage;
import io.github.khayashi4337.micradrone.chat.ChatSession;
import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge;
import io.github.khayashi4337.micradrone.chat.ClaudeStageRunner;
import io.github.khayashi4337.micradrone.chat.CodeBlockParser;
import io.github.khayashi4337.micradrone.chat.ControllerKey;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import io.github.khayashi4337.micradrone.construction.ClientBuildState;
import io.github.khayashi4337.micradrone.construction.net.BuildApprovePayload;
import io.github.khayashi4337.micradrone.construction.net.BuildCancelPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildPlanPayload;
import io.github.khayashi4337.micradrone.construction.net.BuildRollbackPayload;
import io.github.khayashi4337.micradrone.drone.CommandsHelpDoc;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
import io.github.khayashi4337.micradrone.drone.UnlockShop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The IDE's right-half AI chat tab (Wave 5), split out of {@link IdeScreen} once that class passed
 * 1300 lines: the transcript, per-reply Insert buttons, the input row, the compact row, the
 * claude -p round trip behind them, and the per-controller history on disk. IdeScreen still owns
 * layout, tab switching and the widget list; this class reaches those through {@link Host} rather
 * than being a Screen itself. One ClaudeCliBridge per screen instance is enough - there's no
 * per-controller CLI process, only a per-controller ChatSession.
 *
 * <p>Every call runs claude -p in ClaudeCliBridge's locked-down safe mode - there used to be a
 * player-facing "Danger" toggle for full local-terminal power, removed after CurseForge rejected
 * the file over it ("contains a feature... disabling permissions... unsafe and a security risk").
 */
final class IdeChatPanel {
    /** What the panel needs from the screen that hosts it. */
    interface Host {
        Minecraft minecraft();

        Font font();

        BlockPos controllerPos();

        /** The client-side plot scan the 3D camera also uses, so the AI's world-to-grid mapping matches the server's. */
        CornerMarkerScan.PlotBounds plotBounds();

        String editorText();

        /**
         * Insert: shows {@code proposed} as a reviewable diff against the current script (green
         * added / red removed lines in the editor) rather than overwriting it - see LineDiff.
         */
        void beginReview(String proposed);

        boolean isReviewing();

        void acceptReview();

        void rejectReview();

        List<String> logLines();

        /** The controller's harvest points and unlocked crops/features, from the last DroneLogPayload. */
        Map<String, Long> pointsByCrop();

        Set<String> unlockedCrops();

        <T extends GuiEventListener & Renderable & NarratableEntry> T addWidget(T widget);

        void rebuildWidgets();

        /** True once the screen has been removed - a late CLI reply must not rebuild (and re-aim the camera of) it. */
        boolean isClosed();
    }

    private static final int INSERT_ROW_HEIGHT = 16;
    private static final int INPUT_ROW_HEIGHT = 20;
    private static final int TOGGLE_ROW_HEIGHT = 20;
    private static final int INPUT_MAX_CHARS = 2000;
    private static final int SEND_BUTTON_WIDTH = 60;
    private static final int REVIEW_BUTTON_WIDTH = 90;
    private static final int ROW_GAP = IdeScreen.ROW_GAP;
    private static final String CLAUDE_EXECUTABLE = "claude";
    // "AI: thinking..." status row under the transcript while a reply is in flight - the transcript
    // itself is a vanilla MultiLineEditBox, which can't color part of its text, hence a separate row.
    private static final int STATUS_ROW_HEIGHT = 12;
    /** Claude's own accent orange, so the "thinking" line reads as the AI's rather than the mod's cyan. */
    private static final int THINKING_COLOR = 0xFFD97757;
    private static final int THINKING_DOT_INTERVAL_TICKS = 5;   // 0.25s per step at 20 tps
    private static final int THINKING_MAX_DOTS = 3;
    private static final int CANCEL_HINT_COLOR = 0xFF9A9A9A;
    private static final int CLI_MISSING_COLOR = 0xFFFF6060;

    // ---- "けんちく" build mode (P4 task M3) ---------------------------------------------------
    // Every build-mode decision lives in the pure-Java BuildChatFlow; this panel only executes the
    // returned actions (packets, translated lines, buttons). The AI call goes through a separate
    // ClaudeStageRunner so a build round trip never touches the script chat's ClaudeCliBridge, and
    // a build reply never triggers the script chat's automatic code review.
    private static final String BUILD_LINE_PREFIX = "けんちく: ";
    /** <gameDir>/micradrone/build_consent.txt - the exact text "yes" marks stored consent. */
    private static final String BUILD_CONSENT_FILE = "build_consent.txt";
    private static final String BUILD_CONSENT_VALUE = "yes";
    private static final String SAMPLE_PLAN_RESOURCE = "/data/micradrone/build_samples/hut.json";
    private static final String PALETTE_RESOURCE = "/data/micradrone/tags/block/palette_allowed.json";

    private final Host host;
    private final ClaudeCliBridge claudeCliBridge = new ClaudeCliBridge(CLAUDE_EXECUTABLE);
    private boolean open = false;
    private ChatSession chatSession;
    private boolean sendInFlight = false;
    private List<CodeBlockParser.CodeBlock> lastAssistantCodeBlocks = List.of();
    private MultiLineEditBox logBox;
    private EditBox inputBox;
    private Button sendButton;
    private Button compactButton;
    /** What the player has typed but not sent - restored across the rebuild a landing reply triggers. */
    private String inputDraft = "";
    private int statusRowX;
    private int statusRowY;
    private int statusRowWidth;
    /** The question whose reply is in flight - removed from the transcript again if Esc cancels it. */
    private String pendingQuestion;
    /** Client ticks since the panel was built; drives the thinking-dots animation. */
    private int animationTicks = 0;
    /** Result of the one-time {@code claude --version} probe: null = not probed yet / still running. */
    private Boolean cliAvailable;
    private String cliVersion = "";
    private boolean cliProbeStarted;

    // build mode's own state (the flow itself is created lazily - the prompt parts read packaged
    // resources, which only pays off when the child actually turns けんちく on)
    private boolean buildMode;
    private BuildChatFlow buildFlow;
    private ClaudeStageRunner buildStageRunner;
    private BuildChatFlow.PromptParts buildPromptParts;
    private List<BuildChatFlow.ButtonKind> buildButtons = List.of();
    /** Child-facing build lines ("けんちく: …") - kept apart from the ChatMessage transcript on purpose. */
    private final List<String> buildTranscript = new ArrayList<>();
    /** The last offer/progress documents handed to the flow; identical JSON is never fed twice. */
    private String lastOfferJson;
    private String lastProgressJson;

    IdeChatPanel(Host host) {
        this.host = host;
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open) {
        this.open = open;
    }

    Component tabButtonLabel() {
        return Component.translatable(open
                ? "gui.micradrone.ide_screen.chat_close" : "gui.micradrone.ide_screen.chat_open");
    }

    /**
     * Builds the panel's widgets into the host: transcript, the Accept/Reject row (only while a
     * reply's code is under review in the editor), the message input row, and the compact row.
     */
    void initWidgets(int rightX, int rightW, int topY, int bottomY) {
        ensureSessionLoaded();
        probeCliOnce();

        int toggleRowY = bottomY - TOGGLE_ROW_HEIGHT;
        int inputRowY = toggleRowY - ROW_GAP - INPUT_ROW_HEIGHT;
        int insertRowY = inputRowY - ROW_GAP - INSERT_ROW_HEIGHT;
        statusRowY = insertRowY - ROW_GAP - STATUS_ROW_HEIGHT;
        statusRowX = rightX;
        statusRowWidth = rightW;
        int logHeight = statusRowY - ROW_GAP - topY;

        logBox = new MultiLineEditBox(host.font(), rightX, topY, rightW, logHeight,
                Component.translatable("gui.micradrone.ide_screen.chat_log_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.chat_log"));
        logBox.setValue(displayText());
        host.addWidget(logBox);

        if (buildMode && !buildButtons.isEmpty()) {
            // Build mode's buttons (consent pair / つくる+やめる) reuse the insert row: it is free
            // because a build reply never opens the script's code review.
            int width = (rightW - (buildButtons.size() - 1) * ROW_GAP) / buildButtons.size();
            for (int i = 0; i < buildButtons.size(); i++) {
                BuildChatFlow.ButtonKind kind = buildButtons.get(i);
                host.addWidget(Button.builder(Component.translatable(buildButtonLabelKey(kind)),
                                b -> pressBuildButton(kind))
                        .bounds(rightX + i * (width + ROW_GAP), insertRowY, width, INSERT_ROW_HEIGHT)
                        .build());
            }
        } else if (host.isReviewing()) {
            // The review verdict row (Cursor's Accept / Reject pair). A reply's code lands in the
            // editor as a pending diff on its own (see onChatResult), so there is no Insert button:
            // Accept applies whatever blocks haven't been rejected individually in the editor,
            // Reject drops the whole proposal.
            host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_accept"),
                            b -> {
                                host.acceptReview();
                                host.rebuildWidgets();
                            })
                    .bounds(rightX, insertRowY, REVIEW_BUTTON_WIDTH, INSERT_ROW_HEIGHT).build());
            host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_reject"),
                            b -> {
                                host.rejectReview();
                                host.rebuildWidgets();
                            })
                    .bounds(rightX + REVIEW_BUTTON_WIDTH + ROW_GAP, insertRowY, REVIEW_BUTTON_WIDTH, INSERT_ROW_HEIGHT).build());
        }

        inputBox = new EditBox(host.font(), rightX, inputRowY, rightW - SEND_BUTTON_WIDTH - ROW_GAP,
                INPUT_ROW_HEIGHT, Component.translatable("gui.micradrone.ide_screen.chat_input"));
        inputBox.setMaxLength(INPUT_MAX_CHARS);
        inputBox.setValue(inputDraft); // a reply landing mid-typing rebuilds this panel; keep the draft
        inputBox.setResponder(text -> inputDraft = text);
        inputBox.setEditable(!sendInFlight); // while a reply is in flight the box shows what was sent
        host.addWidget(inputBox);
        sendButton = host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_send"),
                        b -> sendMessage())
                .bounds(rightX + rightW - SEND_BUTTON_WIDTH, inputRowY, SEND_BUTTON_WIDTH, INPUT_ROW_HEIGHT)
                .build());
        sendButton.active = !sendInFlight;

        // The bottom row splits in half: script-chat's Compact keeps the left side, the
        // けんちく-mode toggle takes the right (16px rows, same ROW_GAP as everything else).
        int halfWidth = (rightW - ROW_GAP) / 2;
        compactButton = host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_compact"),
                        b -> compact())
                .bounds(rightX, toggleRowY, halfWidth, TOGGLE_ROW_HEIGHT).build());
        compactButton.active = !sendInFlight;
        host.addWidget(Button.builder(buildModeLabel(), b -> setBuildMode(!buildMode))
                .bounds(rightX + halfWidth + ROW_GAP, toggleRowY, rightW - halfWidth - ROW_GAP,
                        TOGGLE_ROW_HEIGHT).build());
    }

    private Component buildModeLabel() {
        return Component.translatable(buildMode
                ? "gui.micradrone.ide_screen.build_mode_on" : "gui.micradrone.ide_screen.build_mode_off");
    }

    private static String buildButtonLabelKey(BuildChatFlow.ButtonKind kind) {
        return switch (kind) {
            case CONSENT_YES -> "gui.micradrone.ide_screen.build_consent_yes";
            case CONSENT_NO -> "gui.micradrone.ide_screen.build_consent_no";
            case BUILD -> "gui.micradrone.ide_screen.build_build";
            case CANCEL -> "gui.micradrone.ide_screen.build_cancel";
            case UNDO -> "gui.micradrone.ide_screen.build_undo";
            case UNDO_YES -> "gui.micradrone.ide_screen.build_undo_yes";
            case UNDO_NO -> "gui.micradrone.ide_screen.build_undo_no";
        };
    }

    /**
     * Once per client tick (from IdeScreen#tick): advances the thinking-dots animation and hands
     * the flow each NEW offer/progress document the server pushed into ClientBuildState - the
     * holder keeps only the newest doc, so an unchanged string must not re-drive the state machine.
     */
    void tick() {
        animationTicks++;
        if (!buildMode || buildFlow == null) {
            return;
        }
        String offer = ClientBuildState.offerJson();
        if (offer != null && !offer.equals(lastOfferJson)) {
            lastOfferJson = offer;
            runActions(buildFlow.offer(offer));
        }
        String progress = ClientBuildState.progressJson();
        if (progress != null && !progress.equals(lastProgressJson)) {
            lastProgressJson = progress;
            runActions(buildFlow.progress(progress));
        }
    }

    /**
     * The AI tab is a thin client for the player's OWN Claude Code install - nothing ships with
     * the mod. So the first time the tab opens, {@code claude --version} is run once and the status
     * row says either which version answered or, in red, that nothing did and how to install it -
     * before the player types a question that would only come back as an error.
     */
    private void probeCliOnce() {
        if (cliProbeStarted) {
            return;
        }
        cliProbeStarted = true;
        claudeCliBridge.probeVersion().thenAccept(version -> Minecraft.getInstance().execute(() -> {
            cliAvailable = version.isPresent();
            cliVersion = version.orElse("");
        }));
    }

    /**
     * Draws the "AI: thinking..." status row while a reply (or compact) is in flight - the dots
     * grow one at a time and wrap, the usual "responding" cue. Drawn by IdeScreen after its widgets
     * so it sits on top of the panel fill.
     */
    void render(GuiGraphics guiGraphics) {
        if (!open) {
            return;
        }
        if (!sendInFlight) {
            renderCliStatus(guiGraphics);
            return;
        }
        int dots = (animationTicks / THINKING_DOT_INTERVAL_TICKS) % (THINKING_MAX_DOTS + 1);
        String text = Component.translatable("gui.micradrone.ide_screen.chat_thinking").getString() + ".".repeat(dots);
        int textY = statusRowY + (STATUS_ROW_HEIGHT - host.font().lineHeight) / 2;
        guiGraphics.drawString(host.font(), text, statusRowX, textY, THINKING_COLOR);
        String hint = Component.translatable("gui.micradrone.ide_screen.chat_cancel_hint").getString();
        guiGraphics.drawString(host.font(), hint, statusRowX + statusRowWidth - host.font().width(hint), textY, CANCEL_HINT_COLOR);
    }

    /** Idle status row: the probed CLI version in grey, or the install hint in red if there is none. */
    private void renderCliStatus(GuiGraphics guiGraphics) {
        if (cliAvailable == null) {
            return;
        }
        int textY = statusRowY + (STATUS_ROW_HEIGHT - host.font().lineHeight) / 2;
        if (cliAvailable) {
            guiGraphics.drawString(host.font(),
                    Component.translatable("gui.micradrone.ide_screen.chat_cli_ok", cliVersion).getString(),
                    statusRowX, textY, CANCEL_HINT_COLOR);
        } else {
            guiGraphics.drawString(host.font(), ClaudeCliBridge.CLI_NOT_FOUND_MESSAGE, statusRowX, textY, CLI_MISSING_COLOR);
        }
    }

    /** Send and Compact both fire a CLI round trip, so both wait for the one in flight. */
    private void setRoundTripInFlight(boolean inFlight) {
        sendInFlight = inFlight;
        if (sendButton != null) {
            sendButton.active = !inFlight;
        }
        if (compactButton != null) {
            compactButton.active = !inFlight;
        }
    }

    /**
     * Called right after the tab is opened (widgets built): consumes RegionSelectionHolder's pending
     * selection - the WorldEdit-style pointer item's corners, if any were picked since the last time
     * chat was opened - and drops the coordinate text straight into the input box.
     */
    void consumePendingRegionIntoInput() {
        RegionSelectionHolder.PENDING.consumeAsText().ifPresent(region -> {
            if (inputBox != null) {
                inputBox.setValue(region + " ");
            }
        });
    }

    /** Esc while a reply is in flight cancels it; Enter in the input box sends; everything else is left to the host. */
    boolean handleKeyPressed(int keyCode) {
        if (sendInFlight && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            cancelRoundTrip();
            return true;
        }
        if (inputBox != null && inputBox.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            sendMessage();
            return true;
        }
        return false;
    }

    /** Stops the CLI round trip in flight; the result then arrives as cancelled (see onChatResult). */
    void cancelRoundTrip() {
        if (sendInFlight) {
            claudeCliBridge.cancel();
            if (buildStageRunner != null) {
                buildStageRunner.cancel();
            }
        }
    }

    /**
     * Called from {@link IdeScreen#removed} when the IDE screen closes: cancels any in-flight CLI
     * round trip and shuts down the bridges' background executors so repeated open/close cycles
     * don't accumulate idle threads.
     */
    void close() {
        cancelRoundTrip();
        claudeCliBridge.close();
        if (buildStageRunner != null) {
            buildStageRunner.close();
        }
    }

    /** Loads this controller's chat history on first use (resume across screen reopens - see ChatHistoryStore). */
    private void ensureSessionLoaded() {
        Minecraft minecraft = host.minecraft();
        if (chatSession != null || minecraft == null || minecraft.level == null) {
            return;
        }
        BlockPos pos = host.controllerPos();
        ControllerKey key = new ControllerKey(minecraft.level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ());
        chatSession = ChatHistoryStore.load(historyDir(), key);
    }

    /**
     * {@code micradrone/chat/<world>/} under the game directory (client-local, never a world save).
     * The per-world folder is what keeps a controller at, say, (10,64,10) in one save from sharing
     * a transcript with one at the same spot in another save or on a server - ControllerKey only
     * covers dimension + position. Vanilla's own precedent for naming "the world I'm in" is
     * Minecraft#archiveProfilingReport (level name locally, ServerData remotely); this uses the save
     * folder and the server address rather than the display names, since those are the unique ones.
     */
    private Path historyDir() {
        return host.minecraft().gameDirectory.toPath().resolve("micradrone").resolve("chat")
                .resolve(ChatHistoryStore.worldDirectoryName(currentWorldId()));
    }

    private String currentWorldId() {
        Minecraft minecraft = host.minecraft();
        IntegratedServer local = minecraft.getSingleplayerServer();
        if (local != null) {
            // LevelResource.ROOT is "." so the raw path ends in "/."; normalize() before taking the
            // last segment, or every save would come out as ".".
            return local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        }
        ServerData remote = minecraft.getCurrentServer();
        return remote != null ? remote.ip : "";
    }

    private void saveSession() {
        if (chatSession == null || host.minecraft() == null) {
            return;
        }
        try {
            ChatHistoryStore.save(historyDir(), chatSession);
        } catch (IOException ignoredBestEffort) {
            // Losing one turn's persistence isn't worth interrupting the chat over - it'll save
            // again on the next successful turn.
        }
    }

    String transcriptText() {
        if (chatSession == null) {
            return "";
        }
        return chatSession.messages().stream()
                .map(m -> switch (m.role()) {
                    case ChatMessage.ROLE_USER -> "You: " + m.text();
                    case ChatMessage.ROLE_ASSISTANT -> "AI: " + m.text();
                    default -> "[summary] " + m.text();
                })
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * What the transcript box shows: the script-chat transcript plus the build mode's own
     * "けんちく:" lines appended after it. Build lines are deliberately NOT ChatMessages - the
     * ChatHistoryStore format has no role for them (brief M3-UI), so they live in their own list.
     */
    private String displayText() {
        String base = transcriptText();
        if (buildTranscript.isEmpty()) {
            return base;
        }
        String buildLines = String.join("\n", buildTranscript);
        return base.isEmpty() ? buildLines : base + "\n\n" + buildLines;
    }

    private void refreshTranscript() {
        if (logBox != null) {
            logBox.setValue(displayText());
        }
    }

    /**
     * Build mode's send: the input is the child's Japanese request. {@link BuildChatFlow#request}
     * decides what happens next (consent question first, or straight to the AI); this method only
     * clears the box once the request was accepted - a busy line leaves the text in place.
     */
    private void sendBuildMessage(String request) {
        ensureBuildFlow();
        List<BuildChatFlow.Action> actions = buildFlow.request(request);
        if (buildFlow.state() == BuildChatFlow.State.ASKING_AI
                || buildFlow.state() == BuildChatFlow.State.NEED_CONSENT) {
            inputBox.setValue(""); // accepted - an empty box reads as "the request went out"
        }
        runActions(actions);
    }

    /**
     * Executes one batch of flow actions: {@code AskAi} goes through the stage runner (result comes
     * back as {@code aiReply} on the render thread, like {@link #onChatResult}), {@code Send*} map
     * to the M2 payloads, {@code Say} appends a translated けんちく line, {@code ShowButtons}
     * re-fills the insert row.
     */
    private void runActions(List<BuildChatFlow.Action> actions) {
        for (BuildChatFlow.Action action : actions) {
            if (action instanceof BuildChatFlow.AskAi ask) {
                setRoundTripInFlight(true); // the same Esc-to-cancel and "thinking" row as a send
                buildStageRunner().run(ask.prompt())
                        .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                            setRoundTripInFlight(false);
                            if (buildFlow != null) {
                                runActions(buildFlow.aiReply(result));
                            }
                        }));
            } else if (action instanceof BuildChatFlow.SendPlan sendPlan) {
                // here=true relocates the plan's site under the player's feet (see
                // BuildNetwork.handlePlan) - the offer line told the child it builds "in front".
                PacketDistributor.sendToServer(new BuildPlanPayload(
                        UUID.randomUUID().toString(), 0, 1, sendPlan.json(), true));
            } else if (action instanceof BuildChatFlow.SendApprove sendApprove) {
                PacketDistributor.sendToServer(new BuildApprovePayload(sendApprove.hash(),
                        sendApprove.confirmTerraform(), sendApprove.confirmDestructive()));
            } else if (action instanceof BuildChatFlow.SendCancel sendCancel) {
                PacketDistributor.sendToServer(new BuildCancelPayload(sendCancel.jobId()));
            } else if (action instanceof BuildChatFlow.SendRollback sendRollback) {
                PacketDistributor.sendToServer(new BuildRollbackPayload(sendRollback.claimId()));
            } else if (action instanceof BuildChatFlow.Say say) {
                buildTranscript.add(BUILD_LINE_PREFIX
                        + Component.translatable(say.key(), say.args().toArray()).getString());
                refreshTranscript();
            } else if (action instanceof BuildChatFlow.ShowButtons showButtons) {
                buildButtons = showButtons.buttons();
                refreshAfterTurn();
            }
        }
    }

    /** One build-mode button press: consent writes the consent file, the rest just feed the flow. */
    private void pressBuildButton(BuildChatFlow.ButtonKind kind) {
        if (buildFlow == null) {
            return;
        }
        switch (kind) {
            case CONSENT_YES -> {
                saveBuildConsent();
                runActions(buildFlow.consent(true));
            }
            case CONSENT_NO -> runActions(buildFlow.consent(false));
            case BUILD -> runActions(buildFlow.approve());
            case CANCEL -> runActions(buildFlow.cancel());
            case UNDO -> runActions(buildFlow.undo());
            case UNDO_YES -> runActions(buildFlow.undoConfirmed());
            case UNDO_NO -> runActions(buildFlow.undoCancelled());
        }
    }

    private void ensureBuildFlow() {
        if (buildFlow == null) {
            buildFlow = new BuildChatFlow(loadBuildConsent(), promptParts());
        }
    }

    private ClaudeStageRunner buildStageRunner() {
        if (buildStageRunner == null) {
            buildStageRunner = new ClaudeStageRunner(CLAUDE_EXECUTABLE);
        }
        return buildStageRunner;
    }

    /**
     * The three texts {@link BuildPromptBuilder} needs: the packaged sample plan, the generated
     * parts schema (the same {@code forLimit} call the M1 schema tests use), and the palette tag's
     * values list. Resource failures degrade to empty text rather than killing the toggle.
     */
    private BuildChatFlow.PromptParts promptParts() {
        if (buildPromptParts == null) {
            buildPromptParts = new BuildChatFlow.PromptParts(
                    readBuildResource(SAMPLE_PLAN_RESOURCE), partsCatalogText(), paletteText());
        }
        return buildPromptParts;
    }

    private static String partsCatalogText() {
        return SchemaGenerator.forLimit(BuildingParts.registry(), null,
                SchemaLimits.CLAUDE_EXE_MAX_SCHEMA_CHARS).json();
    }

    private static String paletteText() {
        try {
            Object tree = MiniJson.parse(readBuildResource(PALETTE_RESOURCE));
            if (tree instanceof Map<?, ?> map && map.get("values") instanceof List<?> values) {
                return values.stream().map(String::valueOf).collect(Collectors.joining(", "));
            }
        } catch (RuntimeException unreadable) {
            // fall through: the empty block list still produces a well-formed prompt
        }
        return "";
    }

    private static String readBuildResource(String path) {
        try (InputStream in = IdeChatPanel.class.getResourceAsStream(path)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** micradrone/build_consent.txt under the game directory (client-local, like the chat history). */
    private Path consentFile() {
        return host.minecraft().gameDirectory.toPath()
                .resolve("micradrone").resolve(BUILD_CONSENT_FILE);
    }

    private boolean loadBuildConsent() {
        try {
            // An unreadable consent file is "not consented" - never silently yes.
            return Files.readString(consentFile(), StandardCharsets.UTF_8).trim()
                    .equals(BUILD_CONSENT_VALUE);
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private void saveBuildConsent() {
        try {
            Files.createDirectories(consentFile().getParent());
            Files.writeString(consentFile(), BUILD_CONSENT_VALUE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unwritable) {
            // Consent survives in memory only; the child is asked again on the next game start.
            MicraDrone.LOGGER.warn("MicraDrone: could not write {}", consentFile());
        }
    }

    /**
     * Sends the input box's text as one chat turn: builds the prompt (script + command reference +
     * plot mapping + last error + any pending region reference), dispatches it on ClaudeCliBridge's
     * background executor, and disables the Send button until the response (or failure) lands.
     */
    private void sendMessage() {
        Minecraft minecraft = host.minecraft();
        if (inputBox == null || minecraft == null || minecraft.level == null) {
            return;
        }
        String question = inputBox.getValue().trim();
        if (question.isEmpty()) {
            return;
        }
        if (buildMode) {
            // sendInFlight does not short-circuit here: the flow itself answers a mid-round
            // request with the "busy" line, which reads better than a silently dead Send key.
            sendBuildMessage(question);
            return;
        }
        if (sendInFlight) {
            return;
        }
        ensureSessionLoaded();
        if (chatSession == null) {
            return;
        }
        Optional<String> mcpConfigPath = mcpConfigPathOrReportError();
        if (mcpConfigPath.isEmpty()) {
            return; // nothing consumed yet - the question and any pending region survive for a retry
        }

        Optional<String> pendingRegion = RegionSelectionHolder.PENDING.consumeAsText();
        Optional<String> priorSummary = chatSession.messages().stream()
                .filter(m -> ChatMessage.ROLE_SUMMARY.equals(m.role()))
                .map(ChatMessage::text)
                .findFirst();
        BlockPos pos = host.controllerPos();
        CornerMarkerScan.PlotBounds bounds = host.plotBounds();
        ChatContextBuilder.PlotInfo plot = new ChatContextBuilder.PlotInfo(
                pos.getX(), pos.getY(), pos.getZ(),
                bounds.worldSize(), bounds.dirX(), bounds.dirZ(), bounds.groundYOffset());
        // Both the command reference and the shop/unlock/collections reference (help scrolls 1/3
        // and 2/3): without the second, the AI happily planned carrot scripts on a plot that hadn't
        // bought carrots yet (real-machine report). The plot's own unlock/points state goes along
        // too, so it can say "carrot isn't unlocked here - Shop, 20 wheat" instead of guessing.
        String reference = CommandsHelpDoc.COMMANDS + "\n" + CommandsHelpDoc.ADVANCED;
        Map<String, Map<String, Long>> lockedOffers = new LinkedHashMap<>();
        for (UnlockShop.Unlock unlock : UnlockShop.CATALOG) {
            if (!host.unlockedCrops().contains(unlock.id())) {
                lockedOffers.put(unlock.id(), unlock.cost());
            }
        }
        ChatContextBuilder.PlotStatus status = new ChatContextBuilder.PlotStatus(
                host.unlockedCrops(), host.pointsByCrop(), lockedOffers);
        ChatContextBuilder.ChatContext context = new ChatContextBuilder.ChatContext(
                host.editorText(), reference, host.logLines(), pendingRegion, priorSummary,
                Optional.of(plot), Optional.of(status));
        String prompt = ChatContextBuilder.build(question, context);

        ClaudeCliBridge.ClaudeCliOptions options = chatSession.cliSessionId() == null
                ? ClaudeCliBridge.ClaudeCliOptions.freshSession(mcpConfigPath.get())
                : new ClaudeCliBridge.ClaudeCliOptions(chatSession.cliSessionId(), false, mcpConfigPath.get());

        setRoundTripInFlight(true);
        // The question stays visible (read-only) in the box until the reply lands, so a typo can
        // be spotted, Esc'd, and fixed in place instead of retyped from memory.
        pendingQuestion = question;
        inputBox.setEditable(false);
        chatSession.addMessage(new ChatMessage(ChatMessage.ROLE_USER, question, System.currentTimeMillis()));
        saveSession(); // the question outlives a game closed before the reply lands
        refreshTranscript();

        claudeCliBridge.send(prompt, options)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> onChatResult(result)));
    }

    /**
     * The --mcp-config path for this turn, starting the loopback tool server on first use. A
     * startup failure (loopback bind refused, unwritable game directory) is reported into the
     * transcript like any other failed turn instead of escaping a button handler - an uncaught
     * exception there takes the whole client down with a crash report.
     */
    private Optional<String> mcpConfigPathOrReportError() {
        try {
            return Optional.of(ChatToolServerLifecycle.ensureRunningAndConfigPath());
        } catch (RuntimeException toolServerDown) {
            Throwable cause = toolServerDown.getCause();
            String detail = cause != null ? toolServerDown.getMessage() + ": " + cause : toolServerDown.toString();
            reportError(detail);
            return Optional.empty();
        }
    }

    private void reportError(String detail) {
        chatSession.addMessage(new ChatMessage(ChatMessage.ROLE_ASSISTANT, "(error) " + detail, System.currentTimeMillis()));
        refreshTranscript();
    }

    /** Runs back on the render thread (see {@link #sendMessage}) - safe to touch widget state here. */
    private void onChatResult(ClaudeCliBridge.ClaudeCliResult result) {
        setRoundTripInFlight(false);
        if (chatSession == null) {
            return; // never null once a send has started; kept as a guard against future reordering
        }
        if (result.isCancelled()) {
            // Esc: take the question back out of the transcript; it is still sitting in the input
            // box (inputDraft) for the player to fix and resend.
            List<ChatMessage> messages = chatSession.messages();
            if (!messages.isEmpty() && ChatMessage.ROLE_USER.equals(messages.get(messages.size() - 1).role())
                    && messages.get(messages.size() - 1).text().equals(pendingQuestion)) {
                chatSession.replaceMessages(messages.subList(0, messages.size() - 1));
            }
            pendingQuestion = null;
            saveSession();
            refreshAfterTurn();
            return;
        }
        pendingQuestion = null;
        if (result.success()) {
            inputDraft = ""; // sent and answered - the box is free for the next question
            chatSession.addMessage(new ChatMessage(ChatMessage.ROLE_ASSISTANT, result.responseText(), System.currentTimeMillis()));
            if (result.sessionId() != null) {
                chatSession.setCliSessionId(result.sessionId());
            }
            lastAssistantCodeBlocks = CodeBlockParser.parse(result.responseText());
            // Cursor's flow: by the time you read the reply, its code is already sitting in the
            // editor as a pending diff - no Insert click, Reject puts the script back untouched.
            // Nothing is applied on top of a review still open from a previous turn.
            if (!lastAssistantCodeBlocks.isEmpty() && !host.isReviewing() && !host.isClosed()) {
                host.beginReview(lastAssistantCodeBlocks.get(0).code());
            }
        } else {
            // The question stays in the box (inputDraft) so a transient failure is one Enter away from a retry.
            reportError(result.errorMessage());
            lastAssistantCodeBlocks = List.of();
        }
        saveSession();
        refreshAfterTurn();
    }

    /**
     * Rebuilds the tab (Insert-button row, Send re-enabled) - unless the host screen has already
     * been closed. Screen#rebuildWidgets re-runs init(), and IdeScreen's init() re-aims the IDE
     * camera; on a closed screen that would steal the player's viewpoint with nothing left to hand
     * it back (the T-16 "close mid-send" case - found by reading Screen#rebuildWidgets, the guard
     * verified on a real machine via the devkit's cameraOnPlayer probe). The reply itself was still
     * saved by the caller, so reopening the IDE shows it.
     */
    private void refreshAfterTurn() {
        if (open && !host.isClosed()) {
            host.rebuildWidgets();
        }
    }

    /**
     * Manual compact (T-4b/ChatCompactor): asks claude -p to summarize the still-open session, then
     * replaces the local transcript with just that summary and drops the CLI session id so the next
     * send starts fresh - see ChatCompactor's javadoc for why starting fresh is what actually saves
     * cost, rather than summarizing in place.
     */
    void compact() {
        Minecraft minecraft = host.minecraft();
        if (sendInFlight || chatSession == null || chatSession.cliSessionId() == null
                || minecraft == null || minecraft.level == null) {
            return;
        }
        Optional<String> mcpConfigPath = mcpConfigPathOrReportError();
        if (mcpConfigPath.isEmpty()) {
            return;
        }
        ClaudeCliBridge.ClaudeCliOptions options = new ClaudeCliBridge.ClaudeCliOptions(
                chatSession.cliSessionId(), false, mcpConfigPath.get());

        setRoundTripInFlight(true);
        claudeCliBridge.send(ChatCompactor.COMPACT_REQUEST_PROMPT, options)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> onCompactResult(result)));
    }

    private void onCompactResult(ClaudeCliBridge.ClaudeCliResult result) {
        setRoundTripInFlight(false);
        if (chatSession == null) {
            return;
        }
        if (result.isCancelled()) {
            refreshAfterTurn(); // nothing changed; just re-enable the buttons
            return;
        }
        if (result.success()) {
            ChatCompactor.applySummary(chatSession, result.responseText());
            saveSession();
        } else {
            reportError(result.errorMessage()); // a silent no-op looked like a frozen button
        }
        refreshAfterTurn();
    }

    /** Opens the diff review for the reply's Nth code block - what onChatResult does for block 0 automatically. */
    void reviewCodeBlock(int index) {
        if (index >= 0 && index < lastAssistantCodeBlocks.size()) {
            host.beginReview(lastAssistantCodeBlocks.get(index).code());
        }
    }

    // ---- state readers / setters used by IdeScreen's devkit test hooks ------------------------

    /**
     * Turns けんちく (build) mode on/off - the bottom-row toggle and {@code setBuildModeForTesting}
     * share this. Switching on creates the flow lazily; switching off drops the visible buttons
     * (the flow's own state survives, so coming back picks the round up where it was).
     */
    void setBuildMode(boolean on) {
        if (buildMode == on) {
            return;
        }
        buildMode = on;
        if (on) {
            ensureBuildFlow();
        } else {
            buildButtons = List.of();
        }
        refreshAfterTurn(); // the insert row and the toggle's own label both follow buildMode
    }

    boolean isBuildMode() {
        return buildMode;
    }

    /** The flow's state name for the devkit's probe; IDLE until the flow exists. */
    String buildFlowState() {
        return buildFlow == null ? BuildChatFlow.State.IDLE.name() : buildFlow.state().name();
    }

    /** The kinds of the buttons currently in the insert row, e.g. ["BUILD","CANCEL"]. */
    List<String> buildButtonKinds() {
        return buildButtons.stream().map(Enum::name).toList();
    }

    /** Same effect as clicking the insert-row button of {@code kind} (a ButtonKind name). */
    void pressBuildButton(String kind) {
        pressBuildButton(BuildChatFlow.ButtonKind.valueOf(kind));
    }

    /** Only the "けんちく:" lines - the script transcript (You:/AI:) is not part of this. */
    String buildTranscriptText() {
        return String.join("\n", buildTranscript);
    }

    boolean isSendInFlight() {
        return sendInFlight;
    }

    int codeBlockCount() {
        return lastAssistantCodeBlocks.size();
    }

    /** Types {@code text} into the input box and sends it (the tab must already be open). */
    void typeAndSend(String text) {
        if (inputBox != null) {
            inputBox.setValue(text);
        }
        sendMessage();
    }
}
