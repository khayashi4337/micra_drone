package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.Map;

/**
 * The positions of the placement list, read before approval. A position without a key could not be read
 * (unloaded); the safety envelope refuses those rather than guessing.
 */
public record PlacementSurvey(Map<IntPos, WorldCell> cells) {
    public PlacementSurvey {
        cells = Map.copyOf(cells);
    }
}
