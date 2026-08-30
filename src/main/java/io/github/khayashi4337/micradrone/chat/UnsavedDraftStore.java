package io.github.khayashi4337.micradrone.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * The unsaved-edit cache {@code IdeScreen} consults across a screen close/reopen, so closing the
 * IDE mid-edit doesn't throw a pending edit away - see its own {@code unsavedDrafts} field for the
 * full story. Pulled out into its own Minecraft-free class so the one piece of real logic here (the
 * mid-review guard) is unit-testable; {@code IdeScreen} owns everything Minecraft-specific
 * (the key itself, the network side-effects) around calls to this.
 */
public final class UnsavedDraftStore {
    private final Map<String, String> drafts = new HashMap<>();

    /**
     * Records {@code text} under {@code key}, unless {@code isReviewing} - mid AI-change review the
     * editor shows a merged diff view (red/green markup), not real script text, and that must not
     * resurface as the next draft (see {@code IdeScreen#beginReview}).
     */
    public void record(String key, String text, boolean isReviewing) {
        if (!isReviewing) {
            drafts.put(key, text);
        }
    }

    /** The pending draft for {@code key}, or {@code fallback} (the server's saved copy) if there is none. */
    public String resolve(String key, String fallback) {
        return drafts.getOrDefault(key, fallback);
    }

    /** Call once a save actually lands - the draft and the saved copy agree again, nothing left to protect. */
    public void forget(String key) {
        drafts.remove(key);
    }

    /**
     * Call on leaving a world/server (see {@code ClientPlayerNetworkEvent.LoggingOut}). Keys are
     * controller position + script id only, with no world or server identity in them, so without
     * this a draft from one save could resurface as if it belonged to an unrelated save that
     * happens to reuse the same coordinates and script id (most commonly the built-in
     * "controller" script, which every controller has) - trading the original bug (a real edit
     * lost) for a worse one (a stranger's stale text silently overwriting what the server actually
     * has saved).
     */
    public void clear() {
        drafts.clear();
    }
}
