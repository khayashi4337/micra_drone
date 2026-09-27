package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.SortedCopies;
import java.util.List;
import java.util.Map;

/** Everything the construction needs, in world coordinates and in construction order, plus the hash that pins it. */
public record PlacementManifest(int manifestVersion, String planId, int planRevision, String registryVersion, String dimension,
                                BuildFrame frame, Box worldBounds, List<Placement> placements, List<AssemblyStep> assemblies,
                                Map<String, Integer> bom, List<PhaseRange> phases, String hash) {
    public static final int MANIFEST_VERSION = 1;

    public PlacementManifest {
        placements = List.copyOf(placements);
        // the index is the construction order: a placement's index must be its position in the list
        for (int i = 0; i < placements.size(); i++) {
            Placement p = placements.get(i);
            if (p.index() != i) {
                throw new IllegalArgumentException("placements[" + i + "] has index " + p.index());
            }
        }
        assemblies = List.copyOf(assemblies);
        bom = SortedCopies.map(bom);
        phases = List.copyOf(phases);
    }
}
