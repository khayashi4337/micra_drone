package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ItemCount;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.List;

/**
 * What one executor call did: the cursor it reached, why it paused (null = it did not), the positions it touched, the
 * items it is short of (MATERIALS_MISSING) or has no room for (NO_ROOM), and the conflicts it newly found.
 */
public record StepReport(int cursor, PauseReason pause, List<IntPos> touched, List<ItemCount> shortage,
                         List<Conflict> conflicts) {
    public StepReport {
        touched = List.copyOf(touched);
        shortage = List.copyOf(shortage);
        conflicts = List.copyOf(conflicts);
    }
}
