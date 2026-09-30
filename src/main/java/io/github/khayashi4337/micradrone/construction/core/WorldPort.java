package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The world as the pure core sees it. The adapter implements it on the server's main thread only (F-1): reads, the
 * placement with the permission event of the actor (S-9), and a restore with the break event that first drops the items
 * of a container the project placed (D-25).
 */
public interface WorldPort {
    WorldCell read(IntPos pos);

    PlaceResult place(IntPos pos, BlockSpec block, Map<String, String> blockEntityConfig, UUID actor);

    /**
     * Puts {@code block} back (rollback, MODIFY removals) after the actor's break event allows it. With
     * {@code dropContentsFirst}, a container's items are dropped into the world first (D-25); a refused break drops nothing.
     */
    PlaceResult restore(IntPos pos, BlockSpec block, UUID actor, boolean dropContentsFirst);

    /**
     * Runs the neighbour updates a restore skipped (a restore changes one position without shape updates, so a door's
     * other half or a sign's wall cannot pop off with an item drop mid-way). Called once a piece is fully restored.
     */
    void settle(List<IntPos> positions);
}
