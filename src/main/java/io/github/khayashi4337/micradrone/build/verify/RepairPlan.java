package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import java.util.List;

/** What L7 does with the deviations: re-place these indexes, report these conflicts, and leave these unfixable. */
public record RepairPlan(List<Integer> reapply, List<Conflict> conflicts, List<Deviation> unfixable) {
    public RepairPlan {
        reapply = List.copyOf(reapply);
        conflicts = List.copyOf(conflicts);
        unfixable = List.copyOf(unfixable);
    }
}
