package io.github.khayashi4337.micradrone.build.compile;

import io.github.khayashi4337.micradrone.build.compile.gen.Canvas;
import io.github.khayashi4337.micradrone.build.compile.gen.GenAbort;
import io.github.khayashi4337.micradrone.build.compile.gen.GenContext;
import io.github.khayashi4337.micradrone.build.compile.gen.Palette;
import io.github.khayashi4337.micradrone.build.compile.gen.PartGenerators;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockRotation;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.BuildLimits;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamValidator;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandedPlan;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns an expanded plan into a placement manifest, deterministically. The plan is built in the local frame and
 * only mapped to the world at the end, so rotating the site rotates the result and nothing else (D-29).
 * The plan may not have passed the patcher (hand-built or client-derived), so nothing in it is trusted: every
 * problem ends as an Issue, never as an exception or a loop.
 */
public final class PlanCompiler {
    /** The cells one compile places by default: the limit the expander also refuses a bigger plan at (see BuildLimits). */
    public static final int DEFAULT_MAX_CELLS = BuildLimits.MAX_CELLS;

    // Issue keys and subjects.
    private static final String KEY_ROT = "rot";
    private static final String KEY_SITE = "site";
    private static final String KEY_SITE_FRAME = "site.frame";
    private static final String SITE_SUBJECT = "site";
    private static final String KEY_GENERATOR = "generator";
    private static final String DATA_EXCEPTION = "exception";
    private static final String DATA_BLOCK = "block";
    /** Depth of the position above a root (so the root is 0) and of the gap a missing parent leaves (so its child is 1). */
    private static final int ROOT_PARENT_DEPTH = -1;
    private static final int MISSING_PARENT_DEPTH = 0;

    /** Cells in construction order: phase, then bottom to top, then front to back, then left to right. */
    private static final Comparator<LocalPos> VWU = Comparator.comparingInt(LocalPos::v).thenComparingInt(LocalPos::w)
            .thenComparingInt(LocalPos::u);
    private static final Comparator<Canvas.Cell> CONSTRUCTION_ORDER = Comparator
            .<Canvas.Cell>comparingInt(c -> c.phase().ordinal()).thenComparing(Canvas.Cell::pos, VWU);

    private final int maxCells;

    public PlanCompiler() {
        this(DEFAULT_MAX_CELLS);
    }

    public PlanCompiler(int maxCells) {
        this.maxCells = maxCells;
    }

    /** {@code survey} is only recorded in P3; the terraforming of P4 uses it. */
    public CompileResult compile(ExpandedPlan expanded, PartTypeRegistry registry, PlaceableBlockPolicy policy, SurveyRef survey) {
        SemanticPlan plan = expanded.source();
        List<Issue> issues = new ArrayList<>();
        Site site = plan.site();
        if (site == null) {
            issues.add(Issue.of(IssueCode.E_SITE_MISSING, List.of(), "敷地(site)が決まっていません。site(...)で、場所と向きと範囲を決めてください"));
            return new CompileResult(null, issues);
        }
        if (site.frame().origin() == null || site.frame().facing() == null) {
            // BuildFrame does not check its fields; the other nulls (style, site.frame, Absolute.rot) are normalised
            // or refused by their records' constructors.
            issues.add(Issue.of(IssueCode.E_SCHEMA, KEY_SITE_FRAME, List.of(SITE_SUBJECT),
                    "site.frameには、原点(origin)と向き(facing)が必要です"));
            return new CompileResult(null, issues);
        }
        if (!siteFitsTheWorld(site)) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_SITE, List.of(SITE_SUBJECT),
                    "敷地の原点と範囲は、各成分を±" + PlanPatcher.MAX_COORD + "以内にしてください"));
            return new CompileResult(null, issues);
        }
        Map<String, PlanNode> byId = new HashMap<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (PlanNode n : expanded.primitiveNodes()) {
            if (byId.put(n.id(), n) != null) {
                repeated.add(n.id());
            }
        }
        if (!repeated.isEmpty()) {
            for (String id : repeated) {
                issues.add(Issue.of(IssueCode.E_ID_DUPLICATE, List.of(id), "同じIDの部品が複数あります: " + id));
            }
            return new CompileResult(null, issues);
        }
        List<PlanNode> nodes = validated(expanded.primitiveNodes(), registry, issues);
        if (issues.stream().anyMatch(Issue::isError)) {
            return new CompileResult(null, issues);
        }
        for (PlanNode n : nodes) {
            byId.put(n.id(), n);
        }
        Map<String, LocalPos> origins = Origins.resolve(nodes, SlotResolver.NONE, issues);
        Palette palette = new Palette(registry.defaultPalette(), plan.style().palette());
        Canvas canvas = new Canvas(maxCells);
        GenContext ctx = new GenContext(registry, palette, canvas, issues, byId, origins);

        List<PlanNode> ordered = order(nodes, byId);
        try {
            for (PartGenerators.Stage stage : PartGenerators.Stage.values()) {
                for (PlanNode node : ordered) {
                    runNode(ctx, registry, node, stage, issues, false);
                }
                if (stage == PartGenerators.Stage.CARVE) {
                    // the openings only registered claims while they ran; resolve the whole set at once
                    ctx.resolveOpenings();
                }
            }
            for (PlanNode node : ordered) {
                runNode(ctx, registry, node, null, issues, true);
            }
        } catch (GenAbort fatal) {
            issues.add(fatal.issue());
            return new CompileResult(null, issues);
        }
        issues.addAll(canvas.overlapIssues());
        checkBounds(canvas, site, issues);
        checkBlocks(canvas, palette, policy, issues);
        if (issues.stream().anyMatch(Issue::isError)) {
            return new CompileResult(null, issues);
        }
        return new CompileResult(build(plan, site, registry, canvas, byId), issues);
    }

    /**
     * The nodes with their parameters typed and range-checked against the registry, as the patcher does. The plan may
     * not have passed the patcher, and the generators loop over and add up these values: the spec ranges are what keeps
     * every loop short and every sum inside int range. Nodes of unknown parts are kept as they are (E-UNKNOWN-PART later).
     */
    private static List<PlanNode> validated(List<PlanNode> nodes, PartTypeRegistry registry, List<Issue> issues) {
        List<PlanNode> out = new ArrayList<>();
        for (PlanNode n : nodes) {
            PartType type = registry.find(n.type()).orElse(null);
            if (type == null) {
                out.add(n);
                continue;
            }
            ParamValidator.Result checked = ParamValidator.validate(n.id(), type, n.params());
            issues.addAll(checked.issues());
            out.add(new PlanNode(n.id(), n.type(), n.parent(), n.anchor(), checked.typed(), n.tags(), n.label()));
        }
        return out;
    }

    /**
     * Whether every local cell of the site maps to world coordinates without leaving int range: the origin and the
     * bounds are each held to the patcher's position limit, so their sum stays far from overflow.
     */
    private static boolean siteFitsTheWorld(Site site) {
        IntPos o = site.frame().origin();
        Box b = site.localBounds();
        for (int value : new int[]{o.x(), o.y(), o.z(), b.minA(), b.minB(), b.minC(), b.maxA(), b.maxB(), b.maxC()}) {
            if (Math.abs((long) value) > PlanPatcher.MAX_COORD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parents before children; the same depth by id. Deterministic whatever order the nodes arrived in. Package-private
     * so that the ordering rules can be tested without running the generators.
     */
    static List<PlanNode> order(List<PlanNode> nodes, Map<String, PlanNode> byId) {
        Map<String, Integer> depth = depths(nodes, byId);
        List<PlanNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt((PlanNode n) -> depth.get(n.id())).thenComparing(PlanNode::id));
        return sorted;
    }

    /**
     * The number of parent hops from each node up to a root, worked out once per node. A climb goes up until it meets
     * a node whose depth is known (or the root, or a missing parent) and then fills in the nodes it passed from the top
     * down, so a chain of any length costs one step per node in total; nothing recurses. A node whose parent is missing
     * counts the hop into the gap (depth 1). A node in or below a parent loop has no root: the climb is capped at the
     * node count (no chain of distinct nodes is longer), and such nodes get the node count plus one, more than any real
     * depth. Origins reports the loop.
     */
    private static Map<String, Integer> depths(List<PlanNode> nodes, Map<String, PlanNode> byId) {
        int loopDepth = nodes.size() + 1;
        Map<String, Integer> depth = new HashMap<>();
        for (PlanNode start : nodes) {
            List<PlanNode> passed = new ArrayList<>();
            int above;
            PlanNode cur = start;
            while (true) {
                Integer known = depth.get(cur.id());
                if (known != null) {
                    above = known;
                    break;
                }
                if (passed.size() > nodes.size()) {
                    above = loopDepth;
                    break;
                }
                passed.add(cur);
                if (cur.parent() == null) {
                    above = ROOT_PARENT_DEPTH;
                    break;
                }
                PlanNode parent = byId.get(cur.parent());
                if (parent == null) {
                    above = MISSING_PARENT_DEPTH;
                    break;
                }
                cur = parent;
            }
            for (int i = passed.size() - 1; i >= 0; i--) {
                above = Math.min(above + 1, loopDepth);
                depth.put(passed.get(i).id(), above);
            }
        }
        return depth;
    }

    private void runNode(GenContext ctx, PartTypeRegistry registry, PlanNode node, PartGenerators.Stage stage, List<Issue> issues,
                         boolean afterAll) {
        PartGenerators.Entry entry = PartGenerators.find(node.type()).orElse(null);
        PartType type = registry.find(node.type()).orElse(null);
        if (entry == null || type == null) {
            if (stage == PartGenerators.Stage.BASE && !afterAll) {
                issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, List.of(node.id()),
                        node.type() + "は、この版では施工できません(置き方が登録されていません)"));
            }
            return;
        }
        if (!afterAll && entry.stage() != stage) {
            return;
        }
        if (afterAll && ctx.isRefused(node.id())) {
            return; // refused while being generated: it is reported once, and has nothing on the canvas to check
        }
        try {
            // Checked on the part itself, not only on a module instance: a template part can carry its own turn.
            if (!afterAll && node.anchor() instanceof Anchor.Absolute a && !a.rot().equals(Rot.NONE)
                    && BuildingParts.ROTATION_UNSUPPORTED.contains(node.type())) {
                throw ctx.fail(node, IssueCode.E_ANCHOR, KEY_ROT, node.type() + "には回転・鏡像を付けられません(向きは敷地のfacingで決めます)");
            }
            Params params = Params.resolve(type, node.params());
            if (afterAll) {
                entry.generator().afterAll(ctx, node, params);
            } else {
                entry.generator().generate(ctx, node, params);
            }
        } catch (GenAbort abort) {
            if (abort.fatal()) {
                throw abort;
            }
            ctx.markRefused(node.id());
            issues.add(abort.issue());
        } catch (RuntimeException unexpected) {
            ctx.markRefused(node.id());
            // A generator must report problems as GenAbort; anything else (a malformed hand-built parameter, a bug) is
            // turned into an ERROR on the node so that one part cannot take the whole compile down or pass silently.
            issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, KEY_GENERATOR, List.of(node.id()),
                    "generator of " + node.type() + " failed on " + node.id() + ": " + unexpected.getClass().getSimpleName()
                            + ": " + unexpected.getMessage(),
                    Map.of(DATA_EXCEPTION, unexpected.getClass().getName()), List.of()));
        }
    }

    /** One E-OUT-OF-BOUNDS per part that has cells outside the site, with the count and the first such cell. */
    private static void checkBounds(Canvas canvas, Site site, List<Issue> issues) {
        Box b = site.localBounds();
        Map<String, Integer> outside = new TreeMap<>();
        Map<String, LocalPos> first = new TreeMap<>();
        for (Canvas.Cell c : canvas.all()) {
            LocalPos p = c.pos();
            if (!b.contains(p.u(), p.v(), p.w())) {
                outside.merge(c.ownerId(), 1, Integer::sum);
                first.merge(c.ownerId(), p, (x, y) -> VWU.compare(x, y) <= 0 ? x : y);
            }
        }
        for (Map.Entry<String, Integer> e : outside.entrySet()) {
            String where = Canvas.posText(first.get(e.getKey()));
            issues.add(Issue.of(IssueCode.E_OUT_OF_BOUNDS, "", List.of(e.getKey()),
                    e.getKey() + "が、敷地の範囲の外に" + e.getValue() + "マスはみ出しています(最初は " + where + ")",
                    Map.of(Canvas.DATA_COUNT, String.valueOf(e.getValue()), Canvas.DATA_FIRST_POS, where), List.of()));
        }
    }

    /**
     * One E-BLOCK-FORBIDDEN per refused block, naming every part that used it: a material handed out by the palette
     * that the policy refuses, or any placed block that is always forbidden whatever route it came by.
     */
    private static void checkBlocks(Canvas canvas, Palette palette, PlaceableBlockPolicy policy, List<Issue> issues) {
        Map<String, Set<String>> refused = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : palette.usedBy().entrySet()) {
            if (policy.checkMaterial(e.getKey()).isPresent()) {
                refused.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).addAll(e.getValue());
            }
        }
        for (Canvas.Cell c : canvas.all()) {
            if (policy.isAlwaysForbidden(c.block().blockId())) {
                refused.computeIfAbsent(c.block().blockId(), k -> new TreeSet<>()).add(c.ownerId());
            }
        }
        for (Map.Entry<String, Set<String>> e : refused.entrySet()) {
            String reason = policy.checkMaterial(e.getKey()).orElse(e.getKey() + "は置けません");
            issues.add(Issue.of(IssueCode.E_BLOCK_FORBIDDEN, e.getKey(), new ArrayList<>(e.getValue()), reason,
                    Map.of(DATA_BLOCK, e.getKey()), List.of()));
        }
    }

    private PlacementManifest build(SemanticPlan plan, Site site, PartTypeRegistry registry, Canvas canvas, Map<String, PlanNode> byId) {
        List<Canvas.Cell> cells = new ArrayList<>(canvas.all());
        cells.sort(CONSTRUCTION_ORDER);
        BuildFrame frame = site.frame();
        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            Canvas.Cell c = cells.get(i);
            PartType owner = registry.get(byId.get(c.ownerId()).type());
            placements.add(new Placement(i, frame.toWorld(c.pos()), worldBlock(c.block(), frame), c.blockEntity(),
                    c.ownerId(), c.phase(), owner.placer(), c.verify(), ReplacePolicy.REPLACEABLE, null));
        }
        List<PhaseRange> phases = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= placements.size(); i++) {
            if (i == placements.size() || placements.get(i).phase() != placements.get(start).phase()) {
                phases.add(new PhaseRange(placements.get(start).phase(), start, i));
                start = i;
            }
        }
        Map<String, Integer> bom = BomCalculator.bom(placements);
        Box worldBounds = worldBounds(frame, site.localBounds());
        String hash = ManifestJson.computeHash(site.dimension(), registry.version(), worldBounds, placements, List.of(), bom);
        return new PlacementManifest(PlacementManifest.MANIFEST_VERSION, plan.planId(), plan.revision(), registry.version(),
                site.dimension(), frame, worldBounds, placements, List.of(), bom, phases, hash);
    }

    /** A block generated in the local frame, as it is placed in the world: its states turn with the site's facing. */
    static BlockSpec worldBlock(BlockSpec local, BuildFrame frame) {
        return BlockRotation.rotate(local, frame.facing().quarterTurns());
    }

    /** The world box around the eight corners of the local bounds. */
    private static Box worldBounds(BuildFrame frame, Box local) {
        IntPos first = frame.toWorld(new LocalPos(local.minA(), local.minB(), local.minC()));
        Box out = new Box(first.x(), first.y(), first.z(), first.x(), first.y(), first.z());
        for (int u : new int[]{local.minA(), local.maxA()}) {
            for (int v : new int[]{local.minB(), local.maxB()}) {
                for (int w : new int[]{local.minC(), local.maxC()}) {
                    IntPos p = frame.toWorld(new LocalPos(u, v, w));
                    out = new Box(Math.min(out.minA(), p.x()), Math.min(out.minB(), p.y()), Math.min(out.minC(), p.z()),
                            Math.max(out.maxA(), p.x()), Math.max(out.maxB(), p.y()), Math.max(out.maxC(), p.z()));
                }
            }
        }
        return out;
    }
}
