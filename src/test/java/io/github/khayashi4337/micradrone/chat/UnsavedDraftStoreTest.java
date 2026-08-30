package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UnsavedDraftStoreTest {

    @Test
    void resolveFallsBackToTheServerCopyWhenThereIsNoDraft() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        assertEquals("saved", store.resolve("k", "saved"));
    }

    @Test
    void recordedTextWinsOverTheFallbackOnResolve() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        store.record("k", "unsaved edit", false);
        assertEquals("unsaved edit", store.resolve("k", "saved"));
    }

    @Test
    void recordingWhileReviewingIsIgnored() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        store.record("k", "merged diff markup", true);
        assertEquals("saved", store.resolve("k", "saved"));
    }

    @Test
    void forgetDropsTheDraftAfterASave() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        store.record("k", "unsaved edit", false);
        store.forget("k");
        assertEquals("saved", store.resolve("k", "saved"));
    }

    @Test
    void clearDropsEveryDraftAcrossAllKeys() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        store.record("a", "draft a", false);
        store.record("b", "draft b", false);
        store.clear();
        assertEquals("saved a", store.resolve("a", "saved a"));
        assertEquals("saved b", store.resolve("b", "saved b"));
    }

    @Test
    void keysAreIndependent() {
        UnsavedDraftStore store = new UnsavedDraftStore();
        store.record("a", "draft a", false);
        assertEquals("draft a", store.resolve("a", "saved a"));
        assertEquals("saved b", store.resolve("b", "saved b"));
    }
}
