package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.Objects;

/** One placement whose observed block disagrees with the manifest, pinned by the placement's construction index. */
public record Deviation(int placementIndex, BlockSpec expected, ObservedBlock observed, DeviationKind kind) {
    public Deviation {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(kind, "kind");
    }
}
