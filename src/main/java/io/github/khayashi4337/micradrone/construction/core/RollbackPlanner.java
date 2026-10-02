package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A claim's rollback (04 F-14): every block the project placed goes back to its pre-build block, top-down, removing only
 * project blocks (D-25), comparing without the part's volatile states, dropping a container's items first, and returning
 * only the material its placer's ledger recorded.
 */
public final class RollbackPlanner {
    private static final Comparator<IntPos> TOP_DOWN = Comparator.<IntPos>comparingInt(p -> -p.y()).thenComparingInt(IntPos::z)
            .thenComparingInt(IntPos::x);

    private RollbackPlanner() {
    }

    public static List<RestoreItem> plan(PlacedRegistry registry, BiFunction<String, Integer, Set<String>> volatileOfPlacer) {
        List<Map.Entry<IntPos, PlacedEntry>> entries = new ArrayList<>(registry.placed().entrySet());
        entries.sort(Map.Entry.comparingByKey(TOP_DOWN));
        List<RestoreItem> out = new ArrayList<>(entries.size());
        for (Map.Entry<IntPos, PlacedEntry> e : entries) {
            PlacedEntry p = e.getValue();
            out.add(new RestoreItem(e.getKey(), p.placed(), volatileOfPlacer.apply(p.jobId(), p.placementIndex()), p.before(),
                    p.jobId(), p.placementIndex(), true, p.earlierKeys()));
        }
        // stable: ties keep the top-down order above
        out.sort(Attachments.REMOVAL_ORDER);
        return out;
    }
}
