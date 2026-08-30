package io.github.khayashi4337.micradrone.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps an editor's undo/redo history alive across a screen close, so reopening the IDE on the same
 * script can still take back the edit made before it was closed. Closing destroys the whole screen
 * and the editor widget with it, which would otherwise throw the history away - and "close the IDE
 * to look at something, come back, press Ctrl+Z" is an ordinary thing to do (real-machine report).
 * The unsaved text itself already survives the same way, see {@link UnsavedDraftStore}; this is its
 * counterpart for the history, keyed identically.
 *
 * <p>A history is only handed back when it still describes the text the editor now holds. If the
 * script changed underneath (someone else saved it, the player edited it elsewhere), the stored
 * steps describe a document that no longer exists, and replaying one would paste stale text over
 * the current script. Minecraft-free so the matching rule is unit-testable.
 */
public final class EditHistoryStore {
    /** One step: the text and caret position as they were before an edit. */
    public record Snapshot(String value, int cursor) {
    }

    private record Retained(List<Snapshot> undo, List<Snapshot> redo, String text) {
    }

    private final Map<String, Retained> byKey = new HashMap<>();

    /**
     * Remembers {@code undo}/{@code redo} against the text they describe. Storing an empty history
     * forgets the key instead, so a script whose history was cleared doesn't keep an older one.
     */
    public void retain(String key, List<Snapshot> undo, List<Snapshot> redo, String text) {
        if (undo.isEmpty() && redo.isEmpty()) {
            byKey.remove(key);
            return;
        }
        byKey.put(key, new Retained(List.copyOf(undo), List.copyOf(redo), text));
    }

    /** The remembered undo steps for {@code key}, or empty if there are none or they describe other text. */
    public List<Snapshot> undoFor(String key, String text) {
        Retained retained = byKey.get(key);
        return retained != null && retained.text().equals(text) ? retained.undo() : List.of();
    }

    /** The remembered redo steps for {@code key}, under the same matching rule as {@link #undoFor}. */
    public List<Snapshot> redoFor(String key, String text) {
        Retained retained = byKey.get(key);
        return retained != null && retained.text().equals(text) ? retained.redo() : List.of();
    }

    /** Call on leaving a world/server, for the same reason as {@link UnsavedDraftStore#clear}. */
    public void clear() {
        byKey.clear();
    }
}
