package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.Conflict;
import io.github.khayashi4337.micradrone.build.compile.ConflictKind;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Splits deviations into what L7 re-places, what it reports as a conflict and leaves alone, and what it cannot fix
 * (design 03, L7): missing blocks and the project's own turned blocks are re-placed; a different block is re-placed only
 * while it is still the untouched pre-build block, otherwise a player may have put it there and it is not overwritten.
 */
public final class RepairPlanner {
    private RepairPlanner() {
    }

    public static RepairPlan plan(PlacementManifest m, List<Deviation> deviations, IntFunction<Optional<BlockSpec>> journaledBefore,
                                  Predicate<IntPos> placedByProject, Set<Integer> denied) {
        List<Integer> reapply = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<Deviation> unfixable = new ArrayList<>();
        for (Deviation d : deviations) {
            IntPos pos = m.placements().get(d.placementIndex()).pos();
            if (denied.contains(d.placementIndex()) || d.kind() == DeviationKind.BLOCKED) {
                unfixable.add(new Deviation(d.placementIndex(), d.expected(), d.observed(), DeviationKind.BLOCKED));
                continue;
            }
            switch (d.kind()) {
                case MISSING -> reapply.add(d.placementIndex());
                case WRONG_STATE -> {
                    if (placedByProject.test(pos)) {
                        reapply.add(d.placementIndex());
                    } else {
                        conflicts.add(new Conflict(pos, d.expected(), d.observed(), ConflictKind.PLAYER_MODIFIED));
                    }
                }
                case WRONG_BLOCK, EXTRA -> {
                    // once the project placed here, anything else at the position was put there by someone: even the
                    // pre-build block (a player may have put the grass back) is theirs now and is never overwritten
                    Optional<BlockSpec> before = journaledBefore.apply(d.placementIndex());
                    if (!placedByProject.test(pos) && before.isPresent()
                            && BlockMatch.satisfies(d.observed().block(), before.get(), Set.of())) {
                        reapply.add(d.placementIndex());
                    } else {
                        conflicts.add(new Conflict(pos, d.expected(), d.observed(), ConflictKind.PLAYER_MODIFIED));
                    }
                }
                default -> unfixable.add(d);
            }
        }
        return new RepairPlan(reapply, conflicts, unfixable);
    }
}
