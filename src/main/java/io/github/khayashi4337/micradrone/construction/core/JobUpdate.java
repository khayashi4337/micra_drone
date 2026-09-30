package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;

/** What one tick did to one job: the positions written, what is short, new conflicts, and whether the state moved. */
public record JobUpdate(ConstructionJob job, List<IntPos> touched, List<ItemCount> shortage,
                        List<Conflict> newConflicts, boolean stateChanged) {
    public JobUpdate {
        touched = List.copyOf(touched);
        shortage = List.copyOf(shortage);
        newConflicts = List.copyOf(newConflicts);
    }
}
