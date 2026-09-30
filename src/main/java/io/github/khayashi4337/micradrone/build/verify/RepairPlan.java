package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import java.util.List;
import java.util.Map;

/**
 * What L7 does with the deviations: re-place these indexes, report these conflicts, and leave these unfixable.
 * {@code reapplyBlocks} overrides the manifest's block for a re-placed index (a repair of a wrong state keeps the
 * world's volatile states instead of resetting them); an index without an entry re-places its manifest block.
 */
public record RepairPlan(List<Integer> reapply, List<Conflict> conflicts, List<Deviation> unfixable,
                         Map<Integer, BlockSpec> reapplyBlocks) {
    public RepairPlan {
        reapply = List.copyOf(reapply);
        conflicts = List.copyOf(conflicts);
        unfixable = List.copyOf(unfixable);
        reapplyBlocks = Map.copyOf(reapplyBlocks);
    }

    public RepairPlan(List<Integer> reapply, List<Conflict> conflicts, List<Deviation> unfixable) {
        this(reapply, conflicts, unfixable, Map.of());
    }
}
