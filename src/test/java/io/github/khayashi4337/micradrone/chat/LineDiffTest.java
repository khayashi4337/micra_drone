package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.LineDiff.Kind;

class LineDiffTest {

    @Test
    void identicalTextsHaveNoChangesAndMergeToThemselves() {
        LineDiff diff = LineDiff.between("a\nb", "a\nb");
        assertFalse(diff.hasChanges());
        assertEquals("a\nb", diff.mergedText());
        assertEquals(List.of(), diff.lineNumbersOf(Kind.ADDED));
        assertEquals(List.of(), diff.lineNumbersOf(Kind.REMOVED));
    }

    @Test
    void anAppendShowsUpAsAddedLinesAfterTheOriginal() {
        LineDiff diff = LineDiff.between("harvest()", "harvest()\nmove(\"east\")\nharvest()");
        assertEquals("harvest()\nmove(\"east\")\nharvest()", diff.mergedText());
        assertEquals(List.of(2, 3), diff.lineNumbersOf(Kind.ADDED));
        assertEquals(List.of(), diff.lineNumbersOf(Kind.REMOVED));
    }

    @Test
    void anOverwriteKeepsTheOldLinesInPlaceMarkedRemoved() {
        LineDiff diff = LineDiff.between("till()\nplant(\"wheat\")", "harvest()");
        // Every original line is visible as removed, so the player sees exactly what would vanish.
        assertEquals(List.of("till()", "plant(\"wheat\")", "harvest()"),
                diff.lines().stream().map(LineDiff.Line::text).toList());
        assertEquals(List.of(1, 2), diff.lineNumbersOf(Kind.REMOVED));
        assertEquals(List.of(3), diff.lineNumbersOf(Kind.ADDED));
    }

    @Test
    void aChangedLineInTheMiddleIsRemovedThenAddedWithContextIntact() {
        LineDiff diff = LineDiff.between("a\nb\nc", "a\nB\nc");
        assertEquals("a\nb\nB\nc", diff.mergedText());
        assertEquals(List.of(2), diff.lineNumbersOf(Kind.REMOVED));
        assertEquals(List.of(3), diff.lineNumbersOf(Kind.ADDED));
        assertTrue(diff.hasChanges());
    }

    @Test
    void anEmptyEditorDiffsAsPureAdditions() {
        LineDiff diff = LineDiff.between("", "x = 1\nprint(x)");
        assertEquals(List.of(1, 2), diff.lineNumbersOf(Kind.ADDED));
        assertEquals(List.of(), diff.lineNumbersOf(Kind.REMOVED));
        assertEquals("x = 1\nprint(x)", diff.mergedText());
    }

    @Test
    void blankLinesAreDiffedLikeAnyOtherLine() {
        LineDiff diff = LineDiff.between("a\n\nb", "a\nb");
        assertEquals(List.of(2), diff.lineNumbersOf(Kind.REMOVED));
        assertEquals("a\n\nb", diff.mergedText());
    }
}
