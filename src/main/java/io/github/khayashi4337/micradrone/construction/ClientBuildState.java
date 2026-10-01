package io.github.khayashi4337.micradrone.construction;

/**
 * The newest offer and progress documents the client received (M2). Written by the S2C payload
 * handlers on the client main thread, read by the build screen (M3). {@code volatile} because the
 * fields may be touched from more than one client-side thread; each document is a self-contained
 * snapshot, so a single reference is enough - readers never need to lock.
 */
public final class ClientBuildState {
    private static volatile String offerJson;
    private static volatile String progressJson;

    private ClientBuildState() {
    }

    public static void offer(String json) {
        offerJson = json;
    }

    public static void progress(String json) {
        progressJson = json;
    }

    /** The last offer document, or null when none arrived this session. */
    public static String offerJson() {
        return offerJson;
    }

    /** The last progress document, or null when none arrived this session. */
    public static String progressJson() {
        return progressJson;
    }

    /** Drops both documents - they carry no world/server identity, so they must not outlive one. */
    public static void clear() {
        offerJson = null;
        progressJson = null;
    }
}
