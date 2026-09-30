package io.github.khayashi4337.micradrone.construction.core;

import java.util.Objects;

/** One change to one source: {@code delta} items of {@code itemId} (negative takes from the owner, positive gives). */
public record Move(String sourceId, String itemId, int delta) {
    public Move {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(itemId, "itemId");
        if (delta == 0) {
            throw new IllegalArgumentException("a move of nothing is a bug");
        }
    }
}
