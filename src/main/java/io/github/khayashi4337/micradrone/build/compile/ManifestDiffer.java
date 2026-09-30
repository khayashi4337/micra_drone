package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Compares an old and a new manifest for a MODIFY job. Nothing here reads the world: the runtime checks each
 * position against {@code expectedNow} right before it touches it (and only for positions it placed).
 */
public final class ManifestDiffer {
    private ManifestDiffer() {
    }

    /**
     * Diffs two manifests of the same dimension. Removals come out top-down (y descending, then z, then x) so
     * nothing falls onto work still in progress; additions and changes follow the new manifest's index order
     * (the order of {@code to.placements()}). {@code restoreLookup} is asked only about removed positions, and
     * a {@code null} answer means {@link BlockSpec#AIR}. Throws {@link IllegalArgumentException} when the two
     * manifests name different dimensions.
     */
    public static ManifestDiff diff(PlacementManifest from, PlacementManifest to, Function<IntPos, BlockSpec> restoreLookup) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(restoreLookup, "restoreLookup");
        if (!from.dimension().equals(to.dimension())) {
            throw new IllegalArgumentException("cannot diff manifests of different dimensions: " + from.dimension() + " and " + to.dimension());
        }
        Map<IntPos, Placement> oldByPos = new HashMap<>();
        for (Placement p : from.placements()) {
            oldByPos.put(p.pos(), p);
        }
        Set<IntPos> newPositions = new HashSet<>();
        for (Placement p : to.placements()) {
            newPositions.add(p.pos());
        }
        List<RemovalEntry> removals = new ArrayList<>();
        for (Placement old : from.placements()) {
            if (!newPositions.contains(old.pos())) {
                BlockSpec restore = restoreLookup.apply(old.pos());
                removals.add(new RemovalEntry(old, old.block(), restore == null ? BlockSpec.AIR : restore));
            }
        }
        // removal order: highest y first so nothing falls onto work still in progress, then z, then x
        removals.sort(Comparator.<RemovalEntry>comparingInt(r -> r.old().pos().y()).reversed()
                .thenComparingInt(r -> r.old().pos().z()).thenComparingInt(r -> r.old().pos().x()));
        List<Placement> additions = new ArrayList<>();
        List<PlacementChange> changes = new ArrayList<>();
        int unchanged = 0;
        for (Placement now : to.placements()) {
            Placement old = oldByPos.get(now.pos());
            if (old == null) {
                additions.add(now);
            } else if (old.block().equals(now.block()) && old.blockEntityConfig().equals(now.blockEntityConfig())) {
                unchanged++;
            } else {
                changes.add(new PlacementChange(old, now, old.block()));
            }
        }
        return new ManifestDiff(from.hash(), to.hash(), removals, additions, changes, unchanged);
    }
}
