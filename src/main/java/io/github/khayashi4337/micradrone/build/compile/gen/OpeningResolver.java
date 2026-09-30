package io.github.khayashi4337.micradrone.build.compile.gen;

import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.OpeningAdjustment;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.function.BiConsumer;

/**
 * The openings (doors and windows) of one compile, collected as claims and resolved in one batch once every claim is
 * in. A generator only registers its claim — the wall cells it takes (the tunnel), the blocks it puts back (with how
 * each lets a passage or a sight line through), and the emitter that places them — so the outcome cannot depend on the
 * order the generators ran in, hence not on node ids or on the order the nodes were listed.
 * <p>
 * Resolution works on a snapshot: the fill claims already on the canvas plus every accepted tunnel opened at once.
 * A tunnel cell must be a cell of the opening's own wall or of a perpendicular wall of the same building that shares
 * a corner with it. Two openings claiming one cell overlap. A passage claim is met when its tunnel reaches the room
 * behind the wall; a view claim when every glazed cell sees it.
 * <p>
 * When a claim fails because its tunnel ends inside the corner block it shares with a perpendicular wall of the same
 * building and level, the resolver tries one bounded local correction: a window is widened around the corner into a
 * corner window ({@link #RULE_CORNER_RETURN}), a door's way in is dug through the neighbouring wall's inner cells only
 * ({@link #RULE_CORNER_DIG}). A correction never touches a cell of any wall's outer face — a door whose only way in
 * would break a one-thick shell is refused instead — nor a cell that is not a wall cell of the same building, a cell
 * claimed by another opening, or more than the one perpendicular wall the corner belongs to. What the resolver changed
 * is reported as an {@link OpeningAdjustment} and a {@code W-OPENING-ADJUSTED} warning; a claim that no bounded
 * correction can satisfy is refused with {@code E-OPENING-BLOCKED} — the code now means only that, not "the cell is
 * still standing".
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
     * goes back where and what it lets through; {@code emit} places those blocks once the claim is accepted — the
     * second argument names extra cells the resolver's local fix wants a transparent fill on (a corner window's
     * return face; always empty for a passage claim).
     */
    record Claim(PlanNode node, OpeningSpot spot, List<LocalPos> tunnel, Contract contract,
                 Map<LocalPos, Perm> fills, BiConsumer<GenContext, List<LocalPos>> emit) {
    }

    /**
     * The local fix computed for one failing claim: {@code carve} cells come out of the wall in addition to its
     * tunnel, {@code fillAt} cells get the claim's transparent fill (a window's glass on the corner return's face).
     */
    private record Pending(String rule, List<LocalPos> carve, List<LocalPos> fillAt,
                           String reason, String note) {
    }

    /** The Issue key of two openings claiming the same cell, matching the carve reports the old sequence produced. */
    private static final String KEY_CARVE = "carve";
    private static final String KEY_GENERATOR = "generator";
    private static final String DATA_EXCEPTION = "exception";
    /** The correction rule names, as {@link OpeningAdjustment#rule} and in the warning's data. */
    private static final String RULE_CORNER_RETURN = "corner-return";
    private static final String RULE_CORNER_DIG = "corner-dig";
    private static final int RULE_VERSION = 1;
    /** The fix hint of a door that only a one-thick shell would unblock: slide it one cell off the corner. */
    private static final String HINT_MOVE_OPENING = "MOVE_OPENING";
    private static final String HINT_ARG_U = "u";
    /** A carved cell left empty reads as air in the adjustment's {@code after}. */
    private static final String AIR = "minecraft:air";
    /** A corner window's return may run at most this many columns along the neighbouring wall. */
    private static final int MAX_RETURN_COLUMNS = 4;
    /** The six axis steps a flood fill through the opened cells may take (a fixed direction order, not node ids). */
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final List<Claim> claims = new ArrayList<>();
    private final List<OpeningAdjustment> adjustments = new ArrayList<>();
    private boolean resolved;

    void add(Claim claim) {
        claims.add(claim);
    }

    /** The local corrections the last resolution made, in the order the claims arrived. */
    List<OpeningAdjustment> adjustments() {
        return List.copyOf(adjustments);
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

        // A claim that cannot meet its contract gets one bounded local correction (corner window / corner dig);
        // one it cannot be saved by is refused. Refusals can only break another claim's way in, so the round repeats
        // until what is left is stable. Corrections come from the original claim set alone — the extra cells of an
        // adjusted claim are seen by that claim only, so one correction can never call up the next.
        List<Claim> alive = new ArrayList<>();
        for (Claim claim : claims) {
            if (!refused.containsKey(claim)) {
                alive.add(claim);
            }
        }
        Map<Claim, Pending> adjusted = new HashMap<>();
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
                // the claim's own correction joins its view of the snapshot — no other claim sees it, so
                // a correction can never supply another claim's way in
                Pending own = adjusted.get(claim);
                Set<LocalPos> ownCarved = carved;
                Map<LocalPos, Perm> ownFills = fills;
                if (own != null) {
                    ownCarved = new HashSet<>(carved);
                    ownCarved.addAll(own.carve());
                    ownFills = new HashMap<>(fills);
                    for (LocalPos pos : own.fillAt()) {
                        ownFills.put(pos, Perm.TRANSPARENT);
                    }
                }
                if (!satisfied(claim, ownCarved, ownFills, walls, canvas, own)) {
                    failing.add(claim);
                }
            }
            if (failing.isEmpty()) {
                break;
            }
            for (Claim claim : failing) {
                List<WallInfo> walls = prisms.getOrDefault(claim.spot().wall().structure().id(), List.of());
                Pending fix = adjust(claim, carved, fills, walls, canvas);
                if (fix != null) {
                    adjusted.put(claim, fix);
                } else {
                    refused.put(claim, blocked(claim, walls));
                    alive.remove(claim);
                    adjusted.remove(claim);
                }
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

        // The accepted claims: carve the tunnel (and the cells a local fix added), then place what it promised
        // to put back. The correction's record and warning go out after the emitter ran, so a refused material
        // leaves neither cells nor an adjustment behind.
        for (Claim claim : claims) {
            if (refused.containsKey(claim)) {
                continue;
            }
            Pending fix = adjusted.get(claim);
            List<Canvas.Cell> emitted;
            try {
                emitted = ctx.captureEmit(claim.emit(), fix == null ? List.of() : fix.fillAt());
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
            if (fix != null) {
                List<OpeningAdjustment.ChangedCell> changed = new ArrayList<>();
                Map<LocalPos, String> placed = new HashMap<>();
                for (Canvas.Cell cell : emitted) {
                    placed.put(cell.pos(), cell.block().blockId());
                }
                Set<String> touchedWalls = new TreeSet<>();
                for (LocalPos pos : fix.carve()) {
                    Canvas.Cell before = canvas.get(pos);
                    if (before != null) {
                        touchedWalls.add(before.ownerId());
                    }
                    changed.add(new OpeningAdjustment.ChangedCell(pos,
                            before == null ? "" : before.ownerId() + "/" + before.block().blockId(),
                            placed.getOrDefault(pos, AIR)));
                }
                changed.sort(Comparator.comparing(OpeningAdjustment.ChangedCell::pos, VWU));
                adjustments.add(new OpeningAdjustment(claim.node().id(), List.copyOf(touchedWalls), fix.rule(),
                        RULE_VERSION, changed, fix.reason(), fix.note()));
                ctx.issues().add(Issue.of(IssueCode.W_OPENING_ADJUSTED, "", List.of(claim.node().id()), fix.note(),
                        Map.of("rule", fix.rule(), "walls", String.join(",", touchedWalls),
                                Canvas.DATA_COUNT, String.valueOf(changed.size()), Canvas.DATA_FIRST_POS,
                                changed.isEmpty() ? "" : Canvas.posText(changed.get(0).pos())),
                        List.of()));
            }
            Set<LocalPos> carve = new LinkedHashSet<>(claim.tunnel());
            if (fix != null) {
                carve.addAll(fix.carve());
            }
            for (LocalPos pos : carve) {
                canvas.remove(pos);
            }
            for (Canvas.Cell cell : emitted) {
                canvas.put(cell);
            }
        }
    }

    /**
     * Refuses an opening whose tunnel has no way out on the inside (E-OPENING-BLOCKED): no bounded local correction
     * could meet its contract — the fix would have to reach past the corner region it is allowed, or touch a cell that
     * is not the building's own wall (a one-thick outer shell counts as that). A door blocked at a corner gets a hint
     * to slide one cell off the corner.
     */
    private static Issue blocked(Claim claim, List<WallInfo> walls) {
        List<FixHint> hints = List.of();
        if (claim.contract() == Contract.PASSAGE) {
            WallInfo host = claim.spot().wall();
            int iLo = Integer.MAX_VALUE;
            for (LocalPos pos : claim.tunnel()) {
                int along = host.alongAt(pos);
                if (along >= 0) {
                    iLo = Math.min(iLo, along);
                }
            }
            for (WallInfo wall : walls) {
                if (wall.axis().equals(host.axis()) || wall.level() != host.level()) {
                    continue;
                }
                for (LocalPos pos : claim.tunnel()) {
                    int along = host.alongAt(pos);
                    if (wall.contains(pos) && along >= 0) {
                        // the tunnel ends inside this wall's corner block: one cell off the corner would clear it —
                        // toward the wall's middle, away from whichever end of the wall the corner is on
                        hints = List.of(new FixHint(HINT_MOVE_OPENING,
                                Map.of(HINT_ARG_U, String.valueOf(along == 0 ? iLo + 1 : iLo - 1))));
                        break;
                    }
                }
                if (!hints.isEmpty()) {
                    break;
                }
            }
        }
        return Issue.of(IssueCode.E_OPENING_BLOCKED, "", List.of(claim.node().id()),
                claim.node().id() + "の開口部は屋内に通じません(トンネルの先が、壁 " + claim.spot().wall().id()
                        + " に隣接する壁やブロックに塞がれた行き止まりで、角の局所補正では直せません)",
                Map.of(), hints);
    }

    /**
     * The one bounded local correction a failing claim may get: the perpendicular wall of the same building and level
     * whose prism the tunnel reaches into (the corner the opening ends in). A window becomes a corner window around
     * that wall's corner; a door's way in is dug through that wall's inner cells. The candidate that changes the
     * fewest cells wins; ties fall back to the corner position, never to an id.
     */
    private static Pending adjust(Claim claim, Set<LocalPos> carved, Map<LocalPos, Perm> fills,
                                  List<WallInfo> walls, Canvas canvas) {
        WallInfo host = claim.spot().wall();
        List<WallInfo> candidates = new ArrayList<>();
        for (WallInfo wall : walls) {
            if (wall.axis().equals(host.axis()) || wall.level() != host.level() || wall.id().equals(host.id())) {
                continue;
            }
            for (LocalPos pos : claim.tunnel()) {
                if (wall.contains(pos)) {
                    candidates.add(wall);
                    break;
                }
            }
        }
        candidates.sort(Comparator.comparing(w -> w.cell(0, 0, 0), VWU));
        Pending best = null;
        for (WallInfo perp : candidates) {
            Pending fix = claim.contract() == Contract.VIEW
                    ? cornerReturn(claim, perp, carved, fills, walls, canvas)
                    : cornerDig(claim, host, perp, carved, fills, walls, canvas);
            if (fix != null && (best == null || fix.carve().size() < best.carve().size())) {
                best = fix;
            }
        }
        return best;
    }

    /**
     * Turns a window sealed by the corner into a corner window: the return opens the neighbouring wall's whole
     * thickness for the columns next to the corner — the overlap band the host's thickness makes, plus the one column
     * touching the room's face, at most {@link #MAX_RETURN_COLUMNS} — over the rows the original window glazes, and
     * puts the glass on the opening's own side of that wall. The return never grows past the corner region: every cell
     * it takes must be a wall cell of the same building (the corner cells the host shares count), not another
     * opening's claim and not past the neighbouring wall's own span.
     */
    private static Pending cornerReturn(Claim claim, WallInfo perp, Set<LocalPos> carved, Map<LocalPos, Perm> fills,
                                        List<WallInfo> walls, Canvas canvas) {
        WallInfo host = claim.spot().wall();
        // the rows the original window glazes, in absolute v
        TreeSet<Integer> maskRows = new TreeSet<>();
        for (Map.Entry<LocalPos, Perm> e : claim.fills().entrySet()) {
            if (e.getValue() == Perm.TRANSPARENT) {
                maskRows.add(e.getKey().v());
            }
        }
        if (maskRows.isEmpty()) {
            return null; // nothing transparent to carry around the corner: the claim's shape is ambiguous
        }
        // the corner sits at one end of the neighbouring wall's span: the columns of that wall inside the host's prism
        boolean atStart = false;
        boolean atEnd = false;
        for (LocalPos pos : claim.tunnel()) {
            int along = perp.alongAt(pos);
            if (along == 0) {
                atStart = true;
            } else if (along == perp.length() - 1) {
                atEnd = true;
            } else if (along >= 0) {
                return null; // the tunnel meets the wall mid-span — not a corner, do not correct
            }
        }
        if (atStart == atEnd) {
            return null; // touches neither end (no corner) or both (a wall one column long: nothing to extend to)
        }
        int columns = Math.min(Math.min(host.thickness() + 1, MAX_RETURN_COLUMNS), perp.length());
        int faceLayer = claim.spot().outer() ? 0 : perp.thickness() - 1;
        List<LocalPos> carve = new ArrayList<>();
        List<LocalPos> fillAt = new ArrayList<>();
        Set<LocalPos> ownTunnel = new HashSet<>(claim.tunnel());
        for (int k = 0; k < columns; k++) {
            int along = atStart ? k : perp.length() - 1 - k;
            for (int v : maskRows) {
                for (int layer = 0; layer < perp.thickness(); layer++) {
                    canvas.charge();
                    LocalPos pos = perp.cell(along, layer, v - perp.baseV());
                    if (ownTunnel.contains(pos)) {
                        if (layer == faceLayer) {
                            Perm own = claim.fills().get(pos);
                            if (own != null && own != Perm.TRANSPARENT) {
                                return null; // the return's face would overwrite the claim's own opaque fill
                            }
                            if (own == null) {
                                fillAt.add(pos); // already ours, but not glazed yet: put the glass down after all
                            }
                        }
                        continue;
                    }
                    if (carved.contains(pos)) {
                        if (layer == faceLayer) {
                            return null; // another opening owns the face cell the return would glaze
                        }
                        continue;
                    }
                    Canvas.Cell cell = canvas.get(pos);
                    if (cell == null || !(cell.ownerId().equals(perp.id())
                            || (perp.cornerGroup().equals(cell.mergeGroup()) && !perp.axis().equals(cell.mergeVariant())))) {
                        return null; // not a wall cell of this building: pillar, beam, another part — untouchable
                    }
                    carve.add(pos);
                    if (layer == faceLayer) {
                        fillAt.add(pos);
                    }
                }
            }
        }
        if (carve.isEmpty() && fillAt.isEmpty()) {
            return null; // the return adds nothing the claim did not already have
        }
        Set<LocalPos> carved2 = new HashSet<>(carved);
        carved2.addAll(carve);
        Map<LocalPos, Perm> fills2 = new HashMap<>(fills);
        for (LocalPos pos : fillAt) {
            fills2.put(pos, Perm.TRANSPARENT);
        }
        Pending fix = new Pending(RULE_CORNER_RETURN, List.copyOf(carve), List.copyOf(fillAt),
                "view blocked by " + perp.id() + " at the corner",
                "窓のすぐ後ろに角の壁(" + perp.id() + ")があったので、" + jaSide(perp.side()) + "側の" + columns
                        + "列もガラスにして角窓にしました。");
        return satisfied(claim, carved2, fills2, walls, canvas, fix) ? fix : null;
    }

    /**
     * Digs a door's way in through the corner block: cells of the host's and the one perpendicular wall's prisms that
     * are not on any wall's outer face, on the tunnel's own rows, inside the corner region (host thickness + 1 cells
     * out from the tunnel). The path found removes the fewest cells it can; a way in that would take a cell of a third
     * wall — or of either wall's outer face — is no way in.
     */
    private static Pending cornerDig(Claim claim, WallInfo host, WallInfo perp, Set<LocalPos> carved,
                                     Map<LocalPos, Perm> fills, List<WallInfo> walls, Canvas canvas) {
        StructureInfo st = host.structure();
        LocalPos o = st.origin();
        int vLo = Integer.MAX_VALUE;
        int vHi = Integer.MIN_VALUE;
        int uLo = Integer.MAX_VALUE;
        int uHi = Integer.MIN_VALUE;
        int wLo = Integer.MAX_VALUE;
        int wHi = Integer.MIN_VALUE;
        for (LocalPos pos : claim.tunnel()) {
            vLo = Math.min(vLo, pos.v());
            vHi = Math.max(vHi, pos.v());
            uLo = Math.min(uLo, pos.u());
            uHi = Math.max(uHi, pos.u());
            wLo = Math.min(wLo, pos.w());
            wHi = Math.max(wHi, pos.w());
        }
        int reach = host.thickness() + 1;
        // the corner region to probe: the tunnel's footprint, widened by the bound on the ground plane; never taller
        Set<LocalPos> diggable = new HashSet<>();
        for (int u = Math.max(o.u(), uLo - reach); u <= Math.min(o.u() + st.width() - 1, uHi + reach); u++) {
            for (int w = Math.max(o.w(), wLo - reach); w <= Math.min(o.w() + st.depth() - 1, wHi + reach); w++) {
                for (int v = vLo; v <= vHi; v++) {
                    LocalPos pos = new LocalPos(u, v, w);
                    canvas.charge();
                    if (carved.contains(pos)) {
                        continue;
                    }
                    Canvas.Cell cell = canvas.get(pos);
                    if (cell == null || !(host.contains(pos) || perp.contains(pos))) {
                        continue;
                    }
                    boolean inner = true;
                    for (WallInfo wall : walls) {
                        int layer = wall.layerAt(pos);
                        if (layer < 0) {
                            continue;
                        }
                        if (!wall.id().equals(host.id()) && !wall.id().equals(perp.id())) {
                            inner = false;
                            break; // a third wall covers the cell: a second corner, out of bounds
                        }
                        if (layer == 0) {
                            inner = false;
                            break; // an outer face cell: digging it would break the shell
                        }
                    }
                    if (!inner || !isWallCell(cell, walls)) {
                        continue;
                    }
                    diggable.add(pos);
                }
            }
        }
        List<LocalPos> path = digPath(claim.tunnel(), diggable, carved, fills, walls, canvas, claim.contract(),
                o.u(), o.u() + st.width() - 1, vLo, vHi, o.w(), o.w() + st.depth() - 1);
        if (path == null) {
            return null;
        }
        // the path's columns, over the tunnel's whole height: the corridor must be walkable, not one cell tall.
        // The rows never grow past the tunnel's own — the fix does not spread up or down.
        Set<LocalPos> dug = new LinkedHashSet<>();
        for (LocalPos p : path) {
            for (int v = vLo; v <= vHi; v++) {
                LocalPos column = new LocalPos(p.u(), v, p.w());
                if (diggable.contains(column)) {
                    dug.add(column);
                }
            }
        }
        List<LocalPos> dig = new ArrayList<>(dug);
        dig.sort(VWU);
        Set<LocalPos> carved2 = new HashSet<>(carved);
        carved2.addAll(dig);
        Pending fix = new Pending(RULE_CORNER_DIG, List.copyOf(dig), List.of(),
                "passage blocked at the " + host.id() + "/" + perp.id() + " corner",
                "扉の先を角(" + host.id() + "と" + perp.id() + ")の壁がふさいでいたので、角まわりの内側を" + dig.size()
                        + "マス掘って通れるようにしました。");
        return satisfied(claim, carved2, fills, walls, canvas, fix) ? fix : null;
    }

    /**
     * The cheapest way in for a passage claim: a flood from the tunnel through open cells (cost nothing) and diggable
     * cells (cost one each), inside the building's footprint and the tunnel's rows. Returns the diggable cells on the
     * first cheapest path found, or {@code null} when the room behind the wall cannot be reached.
     */
    private static List<LocalPos> digPath(List<LocalPos> tunnel, Set<LocalPos> diggable, Set<LocalPos> carved,
                                          Map<LocalPos, Perm> fills, List<WallInfo> walls, Canvas canvas,
                                          Contract contract, int uLo, int uHi, int vLo, int vHi, int wLo, int wHi) {
        Map<LocalPos, Integer> cost = new HashMap<>();
        Map<LocalPos, LocalPos> parent = new HashMap<>();
        Deque<LocalPos> fringe = new ArrayDeque<>();
        for (LocalPos start : tunnel) {
            cost.put(start, 0);
            fringe.add(start);
        }
        LocalPos goal = null;
        while (!fringe.isEmpty() && goal == null) {
            LocalPos p = fringe.poll();
            int here = cost.get(p);
            for (int[] step : NEIGHBOURS) {
                LocalPos q = p.plus(step[0], step[1], step[2]);
                if (q.u() < uLo || q.u() > uHi || q.w() < wLo || q.w() > wHi || q.v() < vLo || q.v() > vHi) {
                    continue;
                }
                boolean dig = diggable.contains(q);
                if (!dig && !open(q, carved, fills, canvas, contract)) {
                    continue;
                }
                int next = here + (dig ? 1 : 0);
                Integer known = cost.get(q);
                if (known != null && known <= next) {
                    continue;
                }
                cost.put(q, next);
                parent.put(q, p);
                if (dig) {
                    fringe.addLast(q);
                } else {
                    fringe.addFirst(q);
                }
                boolean inWall = false;
                for (WallInfo wall : walls) {
                    if (wall.contains(q)) {
                        inWall = true;
                        break;
                    }
                }
                if (!inWall) {
                    goal = q;
                    break;
                }
            }
        }
        if (goal == null) {
            return null;
        }
        List<LocalPos> dig = new ArrayList<>();
        for (LocalPos p = goal; p != null; p = parent.get(p)) {
            if (diggable.contains(p)) {
                dig.add(p);
            }
        }
        Collections.reverse(dig);
        return dig;
    }

    /**
     * Whether the claim's contract is met on the snapshot: for a passage the tunnel as a whole must reach a cell of
     * the room behind the wall; for a view every glazed cell (the claim's own fills plus a fix's return face) must
     * reach one. {@code fix} is the claim's own correction: its cells are visible to this claim only.
     */
    private static boolean satisfied(Claim claim, Set<LocalPos> carved, Map<LocalPos, Perm> fills,
                                     List<WallInfo> walls, Canvas canvas, Pending fix) {
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
            Set<LocalPos> glazed = new HashSet<>();
            for (Map.Entry<LocalPos, Perm> e : claim.fills().entrySet()) {
                if (e.getValue() == Perm.TRANSPARENT) {
                    glazed.add(e.getKey());
                }
            }
            if (fix != null) {
                glazed.addAll(fix.fillAt());
            }
            for (LocalPos pos : glazed) {
                if (!reaches(List.of(pos), walls, carved, fills, canvas, claim.contract(), uLo, uHi, vLo, vHi, wLo, wHi)) {
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

    /** Whether the canvas cell belongs to a wall of the building (a shared corner cell belongs to the wall that took it). */
    private static boolean isWallCell(Canvas.Cell cell, List<WallInfo> walls) {
        for (WallInfo wall : walls) {
            if (wall.id().equals(cell.ownerId())) {
                return true;
            }
        }
        return false;
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

    /** The side a wall faces, in the child's words. */
    private static String jaSide(io.github.khayashi4337.micradrone.build.model.Facing side) {
        return switch (side) {
            case NORTH -> "北";
            case EAST -> "東";
            case SOUTH -> "南";
            case WEST -> "西";
            default -> side.lower();
        };
    }

    /** Cells in construction order: bottom to top, front to back, left to right. */
    private static final Comparator<LocalPos> VWU = Comparator.comparingInt(LocalPos::v)
            .thenComparingInt(LocalPos::w).thenComparingInt(LocalPos::u);

    private static Claim find(Map<Claim, Claim> root, Claim claim) {
        Claim r = root.get(claim);
        return r == claim ? claim : find(root, r);
    }

    private static void union(Map<Claim, Claim> root, Claim a, Claim b) {
        root.put(find(root, a), find(root, b));
    }
}
