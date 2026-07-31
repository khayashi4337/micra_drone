package io.github.khayashi4337.micradrone.client;

import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
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
 */
final class DebugEditBox extends MultiLineEditBox {
    /** MultiLineEditBox renders every text row exactly this tall (hardcoded there). */
    static final int LINE_HEIGHT = 9;
    private static final int CURRENT_LINE_COLOR = 0x66FFD83D;   // translucent yellow
    private static final int BREAKPOINT_LINE_COLOR = 0x55CC3333; // translucent red

    private int currentLine; // 1-based; 0 = no highlight
    private Set<Integer> breakpointLines = Set.of();
    private String previousValue = "";
    private Consumer<String> autocompleteListener = word -> { };

    DebugEditBox(Font font, int x, int y, int width, int height, Component placeholder, Component message) {
        super(font, x, y, width, height, placeholder, message);
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
        super.renderContents(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void drawLineBar(GuiGraphics guiGraphics, int line, int color) {
        int top = getY() + innerPadding() + (line - 1) * LINE_HEIGHT;
        if (withinContentAreaTopBottom(top, top + LINE_HEIGHT)) {
            guiGraphics.fill(getX() + 1, top - 1, getX() + getWidth() - 1, top + LINE_HEIGHT, color);
        }
    }
}
