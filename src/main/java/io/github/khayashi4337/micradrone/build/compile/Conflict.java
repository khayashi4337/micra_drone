package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Objects;

/** One position where the world no longer matches what the manifest expects. */
public record Conflict(IntPos pos, BlockSpec expected, ObservedBlock observed, ConflictKind kind) {
    public Conflict {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(kind, "kind");
    }
}
