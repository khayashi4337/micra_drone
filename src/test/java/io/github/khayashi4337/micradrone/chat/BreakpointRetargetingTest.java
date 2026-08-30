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
}
