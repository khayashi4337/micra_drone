package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.chat.RegionSelectionState;

/** The one pending world-region selection for this client. RegionPointerListener writes it; ChatTabPanel consumes it. */
public final class RegionSelectionHolder {
    public static final RegionSelectionState PENDING = new RegionSelectionState();

    private RegionSelectionHolder() {
    }
}
