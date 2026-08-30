package io.github.khayashi4337.micradrone.client;

import java.util.List;
import java.util.Set;

import io.github.khayashi4337.micradrone.lang.SyntaxHighlighter;
import io.github.khayashi4337.micradrone.lang.SyntaxHighlighter.Kind;
import io.github.khayashi4337.micradrone.lang.SyntaxHighlighter.Span;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;

/**
 * {@link MultiLineEditBox} with debugger decorations (issue #6): a translucent yellow bar under
 * the line about to execute and translucent red bars under breakpoint lines, drawn beneath the
 * text so it stays readable. Line numbers are 1-based script lines; bars track the widget's own
 * scrolling automatically because {@code renderContents} runs with the pose already translated by
 * {@code -scrollAmount} and scissored to the widget box (see AbstractScrollWidget#renderWidget) -
 * raw widget coordinates plus the same {@code withinContentAreaTopBottom} guard the text rows use
 * line each bar up with its text exactly.
 *
 * <p>It also syntax-highlights the script in a Monokai palette. Vanilla draws the whole text in one
 * flat color, so this replaces {@code renderContents} outright rather than decorating it -
 * including the cursor and the selection highlight, which vanilla drew as part of the same pass.
 * Those keep reading vanilla's own {@link MultilineTextField} state (opened up by this mod's access
 * transformer, see {@code META-INF/accesstransformer.cfg}) rather than being tracked separately, so
 * they stay exactly where vanilla's untouched key/mouse handling puts them.
 *
 * <p>The same access opens up the command autocomplete popup {@code IdeScreen} draws:
 * {@link #wordBeforeCursor} reads the identifier being typed straight off the real cursor, and
 * {@link #replaceWordBeforeCursor} swaps it for a suggestion through
 * {@code MultilineTextField}'s own {@code deleteText}/{@code insertText} - the exact pair vanilla's
 * backspace and typing go through, which leave the cursor sitting after the edit. So completion
 * works wherever the caret happens to be, not only at the end of the script.
 */
final class DebugEditBox extends MultiLineEditBox {
    /** MultiLineEditBox renders every text row exactly this tall (hardcoded there). */
    static final int LINE_HEIGHT = 9;
    private static final int CURRENT_LINE_COLOR = 0x66FFD83D;   // translucent yellow
    private static final int BREAKPOINT_LINE_COLOR = 0x55CC3333; // translucent red
    // AI-change review (git-diff colors): lines the proposal adds / lines it would remove.
    private static final int DIFF_ADDED_LINE_COLOR = 0x5540C060;   // translucent green
    private static final int DIFF_REMOVED_LINE_COLOR = 0x66C03030; // translucent red, a bit stronger

    // Monokai. DEFAULT covers variable names and whitespace; operators share the keyword color, and
    // numbers the constant color, the same way the original theme groups them.
    private static final int BACKGROUND_COLOR = 0xFF272822;
    private static final int DEFAULT_COLOR = 0xFFF8F8F2;
    private static final int KEYWORD_COLOR = 0xFFF92672;
    private static final int CONSTANT_COLOR = 0xFFAE81FF;
    private static final int STRING_COLOR = 0xFFE6DB74;
    private static final int COMMENT_COLOR = 0xFF75715E;
    private static final int BUILTIN_COLOR = 0xFF66D9EF;
    private static final int CALL_COLOR = 0xFFA6E22E;

    // Vanilla's own cursor/selection values, kept identical so only the text colors change.
    private static final int CURSOR_COLOR = 0xFFD0D0D0;
    private static final int SELECTION_COLOR = 0xFF0000FF;
    private static final long CURSOR_BLINK_INTERVAL_MS = 300;

    private final Font font;
    private int currentLine; // 1-based; 0 = no highlight
    private Set<Integer> breakpointLines = Set.of();
    private Set<Integer> diffAddedLines = Set.of();
    private Set<Integer> diffRemovedLines = Set.of();
    /** While an AI change is under review the merged view must not be edited - its line map would drift. */
    private boolean locked;
    /** Drives the cursor blink; vanilla keeps the same clock privately, so this mirrors its {@code setFocused}. */
    private long focusedAtMs = Util.getMillis();
    // Rendering runs every frame but the script only changes when someone types, so the scan is
    // cached against the last value it ran on and only re-scanned when a String#equals check finds
    // the text has actually changed - cheap for a script-sized string, and far cheaper than
    // re-tokenizing every frame regardless.
    private String scannedValue;
    private List<Span> scannedSpans = List.of();

    DebugEditBox(Font font, int x, int y, int width, int height, Component placeholder, Component message) {
        super(font, x, y, width, height, placeholder, message);
        this.font = font;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) {
            this.focusedAtMs = Util.getMillis();
        }
    }

    void setCurrentLine(int line) {
        this.currentLine = line;
    }

    /** The line the yellow bar is on; 0 when nothing is highlighted. */
    int currentLine() {
        return currentLine;
    }

    void setBreakpointLines(Set<Integer> lines) {
        this.breakpointLines = Set.copyOf(lines);
    }

    /** Colors the review view: {@code added} green, {@code removed} red (1-based lines of the merged text). */
    void setDiffLines(Set<Integer> added, Set<Integer> removed) {
        this.diffAddedLines = Set.copyOf(added);
        this.diffRemovedLines = Set.copyOf(removed);
    }

    /** Refuses typing/paste while set; caret movement and selection still work so the review can be read. */
    void setLocked(boolean locked) {
        this.locked = locked;
    }

    /**
     * What the Tab key inserts. The language forbids tab characters for indentation (see
     * {@code Lexer#startOfLine}), and every sample and help scroll indents by four spaces, so Tab
     * types that instead of a {@code '\t'} the script would then refuse to run.
     */
    private static final String TAB_AS_SPACES = "    ";

    /**
     * Tab inserts {@link #TAB_AS_SPACES} at the caret. Vanilla's {@code MultilineTextField} does
     * not handle Tab at all, so it fell through to {@code Screen#keyPressed}'s focus traversal and
     * hopped the keyboard from the editor to the next button - in a code editor, Tab has to indent
     * (real-machine report). Handled here, in the widget, rather than in {@code IdeScreen}, so it
     * only fires while the editor actually holds the focus; the screen still gets first refusal for
     * the autocomplete popup's own Tab-to-accept before this ever sees the key.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (locked && isEditingKey(keyCode, modifiers)) {
            return true; // swallowed: the merged view is read-only until Accept/Reject
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB && !locked) {
            textField.insertText(TAB_AS_SPACES);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (locked) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** Everything vanilla's MultilineTextField treats as an edit: backspace, delete, enter, and paste/cut. */
    private static boolean isEditingKey(int keyCode, int modifiers) {
        boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
        return keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
                || (ctrl && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_V || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_X));
    }

    /**
     * The identifier being typed - everything word-shaped immediately before the cursor, or "" if
     * there is none. Also "" while any text is selected: nothing is being typed at that point, and
     * {@link #replaceWordBeforeCursor} could not honour the selection anyway (see its note), so
     * reporting no word keeps the popup away from the one state that edit can't handle.
     */
    String wordBeforeCursor() {
        if (textField.hasSelection()) {
            return "";
        }
        String value = textField.value();
        int cursor = textField.cursor();
        int start = cursor;
        while (start > 0 && isWordChar(value.charAt(start - 1))) {
            start--;
        }
        return value.substring(start, cursor);
    }

    /**
     * Swaps {@link #wordBeforeCursor} for {@code replacement}, leaving the cursor right after it -
     * the same {@code deleteText}/{@code insertText} pair backspace and typing use, so the caret
     * behaves exactly as if the player had typed the rest of the word themselves.
     *
     * <p>No-op while text is selected. {@code MultilineTextField#deleteText} ignores its length
     * argument entirely when there is a selection and wipes the selection instead (verified in
     * decompiled sources), which would delete the wrong text and then paste the suggestion into the
     * hole - so this refuses rather than corrupting the script. {@link #wordBeforeCursor} already
     * reports "" in that state, so the popup never offers anything to accept there in the first
     * place; this is the backstop.
     */
    void replaceWordBeforeCursor(String replacement) {
        if (textField.hasSelection()) {
            return;
        }
        int wordLength = wordBeforeCursor().length();
        if (wordLength > 0) {
            textField.deleteText(-wordLength);
        }
        textField.insertText(replacement);
    }

    /** Screen X of the caret - where {@code IdeScreen} anchors the autocomplete popup. */
    int cursorScreenX() {
        String value = textField.value();
        int cursor = textField.cursor();
        for (MultilineTextField.StringView row : textField.iterateLines()) {
            if (cursor >= row.beginIndex() && cursor <= row.endIndex()) {
                return getX() + innerPadding() + font.width(value.substring(row.beginIndex(), cursor));
            }
        }
        return getX() + innerPadding();
    }

    /** Screen Y of the top of the caret's row, already scrolled. */
    int cursorScreenY() {
        return getY() + innerPadding() + textField.getLineAtCursor() * LINE_HEIGHT - (int) scrollAmount();
    }

    /** Matches the language's own identifier rule (Lexer#identifier), minus "can't start with a digit" - a prefix scan doesn't need that. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** The gutter (drawn by IdeScreen) must scroll in sync with the text. */
    double gutterScroll() {
        return scrollAmount();
    }

    int gutterTopPadding() {
        return innerPadding();
    }

    /**
     * Replaces vanilla's text-field sprite with Monokai's flat dark background. Drawn by
     * AbstractScrollWidget#renderWidget before (and separately from) {@link #renderContents}, so it
     * is just a backdrop - the palette the text on top of it uses lives in {@link #colorOf}.
     */
    @Override
    protected void renderBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BACKGROUND_COLOR);
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        for (int line : diffRemovedLines) {
            drawLineBar(guiGraphics, line, DIFF_REMOVED_LINE_COLOR);
        }
        for (int line : diffAddedLines) {
            drawLineBar(guiGraphics, line, DIFF_ADDED_LINE_COLOR);
        }
        for (int line : breakpointLines) {
            if (line != currentLine) {
                drawLineBar(guiGraphics, line, BREAKPOINT_LINE_COLOR);
            }
        }
        if (currentLine > 0) {
            drawLineBar(guiGraphics, currentLine, CURRENT_LINE_COLOR);
        }
        String value = textField.value();
        if (value.isEmpty() && !isFocused()) {
            super.renderContents(guiGraphics, mouseX, mouseY, partialTick); // vanilla's placeholder
            return;
        }
        renderText(guiGraphics, value);
    }

    private void drawLineBar(GuiGraphics guiGraphics, int line, int color) {
        int top = getY() + innerPadding() + (line - 1) * LINE_HEIGHT;
        if (withinContentAreaTopBottom(top, top + LINE_HEIGHT)) {
            guiGraphics.fill(getX() + 1, top - 1, getX() + getWidth() - 1, top + LINE_HEIGHT, color);
        }
    }

    /**
     * Draws the script one syntax-colored run at a time, plus the cursor and selection vanilla drew
     * in the same pass. Rows come from {@link MultilineTextField#iterateLines} rather than being
     * split here, so they line up with the wrapping vanilla itself scrolls and click-positions by.
     */
    private void renderText(GuiGraphics guiGraphics, String value) {
        List<Span> spans = spansFor(value);
        int cursor = textField.cursor();
        boolean cursorShowing = isFocused()
                && (Util.getMillis() - focusedAtMs) / CURSOR_BLINK_INTERVAL_MS % 2L == 0L;
        // Past the last character there is no glyph to draw a bar in front of, so vanilla switches
        // to an underscore after the final row instead - tracked by the two variables below.
        boolean cursorInsideText = cursor < value.length();
        int left = getX() + innerPadding();
        int rowY = getY() + innerPadding();
        int endOfLastRowX = left;
        int lastRowWithoutCursorY = rowY;

        for (MultilineTextField.StringView row : textField.iterateLines()) {
            boolean cursorOnThisRow = cursorShowing && cursorInsideText
                    && cursor >= row.beginIndex() && cursor <= row.endIndex();
            if (withinContentAreaTopBottom(rowY, rowY + LINE_HEIGHT)) {
                endOfLastRowX = drawRow(guiGraphics, value, spans, row.beginIndex(), row.endIndex(), left, rowY);
                if (cursorOnThisRow) {
                    int cursorX = left + font.width(value.substring(row.beginIndex(), cursor));
                    guiGraphics.fill(cursorX, rowY - 1, cursorX + 1, rowY + 1 + LINE_HEIGHT, CURSOR_COLOR);
                }
            }
            if (!cursorOnThisRow) {
                lastRowWithoutCursorY = rowY;
            }
            rowY += LINE_HEIGHT;
        }

        if (cursorShowing && !cursorInsideText
                && withinContentAreaTopBottom(lastRowWithoutCursorY, lastRowWithoutCursorY + LINE_HEIGHT)) {
            guiGraphics.drawString(font, "_", endOfLastRowX, lastRowWithoutCursorY, CURSOR_COLOR);
        }
        if (textField.hasSelection()) {
            renderSelection(guiGraphics, value, left);
        }
    }

    /**
     * Draws {@code [from, to)} of one row, clipping each span to it, and returns where the row's
     * text ended.
     *
     * <p>The {@code - 1} is load-bearing. {@code GuiGraphics#drawString(Font, String, int, int, int)}
     * draws WITH a drop shadow, and {@code Font#drawInternal} then returns
     * {@code (int) x + (dropShadow ? 1 : 0)} - one pixel PAST the last glyph, not the glyph's end
     * (decompiled 1.21.1 {@code Font.java:204}). The glyph advance a run ends on already includes
     * the usual 1px letter spacing, so that extra pixel is a pure offset, and vanilla's own
     * {@code MultiLineEditBox#renderContents} subtracts it the same way
     * ({@code MultiLineEditBox.java:142,155}). The cursor bar and selection band, meanwhile, are
     * placed by {@code Font#width}, which sums advances with no shadow pixel. Without the
     * {@code - 1}, every span boundary shifted the rest of the row 1px right of where
     * {@code Font#width} says it is: on a line with a dozen color runs the text sat ~12px (two
     * characters) right of the cursor bar - which is exactly the misalignment #24 set out to fix,
     * and what removing the {@code - 1} (as #24 / #32 did) produced instead. Restored here.
     */
    private int drawRow(GuiGraphics guiGraphics, String value, List<Span> spans, int from, int to, int x, int y) {
        int cursorX = x;
        for (Span span : spans) {
            int start = Math.max(span.start(), from);
            int end = Math.min(span.end(), to);
            if (start >= end) {
                continue;
            }
            cursorX = guiGraphics.drawString(font, value.substring(start, end), cursorX, y, colorOf(span.kind())) - 1;
        }
        return cursorX;
    }

    /** The blue band behind selected text - vanilla's own geometry and blend mode, unchanged. */
    private void renderSelection(GuiGraphics guiGraphics, String value, int left) {
        MultilineTextField.StringView selection = textField.getSelected();
        int rowY = getY() + innerPadding();
        for (MultilineTextField.StringView row : textField.iterateLines()) {
            if (selection.beginIndex() > row.endIndex()) {
                rowY += LINE_HEIGHT;
                continue;
            }
            if (row.beginIndex() > selection.endIndex()) {
                break;
            }
            if (withinContentAreaTopBottom(rowY, rowY + LINE_HEIGHT)) {
                int startX = font.width(value.substring(row.beginIndex(),
                        Math.max(selection.beginIndex(), row.beginIndex())));
                int endX = selection.endIndex() > row.endIndex()
                        ? this.width - innerPadding()
                        : font.width(value.substring(row.beginIndex(), selection.endIndex()));
                guiGraphics.fill(RenderType.guiTextHighlight(),
                        left + startX, rowY, left + endX, rowY + LINE_HEIGHT, SELECTION_COLOR);
            }
            rowY += LINE_HEIGHT;
        }
    }

    private List<Span> spansFor(String value) {
        if (!value.equals(scannedValue)) {
            scannedValue = value;
            scannedSpans = SyntaxHighlighter.highlight(value);
        }
        return scannedSpans;
    }

    private static int colorOf(Kind kind) {
        return switch (kind) {
            case KEYWORD, OPERATOR -> KEYWORD_COLOR;
            case CONSTANT, NUMBER -> CONSTANT_COLOR;
            case STRING -> STRING_COLOR;
            case COMMENT -> COMMENT_COLOR;
            case BUILTIN -> BUILTIN_COLOR;
            case CALL -> CALL_COLOR;
            case DEFAULT -> DEFAULT_COLOR;
        };
    }
}
