package io.github.khayashi4337.micradrone.build.verify;

import io.github.khayashi4337.micradrone.build.compile.BlockMatch;
import io.github.khayashi4337.micradrone.build.compile.ObservedBlock;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The strict comparison of L7 (design 03): at each shared position the manifest's last placement decides the
 * expected block, and a window answers the position only when that last placement is in scope — a ground cut
 * in an earlier window must not flag the finished foundation as EXTRA. An ASSEMBLED_AWAY
 * placement is checked like any other until its group is assembled; once assembled the position should be air again
 * (its block moved into the contraption), so anything found there is EXTRA. Unread positions are listed apart:
 * unknown is not missing. Volatile states are never compared.
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

    /** No group assembled yet: every ASSEMBLED_AWAY placement is checked as still standing. */
    public static Result compare(PlacementManifest m, SparseSnapshot s, CompareScope scope,
                                 Function<String, Set<String>> volatileOfNode) {
        return compare(m, s, scope, volatileOfNode, Set.of());
    }

    /**
     * {@code assembledGroups}: the assembly groups already built (PlacedRegistry.assemblies). The comparison reads
     * the world once per position, so deviations come out in placement-index order.
     */
    public static Result compare(PlacementManifest m, SparseSnapshot s, CompareScope scope,
                                 Function<String, Set<String>> volatileOfNode, Set<String> assembledGroups) {
        // the last placement per position is chosen from the WHOLE manifest: picking it inside the scope lets
        // an early window take a site-prep cut as the answer and misjudge the later finished block as EXTRA
        Map<IntPos, Placement> last = new HashMap<>();
        for (Placement p : m.placements()) {
            Placement prev = last.get(p.pos());
            if (prev == null || p.index() > prev.index()) {
                last.put(p.pos(), p);
            }
        }
        List<Placement> chosen = new ArrayList<>();
        for (Placement p : last.values()) {
            if (scope.includes(p)) {
                chosen.add(p);
            }
        }
        chosen.sort(Comparator.comparingInt(Placement::index));
        List<Deviation> out = new ArrayList<>();
        List<Integer> unread = new ArrayList<>();
        for (Placement p : chosen) {
            ObservedBlock obs = s.blocks().get(p.pos());
            if (obs == null) {
                unread.add(p.index());
                continue;
            }
            boolean assembled = p.verify() == VerifyMode.ASSEMBLED_AWAY && p.assemblyGroup() != null
                    && assembledGroups.contains(p.assemblyGroup());
            BlockSpec exp = assembled ? BlockSpec.AIR : p.block();
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
