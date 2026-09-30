package io.github.khayashi4337.micradrone.construction.core;

import java.util.UUID;

/**
 * What the job service needs from the server, per tick: the world of a dimension, an owner's materials, who is online,
 * and whether a job may go on while its owner is away (its chunks held, an actor to place with: F-13).
 */
public interface JobWorld {
    WorldPort world(String dimension);

    MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId);

    boolean ownerOnline(UUID owner);

    /**
     * Whether this job may run while its owner is offline. The adapter answers only after checking the claim's chunks
     * are held and an actor (a FakePlayer) can be made; the setting alone is not enough.
     */
    boolean mayRunWithoutOwner(ConstructionJob job);
}
