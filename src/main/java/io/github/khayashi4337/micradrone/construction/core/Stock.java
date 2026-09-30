package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** How many of one item one source holds now ({@code inventory}, or {@code chest:x,y,z} for a supply chest). */
public record Stock(String sourceId, String itemId, int count) {
    public Stock {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(itemId, "itemId");
        if (count < 0) {
            throw new IllegalArgumentException("a stock cannot be negative: " + count);
        }
    }
}
