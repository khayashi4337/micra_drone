package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;

/** What the approval screen must show and have confirmed (04 F-5): destructive replacements and terraforming. */
public record ReplacementSummary(int fluids, int leaves, int emptyContainers, int terrainCut, int terrainFill,
                                 List<IntPos> destructiveSample) {
    /** How many destructive positions the approval screen lists; the counts above are always complete. */
    public static final int SAMPLE_LIMIT = 16;

    public ReplacementSummary {
        destructiveSample = List.copyOf(destructiveSample);
    }

    public boolean needsDestructiveConfirm() {
        return fluids + leaves + emptyContainers > 0;
    }

    public boolean needsTerraformConfirm() {
        return terrainCut + terrainFill > 0;
    }
}
