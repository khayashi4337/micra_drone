package io.github.khayashi4337.micradrone.client;

import java.util.Set;

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
 */
final class DebugEditBox extends MultiLineEditBox {
    /** MultiLineEditBox renders every text row exactly this tall (hardcoded there). */
    static final int LINE_HEIGHT = 9;
    private static final int CURRENT_LINE_COLOR = 0x66FFD83D;   // translucent yellow
    private static final int BREAKPOINT_LINE_COLOR = 0x55CC3333; // translucent red

    private int currentLine; // 1-based; 0 = no highlight
    private Set<Integer> breakpointLines = Set.of();

    DebugEditBox(Font font, int x, int y, int width, int height, Component placeholder, Component message) {
        super(font, x, y, width, height, placeholder, message);
    }

    void setCurrentLine(int line) {
        this.currentLine = line;
    }

    void setBreakpointLines(Set<Integer> lines) {
        this.breakpointLines = Set.copyOf(lines);
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
