package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/**
 * One position the new manifest no longer places: what was there ({@code old}), what the old manifest
 * expects to find in the world now ({@code expectedNow}), and what to put back ({@code restoreTo}).
 */
public record RemovalEntry(Placement old, BlockSpec expectedNow, BlockSpec restoreTo) {
    public RemovalEntry {
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(expectedNow, "expectedNow");
        Objects.requireNonNull(restoreTo, "restoreTo");
    }
}
