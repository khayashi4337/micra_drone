package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The strict comparison of L7 (design 03): each placement in scope against the block read at its position, by its
 * VerifyMode, never comparing the part's volatile states. Unread positions are listed apart: unknown is not missing.
 */
public final class SnapshotDiff {
    public record Result(List<Deviation> deviations, List<Integer> unread) {
        public Result {
            deviations = List.copyOf(deviations);
            unread = List.copyOf(unread);
        }
    }

    private SnapshotDiff() {
    }

    public static Result compare(PlacementManifest m, SparseSnapshot s, CompareScope scope,
                                 Function<String, Set<String>> volatileOfNode) {
        List<Deviation> out = new ArrayList<>();
        List<Integer> unread = new ArrayList<>();
        for (Placement p : m.placements()) {
            if (!scope.includes(p) || p.verify() == VerifyMode.ASSEMBLED_AWAY) {
                continue;
            }
            ObservedBlock obs = s.blocks().get(p.pos());
            if (obs == null) {
                unread.add(p.index());
                continue;
            }
            BlockSpec exp = p.block();
            BlockSpec o = obs.block();
            DeviationKind kind = null;
            if (exp.isAir()) {
                kind = o.isAir() ? null : DeviationKind.EXTRA;
            } else if (o.isAir()) {
                kind = DeviationKind.MISSING;
            } else if (!o.blockId().equals(exp.blockId())) {
                kind = DeviationKind.WRONG_BLOCK;
            } else if (!statesMatch(p.verify(), o, exp, volatileOfNode.apply(p.partNodeId()))) {
                kind = DeviationKind.WRONG_STATE;
            }
            if (kind != null) {
                out.add(new Deviation(p.index(), exp, obs, kind));
            }
        }
        return new Result(out, unread);
    }

    /** Design 05, 1.1.1: BLOCK_ONLY looks at the id only, EXACT at every state, STATE_SUBSET at the listed states. */
    public static boolean statesMatch(VerifyMode mode, BlockSpec observed, BlockSpec expected, Set<String> volatileProps) {
        return switch (mode) {
            case BLOCK_ONLY, ASSEMBLED_AWAY -> true;
            case EXACT -> BlockMatch.exact(observed, expected, volatileProps);
            case STATE_SUBSET -> BlockMatch.satisfies(observed, expected, volatileProps);
        };
    }
}
