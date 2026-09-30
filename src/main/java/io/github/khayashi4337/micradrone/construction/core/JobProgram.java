package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // one ledger key settles one placement: a shared key would charge or return the same step twice (04 F-7)
        Set<Integer> keys = new HashSet<>();
        for (PutItem p : puts) {
            if (!keys.add(p.ledgerKey())) {
                throw new IllegalArgumentException("two placements share ledger key " + p.ledgerKey());
            }
        }
    }

    public static JobProgram build(PlacementManifest m) {
        List<PutItem> puts = new ArrayList<>(m.placements().size());
        for (Placement p : m.placements()) {
            puts.add(new PutItem(p.index(), p.index(), p));
        }
        return new JobProgram(List.of(), puts);
    }

    public static JobProgram repair(PlacementManifest m, List<Integer> indexes, int round) {
        // round 0 would charge under the build's own keys, a round past the maximum is not a repair (ConstructionJob)
        if (round < 1 || round > ConstructionJob.MAX_REPAIR_ROUNDS) {
            throw new IllegalArgumentException("repair round " + round + " must lie in 1.."
                    + ConstructionJob.MAX_REPAIR_ROUNDS);
        }
        Set<Integer> seen = new HashSet<>();
        List<PutItem> puts = new ArrayList<>(indexes.size());
        for (int i : indexes) {
            if (i < 0 || i >= m.placements().size()) {
                throw new IllegalArgumentException("repair index " + i + " outside the manifest's 0.."
                        + (m.placements().size() - 1));
            }
            if (!seen.add(i)) {
                throw new IllegalArgumentException("repair index " + i + " listed twice: one index is repaired once");
            }
            // exact arithmetic: a key past the int range must refuse, never wrap into another key's space
            int key = Math.addExact(Math.multiplyExact(round, LEDGER_ROUND_STRIDE), i);
            puts.add(new PutItem(i, key, m.placements().get(i)));
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
