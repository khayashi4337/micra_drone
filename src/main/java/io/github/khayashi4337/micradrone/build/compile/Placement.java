package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.SortedCopies;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PlacerId;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.Map;

/** One block of the manifest, in world coordinates, with how it is placed and checked. */
public record Placement(int index, IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, String partNodeId,
                        BuildPhase phase, PlacerId placer, VerifyMode verify, ReplacePolicy replaces, String assemblyGroup) {
    public Placement {
        blockEntityConfig = SortedCopies.map(blockEntityConfig);
    }
}
