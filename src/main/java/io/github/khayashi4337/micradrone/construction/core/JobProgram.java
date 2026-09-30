package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.util.ArrayList;
import java.util.List;

/**
 * The ordered work of one job: removals first (top-down, as planned by the rollback or MODIFY planner), then placements
 * (in manifest order). One cursor walks the whole program.
 */
public record JobProgram(List<RestoreItem> restores, List<PutItem> puts) {
    /** Separates the ledger keys of repair rounds: a block re-placed by a repair is charged again, under a new key. */
    public static final int LEDGER_ROUND_STRIDE = Integer.MAX_VALUE / (ConstructionJob.MAX_REPAIR_ROUNDS + 1);

    public JobProgram {
        restores = List.copyOf(restores);
        puts = List.copyOf(puts);
    }

    public static JobProgram build(PlacementManifest m) {
        List<PutItem> puts = new ArrayList<>(m.placements().size());
        for (Placement p : m.placements()) {
            puts.add(new PutItem(p.index(), p.index(), p));
        }
        return new JobProgram(List.of(), puts);
    }

    public static JobProgram repair(PlacementManifest m, List<Integer> indexes, int round) {
        List<PutItem> puts = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            puts.add(new PutItem(i, round * LEDGER_ROUND_STRIDE + i, m.placements().get(i)));
        }
        return new JobProgram(List.of(), puts);
    }

    public int size() {
        return restores.size() + puts.size();
    }

    public boolean isRestore(int i) {
        return i < restores.size();
    }

    public RestoreItem restore(int i) {
        return restores.get(i);
    }

    public PutItem put(int i) {
        return puts.get(i - restores.size());
    }
}
