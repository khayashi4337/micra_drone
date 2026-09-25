package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything the construction needs, in world coordinates and in construction order, plus the hash that pins it. */
public record PlacementManifest(int manifestVersion, String planId, int planRevision, String registryVersion, String dimension,
                                BuildFrame frame, Box worldBounds, List<Placement> placements, List<AssemblyStep> assemblies,
                                Map<String, Integer> bom, List<PhaseRange> phases, String hash) {
    public static final int MANIFEST_VERSION = 1;

    public PlacementManifest {
        placements = List.copyOf(placements);
        assemblies = List.copyOf(assemblies);
        bom = Collections.unmodifiableSortedMap(new TreeMap<>(bom));
        phases = List.copyOf(phases);
    }
}
