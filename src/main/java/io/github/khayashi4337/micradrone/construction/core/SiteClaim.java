package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import java.util.Objects;
import java.util.UUID;

/** A reserved site (design 01, section 8). Kept while the factory exists (D-23); a job's end never releases it. */
public record SiteClaim(int schemaVersion, String claimId, UUID ownerUuid, String dimension, Box worldBox, Box operatingBox,
                        boolean released, long createdTick) {
    public static final int SCHEMA_VERSION = 1;

    public SiteClaim {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(worldBox, "worldBox");
        Objects.requireNonNull(operatingBox, "operatingBox");
    }

    public SiteClaim release() {
        return new SiteClaim(schemaVersion, claimId, ownerUuid, dimension, worldBox, operatingBox, true, createdTick);
    }
}
