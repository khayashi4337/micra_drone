package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.chat.RegionSelectionState;

/**
 * The one pending world-region selection for this client. RegionPointerListener writes it,
 * RegionSelectionRenderer draws it, and IdeChatPanel consumes it when the Chat tab opens.
 */
public final class RegionSelectionHolder {
    public static final RegionSelectionState PENDING = new RegionSelectionState();

    private RegionSelectionHolder() {
    }
}
