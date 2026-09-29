package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The openings (doors and windows) of one compile, collected as claims and resolved in one batch once every claim is
 * in. A generator only registers its claim — the wall cells it takes (the tunnel), the blocks it puts back (with how
 * each lets a passage or a sight line through), and the emitter that places them — so the outcome cannot depend on the
 * order the generators ran in, hence not on node ids or on the order the nodes were listed.
 * <p>
 * Resolution works on a snapshot: the fill claims already on the canvas plus every accepted tunnel opened at once.
 * A tunnel cell must be a cell of the opening's own wall or of a perpendicular wall of the same building that shares
 * a corner with it. Two openings claiming one cell overlap. A passage claim is met when its tunnel reaches the room
 * behind the wall; a view claim when every glazed cell sees it. Claims that fail are refused together; accepted claims
 * are then carved and filled — the emitters run in the order the claims arrived, which cannot change the outcome.
 */
final class OpeningResolver {
    /** How an opening connects to the room behind its wall. */
    enum Contract {
        /** A door: the tunnel as a whole must let a walker through. */
        PASSAGE,
        /** A window: every glazed cell of it must see the room. */
        VIEW
    }

    /** How a block an opening puts back lets a contract pass through it. */
    enum Perm {
        OPAQUE, TRANSPARENT, PASSABLE
    }

    /**
     * One opening's claim. {@code tunnel} is every cell the opening takes out of the wall; {@code fills} says what
     * goes back where and what it lets through; {@code emit} places those blocks once the claim is accepted.
     */
    record Claim(PlanNode node, OpeningSpot spot, List<LocalPos> tunnel, Contract contract,
                 Map<LocalPos, Perm> fills, Consumer<GenContext> emit) {
    }

    /** The Issue key of two openings claiming the same cell, matching the carve reports the old sequence produced. */
    private static final String KEY_CARVE = "carve";
    private static final String KEY_GENERATOR = "generator";
    private static final String DATA_EXCEPTION = "exception";
    /** The six axis steps a flood fill through the opened cells may take (a fixed direction order, not node ids). */
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final List<Claim> claims = new ArrayList<>();
    private boolean resolved;

    void add(Claim claim) {
        claims.add(claim);
    }

    /**
     * Resolves every collected claim against the claims of the other openings. Idempotent: a second run decides
     * nothing twice.
     */
    void resolve(GenContext ctx) {
        if (resolved) {
            return;
        }
        resolved = true;
        if (claims.isEmpty()) {
            return;
        }
        Canvas canvas = ctx.canvas();
        Map<String, List<WallInfo>> prisms = wallPrisms(ctx);
        Map<Claim, Issue> refused = new LinkedHashMap<>();

        // A tunnel cell must be a cell of the opening's own wall, or of a perpendicular wall of the same building
        // that shares a corner with it. A refused claim takes no cell, so only surviving claims overlap later.
        Map<Claim, LocalPos> firstBad = new HashMap<>();
        Map<Claim, Integer> notWall = new HashMap<>();
        for (Claim claim : claims) {
            WallInfo host = claim.spot().wall();
            for (LocalPos pos : new LinkedHashSet<>(claim.tunnel())) {
                canvas.charge();
                Canvas.Cell cell = canvas.get(pos);
                boolean ownWall = cell != null && (cell.ownerId().equals(host.id())
                        || (host.cornerGroup().equals(cell.mergeGroup()) && !host.axis().equals(cell.mergeVariant())));
                if (!ownWall) {
                    notWall.merge(claim, 1, Integer::sum);
                    firstBad.putIfAbsent(claim, pos);
                }
            }
            if (notWall.containsKey(claim)) {
                refused.put(claim, Issue.of(IssueCode.E_OPENING_NO_WALL, "", List.of(claim.node().id()),
                        claim.node().id() + "の開口部が、壁(" + host.id() + ")の外にはみ出しています(壁でないマスが" + notWall.get(claim)
                                + "個。最初は " + Canvas.posText(firstBad.get(claim)) + ")",
                        Map.of(Canvas.DATA_COUNT, String.valueOf(notWall.get(claim))), List.of()));
            }
        }

        // Two openings that claim the same cell overlap; each connected group of them is one issue naming all of them.
        Map<LocalPos, List<Claim>> claimedBy = new HashMap<>();
        for (Claim claim : claims) {
            if (refused.containsKey(claim)) {
                continue;
            }
            for (LocalPos pos : new LinkedHashSet<>(claim.tunnel())) {
                claimedBy.computeIfAbsent(pos, k -> new ArrayList<>()).add(claim);
            }
        }
        Map<Claim, Claim> root = new HashMap<>();
        for (Claim claim : claims) {
            root.put(claim, claim);
        }
        for (List<Claim> claimants : claimedBy.values()) {
            for (int k = 1; k < claimants.size(); k++) {
                union(root, claimants.get(0), claimants.get(k));
            }
        }
        Map<Claim, List<Claim>> groups = new LinkedHashMap<>();
        for (Claim claim : claims) {
            if (!refused.containsKey(claim)) {
                groups.computeIfAbsent(find(root, claim), k -> new ArrayList<>()).add(claim);
            }
        }
        for (List<Claim> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Set<String> involved = new TreeSet<>();
            for (Claim claim : group) {
                involved.add(claim.node().id());
            }
            int overlapping = 0;
            LocalPos firstOverlap = null;
            for (Map.Entry<LocalPos, List<Claim>> e : claimedBy.entrySet()) {
                int inGroup = 0;
                for (Claim claim : e.getValue()) {
                    if (group.contains(claim)) {
                        inGroup++;
                    }
                }
                if (inGroup >= 2) {
                    overlapping += inGroup - 1;
                    if (firstOverlap == null || VWU.compare(e.getKey(), firstOverlap) < 0) {
                        firstOverlap = e.getKey();
                    }
                }
            }
            Issue issue = Issue.of(IssueCode.E_OVERLAP, KEY_CARVE, List.copyOf(involved),
                    String.join(",", involved) + "の開口部が重なっています(" + overlapping + "マス。最初は "
                            + Canvas.posText(firstOverlap) + ")",
                    Map.of(Canvas.DATA_COUNT, String.valueOf(overlapping), Canvas.DATA_FIRST_POS,
                            Canvas.posText(firstOverlap)),
                    List.of());
            for (Claim claim : group) {
                refused.put(claim, issue);
            }
        }

        // A claim that cannot meet its contract is refused; removing it can only break another claim's way in, so the
        // round is repeated until what is left is stable.
        List<Claim> alive = new ArrayList<>();
        for (Claim claim : claims) {
            if (!refused.containsKey(claim)) {
                alive.add(claim);
            }
        }
        while (true) {
            Set<LocalPos> carved = new HashSet<>();
            Map<LocalPos, Perm> fills = new HashMap<>();
            for (Claim claim : alive) {
                carved.addAll(claim.tunnel());
                fills.putAll(claim.fills());
            }
            List<Claim> failing = new ArrayList<>();
            for (Claim claim : alive) {
                List<WallInfo> walls = prisms.getOrDefault(claim.spot().wall().structure().id(), List.of());
                if (!satisfied(claim, carved, fills, walls, canvas)) {
                    failing.add(claim);
                }
            }
            if (failing.isEmpty()) {
                break;
            }
            for (Claim claim : failing) {
                refused.put(claim, blocked(claim));
                alive.remove(claim);
            }
        }

        // Refusals are reported in the order the claims arrived, and a shared overlap is reported once.
        Set<Issue> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Claim claim : claims) {
            Issue refusal = refused.get(claim);
            if (refusal != null) {
                ctx.markRefused(claim.node().id());
                if (reported.add(refusal)) {
                    ctx.issues().add(refusal);
                }
            }
        }

        // The accepted claims: carve the tunnel, then place what it promised to put back.
        for (Claim claim : claims) {
            if (refused.containsKey(claim)) {
                continue;
            }
            List<Canvas.Cell> emitted;
            try {
                emitted = ctx.captureEmit(claim.emit());
            } catch (GenAbort abort) {
                if (abort.fatal()) {
                    throw abort;
                }
                ctx.markRefused(claim.node().id());
                ctx.issues().add(abort.issue());
                continue;
            } catch (RuntimeException unexpected) {
                ctx.markRefused(claim.node().id());
                // the same report the compiler makes when a generator misbehaves on a node
                ctx.issues().add(Issue.of(IssueCode.E_UNKNOWN_PART, KEY_GENERATOR, List.of(claim.node().id()),
                        "generator of " + claim.node().type() + " failed on " + claim.node().id() + ": "
                                + unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage(),
                        Map.of(DATA_EXCEPTION, unexpected.getClass().getName()), List.of()));
                continue;
            }
            for (LocalPos pos : new LinkedHashSet<>(claim.tunnel())) {
                canvas.remove(pos);
            }
            for (Canvas.Cell cell : emitted) {
                canvas.put(cell);
            }
        }
    }

    /**
     * Refuses an opening whose tunnel has no way out on the inside (E-OPENING-BLOCKED): the cells the opening would
     * open end against another wall or a standing block on every path.
     */
    private static Issue blocked(Claim claim) {
        return Issue.of(IssueCode.E_OPENING_BLOCKED, "", List.of(claim.node().id()),
                claim.node().id() + "の開口部は屋内に通じません(トンネルの先が、壁 " + claim.spot().wall().id()
                        + " に隣接する壁やブロックに塞がれた行き止まりです)");
    }

    /**
     * Whether the claim's contract is met on the snapshot: for a passage the tunnel as a whole must reach a cell of
     * the room behind the wall; for a view every glazed cell must reach one.
     */
    private static boolean satisfied(Claim claim, Set<LocalPos> carved, Map<LocalPos, Perm> fills, List<WallInfo> walls,
                                     Canvas canvas) {
        StructureInfo st = claim.spot().wall().structure();
        LocalPos o = st.origin();
        int uLo = o.u();
        int uHi = o.u() + st.width() - 1;
        int wLo = o.w();
        int wHi = o.w() + st.depth() - 1;
        int vLo = Integer.MAX_VALUE;
        int vHi = Integer.MIN_VALUE;
        for (LocalPos p : claim.tunnel()) {
            vLo = Math.min(vLo, p.v());
            vHi = Math.max(vHi, p.v());
        }
        if (claim.contract() == Contract.VIEW) {
            for (Map.Entry<LocalPos, Perm> e : claim.fills().entrySet()) {
                if (e.getValue() == Perm.TRANSPARENT
                        && !reaches(List.of(e.getKey()), walls, carved, fills, canvas, claim.contract(),
                                uLo, uHi, vLo, vHi, wLo, wHi)) {
                    return false;
                }
            }
            return true;
        }
        return reaches(claim.tunnel(), walls, carved, fills, canvas, claim.contract(), uLo, uHi, vLo, vHi, wLo, wHi);
    }

    /**
     * A flood fill from the start cells through cells the contract can pass; it succeeds on reaching a cell inside
     * the building's footprint that no wall of the building covers — the room behind the wall. The search stays inside
     * the footprint and inside the tunnel's own rows, so it cannot slip out under a storey or past the building's
     * edge.
     */
    private static boolean reaches(Collection<LocalPos> starts, List<WallInfo> walls, Set<LocalPos> carved,
                                   Map<LocalPos, Perm> fills, Canvas canvas, Contract contract, int uLo, int uHi,
                                   int vLo, int vHi, int wLo, int wHi) {
        Deque<LocalPos> fringe = new ArrayDeque<>(starts);
        Set<LocalPos> seen = new HashSet<>(starts);
        while (!fringe.isEmpty()) {
            LocalPos p = fringe.poll();
            for (int[] step : NEIGHBOURS) {
                LocalPos q = p.plus(step[0], step[1], step[2]);
                if (q.u() < uLo || q.u() > uHi || q.w() < wLo || q.w() > wHi || q.v() < vLo || q.v() > vHi
                        || !seen.add(q) || !open(q, carved, fills, canvas, contract)) {
                    continue;
                }
                boolean inWall = false;
                for (WallInfo prism : walls) {
                    if (prism.contains(q)) {
                        inWall = true;
                        break;
                    }
                }
                if (inWall) {
                    fringe.add(q); // a cell opened by a claim: the way continues there
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the contract can pass through the cell on the snapshot. */
    private static boolean open(LocalPos pos, Set<LocalPos> carved, Map<LocalPos, Perm> fills, Canvas canvas,
                                Contract contract) {
        Perm fill = fills.get(pos);
        if (fill != null) {
            return contract == Contract.VIEW ? fill == Perm.TRANSPARENT : fill == Perm.PASSABLE;
        }
        if (carved.contains(pos)) {
            return true; // a tunnel cell with nothing put back: plain air
        }
        return canvas.get(pos) == null;
    }

    /** The wall prisms of every building, from the plan — never from the mutable canvas. */
    private static Map<String, List<WallInfo>> wallPrisms(GenContext ctx) {
        Map<String, List<WallInfo>> prisms = new HashMap<>();
        for (PlanNode n : ctx.nodes().values()) {
            if (BuildingParts.WALL.equals(n.type())) {
                ctx.wallInfo(n.id()).ifPresent(w ->
                        prisms.computeIfAbsent(w.structure().id(), k -> new ArrayList<>()).add(w));
            }
        }
        return prisms;
    }

    /** Cells in construction order: bottom to top, front to back, left to right. */
    private static final java.util.Comparator<LocalPos> VWU = java.util.Comparator.comparingInt(LocalPos::v)
            .thenComparingInt(LocalPos::w).thenComparingInt(LocalPos::u);

    private static Claim find(Map<Claim, Claim> root, Claim claim) {
        Claim r = root.get(claim);
        return r == claim ? claim : find(root, r);
    }

    private static void union(Map<Claim, Claim> root, Claim a, Claim b) {
        root.put(find(root, a), find(root, b));
    }
}
