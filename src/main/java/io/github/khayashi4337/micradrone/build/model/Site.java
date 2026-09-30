package io.github.khayashi4337.micradrone.build.model;

import java.util.Objects;

/**
 * Where the plan is built. The dimension is part of the manifest hash (D-27). {@code localBounds} is the
 * allowed region in local coordinates; every generated cell must lie inside it.
 */
public record Site(String dimension, BuildFrame frame, Box localBounds, String terrainDigest, String claimId) {
    public Site {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(localBounds, "localBounds");
        terrainDigest = Objects.requireNonNullElse(terrainDigest, "");
        claimId = Objects.requireNonNullElse(claimId, "");
    }
}
