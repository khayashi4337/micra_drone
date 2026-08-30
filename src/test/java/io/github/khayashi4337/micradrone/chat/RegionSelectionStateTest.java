package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class RegionSelectionStateTest {

    @Test
    void noSelectionYetConsumesToEmpty() {
        assertEquals(Optional.empty(), new RegionSelectionState().consumeAsText());
    }

    @Test
    void onlyTheStartCornerIsNotYetACompleteSelection() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(1, 64, 2);
        assertFalse(state.hasSelection());
        assertEquals(Optional.empty(), state.consumeAsText());
    }

    @Test
    void aSingleBlockIsBothCornersAtTheSameSpot() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(5, 70, -3);
        state.setCorner2(5, 70, -3);
        assertEquals(Optional.of("(5,70,-3)~(5,70,-3)"), state.consumeAsText());
    }

    @Test
    void normalizesToMinThenMaxRegardlessOfClickOrder() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(10, 70, 5);
        state.setCorner2(2, 64, -3);
        assertEquals(Optional.of("(2,64,-3)~(10,70,5)"), state.consumeAsText());
    }

    @Test
    void consumingClearsTheSelectionSoItIsNotReturnedTwice() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(0, 0, 0);
        state.setCorner2(1, 1, 1);

        assertTrue(state.consumeAsText().isPresent());
        assertEquals(Optional.empty(), state.consumeAsText());
    }

    @Test
    void cornersAreReadableForTheInWorldPreview() {
        RegionSelectionState state = new RegionSelectionState();
        assertEquals(Optional.empty(), state.corner1());
        assertEquals(Optional.empty(), state.corner2());

        state.setCorner1(1, 64, 2);
        assertEquals(Optional.of(new RegionSelectionState.Corner(1, 64, 2)), state.corner1());
        assertEquals(Optional.empty(), state.corner2());

        state.setCorner2(3, 65, 4);
        assertEquals(Optional.of(new RegionSelectionState.Corner(3, 65, 4)), state.corner2());
    }

    @Test
    void aNewStartCornerDropsTheOldEndCorner() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(0, 0, 0);
        state.setCorner2(5, 5, 5);

        state.setCorner1(9, 9, 9);

        assertFalse(state.hasSelection());
        assertEquals(Optional.empty(), state.corner2());
        assertEquals(Optional.empty(), state.consumeAsText());
    }

    @Test
    void reSelectingAfterAConsumeStartsFresh() {
        RegionSelectionState state = new RegionSelectionState();
        state.setCorner1(0, 0, 0);
        state.setCorner2(1, 1, 1);
        state.consumeAsText();

        state.setCorner1(9, 9, 9);
        assertFalse(state.hasSelection());
        state.setCorner2(9, 9, 9);
        assertEquals(Optional.of("(9,9,9)~(9,9,9)"), state.consumeAsText());
    }
}
