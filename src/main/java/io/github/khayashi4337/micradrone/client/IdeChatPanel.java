package io.github.khayashi4337.micradrone.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.khayashi4337.micradrone.chat.ChatCompactor;
import io.github.khayashi4337.micradrone.chat.ChatContextBuilder;
import io.github.khayashi4337.micradrone.chat.ChatHistoryStore;
import io.github.khayashi4337.micradrone.chat.ChatMessage;
import io.github.khayashi4337.micradrone.chat.ChatSession;
import io.github.khayashi4337.micradrone.chat.ClaudeCliBridge;
import io.github.khayashi4337.micradrone.chat.CodeBlockParser;
import io.github.khayashi4337.micradrone.chat.ControllerKey;
import io.github.khayashi4337.micradrone.chat.DangerModeState;
import io.github.khayashi4337.micradrone.drone.CommandsHelpDoc;
import io.github.khayashi4337.micradrone.drone.CornerMarkerScan;
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
import org.lwjgl.glfw.GLFW;

/**
 * The IDE's right-half AI chat tab (Wave 5), split out of {@link IdeScreen} once that class passed
 * 1300 lines: the transcript, per-reply Insert buttons, the input row, the danger/compact row, the
 * claude -p round trip behind them, and the per-controller history on disk. IdeScreen still owns
 * layout, tab switching and the widget list; this class reaches those through {@link Host} rather
 * than being a Screen itself. One ClaudeCliBridge/DangerModeState per screen instance is enough -
 * there's no per-controller CLI process, only a per-controller ChatSession.
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

    private final Host host;
    private final ClaudeCliBridge claudeCliBridge = new ClaudeCliBridge(CLAUDE_EXECUTABLE);
    private final DangerModeState dangerMode = new DangerModeState();
    private boolean open = false;
    private ChatSession chatSession;
    private boolean sendInFlight = false;
    private List<CodeBlockParser.CodeBlock> lastAssistantCodeBlocks = List.of();
    private MultiLineEditBox logBox;
    private EditBox inputBox;
    private Button sendButton;
    private Button compactButton;
    private Button dangerButton;
    /** What the player has typed but not sent - restored across the rebuild a landing reply triggers. */
    private String inputDraft = "";
    private int statusRowX;
    private int statusRowY;
    /** Client ticks since the panel was built; drives the thinking-dots animation. */
    private int animationTicks = 0;

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
     * reply's code is under review in the editor), the message input row, and the danger/compact
     * toggle row.
     */
    void initWidgets(int rightX, int rightW, int topY, int bottomY) {
        ensureSessionLoaded();

        int toggleRowY = bottomY - TOGGLE_ROW_HEIGHT;
        int inputRowY = toggleRowY - ROW_GAP - INPUT_ROW_HEIGHT;
        int insertRowY = inputRowY - ROW_GAP - INSERT_ROW_HEIGHT;
        statusRowY = insertRowY - ROW_GAP - STATUS_ROW_HEIGHT;
        statusRowX = rightX;
        int logHeight = statusRowY - ROW_GAP - topY;

        logBox = new MultiLineEditBox(host.font(), rightX, topY, rightW, logHeight,
                Component.translatable("gui.micradrone.ide_screen.chat_log_placeholder"),
                Component.translatable("gui.micradrone.ide_screen.chat_log"));
        logBox.setValue(transcriptText());
        host.addWidget(logBox);

        if (host.isReviewing()) {
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
        host.addWidget(inputBox);
        sendButton = host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_send"),
                        b -> sendMessage())
                .bounds(rightX + rightW - SEND_BUTTON_WIDTH, inputRowY, SEND_BUTTON_WIDTH, INPUT_ROW_HEIGHT)
                .build());
        sendButton.active = !sendInFlight;

        int toggleBtnW = (rightW - ROW_GAP) / 2;
        dangerButton = host.addWidget(Button.builder(dangerButtonLabel(),
                        b -> {
                            dangerMode.toggle();
                            dangerButton.setMessage(dangerButtonLabel());
                        })
                .bounds(rightX, toggleRowY, toggleBtnW, TOGGLE_ROW_HEIGHT).build());
        compactButton = host.addWidget(Button.builder(Component.translatable("gui.micradrone.ide_screen.chat_compact"),
                        b -> compact())
                .bounds(rightX + toggleBtnW + ROW_GAP, toggleRowY, toggleBtnW, TOGGLE_ROW_HEIGHT).build());
        compactButton.active = !sendInFlight;
    }

    /** Once per client tick (from IdeScreen#tick): advances the thinking-dots animation. */
    void tick() {
        animationTicks++;
    }

    /**
     * Draws the "AI: thinking..." status row while a reply (or compact) is in flight - the dots
     * grow one at a time and wrap, the usual "responding" cue. Drawn by IdeScreen after its widgets
     * so it sits on top of the panel fill.
     */
    void render(GuiGraphics guiGraphics) {
        if (!open || !sendInFlight) {
            return;
        }
        int dots = (animationTicks / THINKING_DOT_INTERVAL_TICKS) % (THINKING_MAX_DOTS + 1);
        String text = Component.translatable("gui.micradrone.ide_screen.chat_thinking").getString() + ".".repeat(dots);
        guiGraphics.drawString(host.font(), text, statusRowX, statusRowY + (STATUS_ROW_HEIGHT - host.font().lineHeight) / 2,
                THINKING_COLOR);
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

    /** Enter in the input box sends; everything else is left to the host. */
    boolean handleKeyPressed(int keyCode) {
        if (inputBox != null && inputBox.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            sendMessage();
            return true;
        }
        return false;
    }

    private Component dangerButtonLabel() {
        return Component.translatable(dangerMode.isEnabled()
                ? "gui.micradrone.ide_screen.chat_danger_on" : "gui.micradrone.ide_screen.chat_danger_off");
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

    private void refreshTranscript() {
        if (logBox != null) {
            logBox.setValue(transcriptText());
        }
    }

    /**
     * Sends the input box's text as one chat turn: builds the prompt (script + command reference +
     * plot mapping + last error + any pending region reference), dispatches it on ClaudeCliBridge's
     * background executor, and disables the Send button until the response (or failure) lands.
     */
    private void sendMessage() {
        Minecraft minecraft = host.minecraft();
        if (sendInFlight || inputBox == null || minecraft == null || minecraft.level == null) {
            return;
        }
        String question = inputBox.getValue().trim();
        if (question.isEmpty()) {
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
        ChatContextBuilder.ChatContext context = new ChatContextBuilder.ChatContext(
                host.editorText(), CommandsHelpDoc.COMMANDS, host.logLines(), pendingRegion, priorSummary,
                Optional.of(plot));
        String prompt = ChatContextBuilder.build(question, context);

        ClaudeCliBridge.ClaudeCliOptions options = chatSession.cliSessionId() == null
                ? ClaudeCliBridge.ClaudeCliOptions.freshSession(dangerMode.toCliFlags(), mcpConfigPath.get())
                : new ClaudeCliBridge.ClaudeCliOptions(chatSession.cliSessionId(), false, dangerMode.toCliFlags(), mcpConfigPath.get());

        setRoundTripInFlight(true);
        inputBox.setValue("");
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
        if (result.success()) {
            chatSession.addMessage(new ChatMessage(ChatMessage.ROLE_ASSISTANT, result.responseText(), System.currentTimeMillis()));
            if (result.sessionId() != null) {
                chatSession.setCliSessionId(result.sessionId());
            }
            lastAssistantCodeBlocks = CodeBlockParser.parse(result.responseText());
            // Cursor's flow: by the time you read the reply, its code is already sitting in the
            // editor as a pending diff - no Insert click. The first block is applied for review
            // right away (Reject puts the script back untouched); nothing is applied on top of a
            // review still open from a previous turn.
            if (!lastAssistantCodeBlocks.isEmpty() && !host.isReviewing() && !host.isClosed()) {
                host.beginReview(lastAssistantCodeBlocks.get(0).code());
            }
        } else {
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
                chatSession.cliSessionId(), false, dangerMode.toCliFlags(), mcpConfigPath.get());

        setRoundTripInFlight(true);
        claudeCliBridge.send(ChatCompactor.COMPACT_REQUEST_PROMPT, options)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> onCompactResult(result)));
    }

    private void onCompactResult(ClaudeCliBridge.ClaudeCliResult result) {
        setRoundTripInFlight(false);
        if (chatSession == null) {
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

    boolean isSendInFlight() {
        return sendInFlight;
    }

    int codeBlockCount() {
        return lastAssistantCodeBlocks.size();
    }

    boolean isDangerMode() {
        return dangerMode.isEnabled();
    }

    void setDangerMode(boolean enabled) {
        dangerMode.setEnabled(enabled);
        if (dangerButton != null) {
            dangerButton.setMessage(dangerButtonLabel());
        }
    }

    /** Types {@code text} into the input box and sends it (the tab must already be open). */
    void typeAndSend(String text) {
        if (inputBox != null) {
            inputBox.setValue(text);
        }
        sendMessage();
    }
}
