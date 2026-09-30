package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.parts.BuildPhase;

/** The placements of one construction phase: indexes {@code fromIndex} (inclusive) to {@code toIndexExclusive}. */
public record PhaseRange(BuildPhase phase, int fromIndex, int toIndexExclusive) {
}
