package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.chat.EditHistoryStore.Snapshot;

class EditHistoryStoreTest {
    private static final Snapshot STEP = new Snapshot("before the edit", 3);
    private static final Snapshot REDO_STEP = new Snapshot("after the edit", 5);

    @Test
    void aRetainedHistoryComesBackForTheSameTextAndKey() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("k", List.of(STEP), List.of(REDO_STEP), "current");
        assertEquals(List.of(STEP), store.undoFor("k", "current"));
        assertEquals(List.of(REDO_STEP), store.redoFor("k", "current"));
    }

    @Test
    void nothingComesBackForAKeyThatWasNeverRetained() {
        EditHistoryStore store = new EditHistoryStore();
        assertEquals(List.of(), store.undoFor("k", "current"));
        assertEquals(List.of(), store.redoFor("k", "current"));
    }

    /** The steps describe a document that no longer exists - replaying one would paste stale text. */
    @Test
    void aHistoryIsWithheldWhenTheTextChangedUnderneath() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("k", List.of(STEP), List.of(REDO_STEP), "current");
        assertEquals(List.of(), store.undoFor("k", "someone else saved this"));
        assertEquals(List.of(), store.redoFor("k", "someone else saved this"));
    }

    @Test
    void keysAreIndependent() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("a", List.of(STEP), List.of(), "text a");
        assertEquals(List.of(STEP), store.undoFor("a", "text a"));
        assertEquals(List.of(), store.undoFor("b", "text a"));
    }

    /** Retaining nothing must drop what was there, or a cleared history would resurrect an older one. */
    @Test
    void retainingAnEmptyHistoryForgetsTheKey() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("k", List.of(STEP), List.of(REDO_STEP), "current");
        store.retain("k", List.of(), List.of(), "current");
        assertEquals(List.of(), store.undoFor("k", "current"));
        assertEquals(List.of(), store.redoFor("k", "current"));
    }

    /**
     * The screen that parks nothing is not necessarily talking about the same text: an IDE closed
     * before the script arrives holds "", one closed mid-AI-review holds the diff markup. Neither
     * may throw away the history parked for the real script.
     */
    @Test
    void anEmptyRetainForOtherTextLeavesTheRetainedHistoryAlone() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("k", List.of(STEP), List.of(REDO_STEP), "current");
        store.retain("k", List.of(), List.of(), ""); // a screen that never loaded the script
        assertEquals(List.of(STEP), store.undoFor("k", "current"));
        assertEquals(List.of(REDO_STEP), store.redoFor("k", "current"));
    }

    @Test
    void clearDropsEveryRetainedHistory() {
        EditHistoryStore store = new EditHistoryStore();
        store.retain("a", List.of(STEP), List.of(), "text a");
        store.retain("b", List.of(STEP), List.of(), "text b");
        store.clear();
        assertEquals(List.of(), store.undoFor("a", "text a"));
        assertEquals(List.of(), store.undoFor("b", "text b"));
    }
}
