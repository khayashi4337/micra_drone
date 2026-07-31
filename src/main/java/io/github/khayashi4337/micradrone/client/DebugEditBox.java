package io.github.khayashi4337.micradrone.client;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
 * <p>Also detects the word currently being typed at the very end of the text, for the command
 * autocomplete popup {@code IdeScreen} draws. Vanilla
 * {@link MultiLineEditBox} exposes no cursor-position API at all (verified in decompiled sources -
 * {@code MultilineTextField.cursor()} is behind a private field even subclasses can't reach), and
 * {@link #setValue} always snaps the cursor to the end of the text (also verified), so accepting a
 * suggestion anywhere but the true end would jerk the cursor away from wherever the player actually
 * was. {@link #setAutocompleteListener} therefore only ever reports a word when the edit that just
 * happened - detected by diffing the previous value against the new one, since that's the only
 * signal available - was at the tail of the whole script; editing earlier in the script silently
 * reports "no word" (empty string) instead of guessing wrong.
 *
 * <p>Finally, it syntax-highlights the script in a Monokai palette. Vanilla draws the whole text in
 * one flat color, so this replaces {@code renderContents} outright rather than decorating it -
 * including the cursor and the selection highlight, which vanilla drew as part of the same pass.
 * Those keep reading vanilla's own {@link MultilineTextField} state (opened up by this mod's access
 * transformer, see {@code META-INF/accesstransformer.cfg}) rather than being tracked separately, so
 * they stay exactly where vanilla's untouched key/mouse handling puts them.
 */
final class DebugEditBox extends MultiLineEditBox {
    /** MultiLineEditBox renders every text row exactly this tall (hardcoded there). */
    static final int LINE_HEIGHT = 9;
    private static final int CURRENT_LINE_COLOR = 0x66FFD83D;   // translucent yellow
    private static final int BREAKPOINT_LINE_COLOR = 0x55CC3333; // translucent red

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
    private String previousValue = "";
    private Consumer<String> autocompleteListener = word -> { };
    /** Drives the cursor blink; vanilla keeps the same clock privately, so this mirrors its {@code setFocused}. */
    private long focusedAtMs = Util.getMillis();

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

    void setBreakpointLines(Set<Integer> lines) {
        this.breakpointLines = Set.copyOf(lines);
    }

    /** Called with the identifier-shaped word ending the text after every tail edit; "" when there is none (see class doc). */
    void setAutocompleteListener(Consumer<String> listener) {
        this.autocompleteListener = listener;
    }

    @Override
    public void setValue(String fullText) {
        super.setValue(fullText);
        this.previousValue = fullText;
    }

    @Override
    public void setValueListener(Consumer<String> valueListener) {
        super.setValueListener(text -> {
            detectAutocompleteWord(text);
            valueListener.accept(text);
        });
    }

    private void detectAutocompleteWord(String newValue) {
        String oldValue = previousValue;
        previousValue = newValue;
        int commonPrefix = commonPrefixLength(oldValue, newValue);
        if (commonPrefix != Math.min(oldValue.length(), newValue.length())) {
            autocompleteListener.accept(""); // edit happened somewhere other than the tail
            return;
        }
        int wordStart = newValue.length();
        while (wordStart > 0 && isWordChar(newValue.charAt(wordStart - 1))) {
            wordStart--;
        }
        autocompleteListener.accept(newValue.substring(wordStart));
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
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
     * Replaces vanilla's text-field sprite with a flat Monokai-style dark background - independent
     * of {@link #renderContents} (AbstractScrollWidget#renderWidget calls this first, unrelated to
     * the cursor/selection/text vanilla draws afterward - verified in decompiled sources), so this
     * is safe to always apply regardless of focus state. Vanilla's own text color (0xFFE0E0E0, a
     * light grey - decoded from the private constant used in MultiLineEditBox#renderContents)
     * stays plenty legible against it.
     */
    @Override
    protected void renderBackground(GuiGraphics guiGraphics) {
        guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), BACKGROUND_COLOR);
    }

    @Override
    protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
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
        List<Span> spans = SyntaxHighlighter.highlight(value);
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
     * Draws {@code [from, to)} of one row, clipping each span to it. Returns where the row's text
     * ended: {@code drawString} hands back the next glyph's position (one pixel of spacing past the
     * text), so backing off that pixel each time makes the runs join seamlessly.
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
