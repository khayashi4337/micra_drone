package io.github.khayashi4337.micradrone.construction.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How each owner asked for their newest build (M2b): over the panel's button or over a
 * {@code /micradrone build} command. The panel path hides the command machinery (hashes, job
 * ids, flag names) from the child's chat because the panel's own packets show the same facts.
 * Only the server main thread touches this - every caller is a packet handler, a command or the
 * tick (F-1) - so the map needs no synchronization.
 */
public final class ChatChannel {
    /** The path an owner's newest submission came in on. */
    public enum Channel { COMMAND, PANEL }

    private final Map<UUID, Channel> channels = new HashMap<>();

    /** A submission over the build channel (the panel's button) marks the owner. */
    public void markPanel(UUID owner) {
        channels.put(owner, Channel.PANEL);
    }

    /** A submission over a {@code /micradrone build} command turns the command lines back on. */
    public void markCommand(UUID owner) {
        channels.put(owner, Channel.COMMAND);
    }

    /** True while the owner's newest submission came over the panel. */
    public boolean isPanel(UUID owner) {
        return channels.get(owner) == Channel.PANEL;
    }

    /** Logout drops the mark; a rejoining owner is a command viewer until their next submit. */
    public void forget(UUID owner) {
        channels.remove(owner);
    }
}
