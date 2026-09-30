package io.github.khayashi4337.micradrone.construction.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One fake world, fake inventories, and who is online, for JobService's tests. */
public final class FakeJobWorld implements JobWorld {
    public final FakeWorld world = new FakeWorld();
    public final Map<UUID, FakeMaterials> inventories = new HashMap<>();
    public final Set<UUID> online = new HashSet<>();
    /** Jobs the adapter would let run while their owner is away (chunks held, an actor available). */
    public final Set<String> mayRunOffline = new HashSet<>();

    @Override
    public WorldPort world(String dimension) {
        return world;
    }

    @Override
    public MaterialPort materials(UUID owner, MaterialPolicy policy, String claimId) {
        return policy == MaterialPolicy.CREATIVE_FREE ? MaterialPort.FREE
                : inventories.computeIfAbsent(owner, k -> new FakeMaterials());
    }

    @Override
    public boolean ownerOnline(UUID owner) {
        return online.contains(owner);
    }

    @Override
    public boolean mayRunWithoutOwner(ConstructionJob job) {
        return mayRunOffline.contains(job.jobId());
    }
}
