package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;

import org.junit.jupiter.api.Test;

class BreakpointRetargetingTest {

    @Test
    void aLineInsertedAboveShiftsTheBreakpointDown() {
        String before = "a\nb\nc";
        String after = "x\na\nb\nc"; // one new line inserted at the top
        Set<Integer> result = BreakpointRetargeting.retarget(Set.of(2), before, after);
        assertEquals(Set.of(3), result);
    }

    @Test
    void aLineRemovedAboveShiftsTheBreakpointUp() {
        String before = "a\nb\nc";
        String after = "b\nc"; // line 1 deleted
        Set<Integer> result = BreakpointRetargeting.retarget(Set.of(3), before, after);
        assertEquals(Set.of(2), result);
    }

    @Test
    void editingTheBreakpointedLineItselfDropsIt() {
        String before = "a\nb\nc";
        String after = "a\nchanged\nc"; // line 2's own text was edited
        Set<Integer> result = BreakpointRetargeting.retarget(Set.of(2), before, after);
        assertEquals(Set.of(), result);
    }

    @Test
    void deletingTheBreakpointedLineDropsIt() {
        String before = "a\nb\nc";
        String after = "a\nc"; // line 2 removed outright
        Set<Integer> result = BreakpointRetargeting.retarget(Set.of(2), before, after);
        assertEquals(Set.of(), result);
    }

    @Test
    void multipleBreakpointsEachFollowTheirOwnLine() {
        String before = "a\nb\nc\nd";
        String after = "x\na\nb\nc\nd"; // insert above all of them
        Set<Integer> result = BreakpointRetargeting.retarget(Set.of(1, 3), before, after);
        assertEquals(Set.of(2, 4), result);
    }

    @Test
    void unchangedTextReturnsTheSameSetInstanceWithoutDiffing() {
        Set<Integer> breakpoints = Set.of(2, 5);
        Set<Integer> result = BreakpointRetargeting.retarget(breakpoints, "a\nb", "a\nb");
        assertSame(breakpoints, result);
    }

    @Test
    void noBreakpointsIsANoOpEvenWithChangedText() {
        Set<Integer> empty = Set.of();
        Set<Integer> result = BreakpointRetargeting.retarget(empty, "a", "a\nb");
        assertSame(empty, result);
    }

    /**
     * Mirrors what {@code IdeScreen}'s AI-review flow actually does: a breakpoint on the last of
     * three lines follows into the merged diff view when review starts (the value listener's own
     * retargeting call), then - on Reject - {@code endReview} retargets explicitly from that merged
     * view back to the rejected (original) text, since the listener alone can't (see its own doc).
     * The breakpoint must land back on the same line it started on.
     */
    @Test
    void rejectingAnAiProposalReturnsTheBreakpointToItsOriginalLine() {
        String original = "a\nb\nc";
        String proposed = "a\nX\nb\nc"; // AI inserts a line before the last two
        String mergedView = LineDiff.between(original, proposed).mergedText();
        assertEquals("a\nX\nb\nc", mergedView);

        Set<Integer> onMergedView = BreakpointRetargeting.retarget(Set.of(3), original, mergedView);
        assertEquals(Set.of(4), onMergedView, "breakpoint should have followed 'c' down to line 4 while review is open");

        Set<Integer> afterReject = BreakpointRetargeting.retarget(onMergedView, mergedView, original);
        assertEquals(Set.of(3), afterReject, "rejecting must return the breakpoint to its original line, not strand it on the discarded merged view");
    }

    /**
     * Danger ON applies an AI proposal straight to the script and offers one "Undo AI change" -
     * neither goes through the review view, so each is just an ordinary whole-text edit. Both legs
     * must retarget (see {@code IdeScreen#applyWithoutReview}), leaving the breakpoint back on its
     * original line once the round trip completes.
     */
    @Test
    void applyingAndUndoingAnAiChangeReturnsTheBreakpointToItsOriginalLine() {
        String original = "a\nb\nc";
        String applied = "a\nX\nb\nc"; // the AI inserts a line above the breakpointed one

        Set<Integer> afterApply = BreakpointRetargeting.retarget(Set.of(3), original, applied);
        assertEquals(Set.of(4), afterApply, "applying must push the breakpoint down with its line");

        Set<Integer> afterUndo = BreakpointRetargeting.retarget(afterApply, applied, original);
        assertEquals(Set.of(3), afterUndo, "undoing must bring it back, not strand it on the applied line number");
    }

    /** Same setup as the reject case, but Accept keeps the merged view's text as-is - nothing to retarget. */
    @Test
    void acceptingAnAiProposalKeepsTheBreakpointOnTheInsertedLineNumber() {
        String original = "a\nb\nc";
        String proposed = "a\nX\nb\nc";
        LineDiff diff = LineDiff.between(original, proposed);
        String mergedView = diff.mergedText();

        Set<Integer> onMergedView = BreakpointRetargeting.retarget(Set.of(3), original, mergedView);
        assertEquals(Set.of(4), onMergedView);

        String accepted = diff.acceptedText();
        assertEquals(mergedView, accepted, "accepting a pure insertion leaves the text identical to the merged view");
        Set<Integer> afterAccept = BreakpointRetargeting.retarget(onMergedView, mergedView, accepted);
        assertEquals(Set.of(4), afterAccept);
    }
}
