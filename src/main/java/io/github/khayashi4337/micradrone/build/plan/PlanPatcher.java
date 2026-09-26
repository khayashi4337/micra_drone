package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.FixHint;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamValidator;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Applies a {@link PlanPatch} to a {@link SemanticPlan}, deterministically. Operations apply in order; any ERROR
 * refuses the whole patch (no half-applied plans). Everything that arrives from outside (AI output, hand-written
 * JSON, scripts) passes through here, so the checks here are the first line of defence.
 */
public final class PlanPatcher {
    /** Positions stay far inside int range so sums along a parent chain cannot wrap around. */
    public static final int MAX_COORD = 30_000_000;

    private static final int SUGGESTION_LIMIT = 3;

    /** The revision of an empty plan; normalize() builds its patch against it. */
    private static final int EMPTY_REVISION = 0;
    private static final String NORMALIZE_PATCH_ID = "normalize";
    private static final String NORMALIZE_STAGE_ID = "normalize";

    // Kinds and argument names of the fix hints, and names in Issue.data (read by the repair prompts).
    private static final String HINT_REBASE = "REBASE";
    private static final String HINT_USE_PART = "USE_PART";
    private static final String HINT_REMOVE_FIRST = "REMOVE_FIRST";
    /** The plan's revision, both in the data of E-PATCH-STALE and in the args of its REBASE hint. */
    private static final String ARG_REVISION = "revision";
    private static final String DATA_BASE_REVISION = "baseRevision";
    private static final String DATA_TYPE = "type";
    private static final String DATA_DEPENDENTS = "dependents";
    /** The ids a hint points at (parts to use, nodes to remove first). */
    private static final String ARG_IDS = "ids";
    private static final String ID_LIST_SEPARATOR = ",";

    // Keys that tell apart several issues of one code on one subject.
    private static final String NO_KEY = "";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_PARENT = "parent";
    private static final String KEY_ANCHOR = "anchor";
    private static final String KEY_CYCLE = "cycle";
    private static final String KEY_LABEL = "label";
    private static final String KEY_NODE = "node";
    private static final String KEY_REMOVE = "remove";
    private static final String KEY_STYLE = "style";
    private static final String KEY_SITE = "site";
    private static final String KEY_DOCK_PREFIX = "dock:";
    private static final String KEY_VIA_PREFIX = "via:";
    private static final String PORT_SEPARATOR = ".";
    /** The site has no id of its own, so its issues name it by this. */
    private static final String SITE_SUBJECT = "site";

    // Start of the message of E-ID-DUPLICATE, by what the id names.
    private static final String DUPLICATE_NODE_ID = "IDが重複しています: ";
    private static final String DUPLICATE_CONNECTION_ID = "接続のIDが重複しています: ";
    private static final String DUPLICATE_DOCK_ID = "発着場のIDが重複しています: ";
    private static final String DUPLICATE_ROUTE_ID = "航路のIDが重複しています: ";

    private final PartTypeRegistry registry;
    private final TemplateBundle templates;

    public PlanPatcher(PartTypeRegistry registry, TemplateBundle templates) {
        this.registry = registry;
        this.templates = templates;
    }

    /** The plan being edited: mutable copies of what the operations change, in insertion order. */
    private static final class State {
        final LinkedHashMap<String, PlanNode> nodes = new LinkedHashMap<>();
        final LinkedHashMap<String, Connection> connections = new LinkedHashMap<>();
        Site site;
        StyleSpec style;
        LogisticsPlan logistics;

        State(SemanticPlan plan) {
            for (PlanNode n : plan.nodes()) {
                nodes.put(n.id(), n);
            }
            for (Connection c : plan.connections()) {
                connections.put(c.id(), c);
            }
            site = plan.site();
            style = plan.style();
            logistics = plan.logistics();
        }
    }

    public PatchResult apply(SemanticPlan plan, PlanPatch patch) {
        if (patch.baseRevision() != plan.revision()) {
            return new PatchResult(null, List.of(staleIssue(plan, patch)));
        }
        List<Issue> issues = new ArrayList<>();
        State st = new State(plan);
        for (PlanOp op : patch.ops()) {
            applyOp(st, op, issues);
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new PatchResult(null, issues);
        }
        return new PatchResult(new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision() + 1, plan.revision(),
                st.site, st.style, new ArrayList<>(st.nodes.values()), new ArrayList<>(st.connections.values()),
                st.logistics, plan.provenance()), issues);
    }

    /**
     * Types a loosely read plan against the registry; keeps planId, revision and provenance. The plan is built
     * by adding everything to an empty one, so it passes exactly the checks a patch would.
     */
    public PatchResult normalize(SemanticPlan loose) {
        SemanticPlan empty = new SemanticPlan(loose.schemaVersion(), loose.planId(), EMPTY_REVISION, null, null,
                StyleSpec.EMPTY, List.of(), List.of(), null, loose.provenance());
        List<PlanOp> ops = new ArrayList<>();
        if (loose.site() != null) {
            ops.add(new PlanOp.SetSite(loose.site()));
        }
        ops.add(new PlanOp.SetStyle(loose.style()));
        for (PlanNode n : loose.nodes()) {
            ops.add(new PlanOp.AddNode(n));
        }
        for (Connection c : loose.connections()) {
            ops.add(new PlanOp.AddConnection(c));
        }
        if (loose.logistics() != null) {
            ops.add(new PlanOp.SetLogistics(loose.logistics()));
        }
        PatchResult r = apply(empty, new PlanPatch(NORMALIZE_PATCH_ID, EMPTY_REVISION, NORMALIZE_STAGE_ID, ops));
        if (!r.ok()) {
            return r;
        }
        SemanticPlan p = r.plan();
        return new PatchResult(new SemanticPlan(loose.schemaVersion(), loose.planId(), loose.revision(),
                loose.parentRevision(), p.site(), p.style(), p.nodes(), p.connections(), p.logistics(), loose.provenance()),
                r.issues());
    }

    private static Issue staleIssue(SemanticPlan plan, PlanPatch patch) {
        String revision = String.valueOf(plan.revision());
        return Issue.of(IssueCode.E_PATCH_STALE, NO_KEY, List.of(patch.patchId()),
                "差分は版" + patch.baseRevision() + "向けですが、計画は版" + plan.revision() + "です",
                Map.of(DATA_BASE_REVISION, String.valueOf(patch.baseRevision()), ARG_REVISION, revision),
                List.of(new FixHint(HINT_REBASE, Map.of(ARG_REVISION, revision))));
    }

    private void applyOp(State st, PlanOp op, List<Issue> issues) {
        switch (op) {
            case PlanOp.AddNode o -> addNode(st, o.node(), issues);
            case PlanOp.UpdateParams o -> updateParams(st, o, issues);
            case PlanOp.MoveNode o -> moveNode(st, o, issues);
            case PlanOp.RemoveNode o -> removeNode(st, o.id(), issues);
            case PlanOp.AddConnection o -> addConnection(st, o.connection(), issues);
            case PlanOp.RemoveConnection o -> removeConnection(st, o.id(), issues);
            case PlanOp.SetStyle o -> setStyle(st, o.style(), issues);
            case PlanOp.SetSite o -> setSite(st, o.site(), issues);
            case PlanOp.SetLogistics o -> setLogistics(st, o.logistics(), issues);
        }
    }

    // ------------------------------------------------------------------ ids

    private static Issue duplicateId(String id, String messageStart) {
        return Issue.of(IssueCode.E_ID_DUPLICATE, List.of(id), messageStart + id);
    }

    /** True when the id is well formed and not yet in {@code taken}; otherwise the issue is added and it is false. */
    private static boolean checkNewId(String id, Set<String> taken, String duplicateMessageStart, List<Issue> issues) {
        if (!PlanIds.isValid(id)) {
            issues.add(Issue.of(IssueCode.E_ID_INVALID, List.of(id), PlanIds.invalidMessage(id)));
            return false;
        }
        if (taken.contains(id)) {
            issues.add(duplicateId(id, duplicateMessageStart));
            return false;
        }
        return true;
    }

    /** Records the id in {@code seen}; a repeat is reported as E-ID-DUPLICATE and gives false. */
    private static boolean recordUniqueId(Set<String> seen, String id, String duplicateMessageStart, List<Issue> issues) {
        if (seen.add(id)) {
            return true;
        }
        issues.add(duplicateId(id, duplicateMessageStart));
        return false;
    }

    // ------------------------------------------------------------------ nodes

    private static PlanNode withParams(PlanNode n, Map<String, ParamValue> params) {
        return new PlanNode(n.id(), n.type(), n.parent(), n.anchor(), params, n.tags(), n.label());
    }

    private static PlanNode withAnchor(PlanNode n, Anchor anchor) {
        return new PlanNode(n.id(), n.type(), n.parent(), anchor, n.params(), n.tags(), n.label());
    }

    /** The node with this id, or null after reporting E-ANCHOR when there is none. */
    private static PlanNode existingNode(State st, String id, List<Issue> issues) {
        PlanNode n = st.nodes.get(id);
        if (n == null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_NODE, List.of(id), "対象のノードがありません: " + id));
        }
        return n;
    }

    private static Issue moduleTakesNoParams(String nodeId, String type) {
        return Issue.of(IssueCode.E_PARAM_RANGE, KEY_PARAMS, List.of(nodeId), "モジュール" + type + "はパラメータを持ちません");
    }

    private void addNode(State st, PlanNode node, List<Issue> issues) {
        if (!checkNewId(node.id(), st.nodes.keySet(), DUPLICATE_NODE_ID, issues)) {
            return;
        }
        Map<String, ParamValue> typed = typeParams(node, issues);
        boolean ok = typed != null;
        if (node.parent() != null && !st.nodes.containsKey(node.parent())) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_PARENT, List.of(node.id()), "親のノードがありません: " + node.parent()));
            ok = false;
        }
        ok &= checkAnchor(st, node.id(), node.anchor(), issues);
        ok &= checkText(node, issues);
        if (ok) {
            st.nodes.put(node.id(), withParams(node, typed));
        }
    }

    /**
     * The typed parameters of a new node: registry parts are typed by {@link ParamValidator} (defaults are not
     * filled in); a module instance takes none. Null, with the issues added, when the type or a parameter is wrong.
     */
    private Map<String, ParamValue> typeParams(PlanNode node, List<Issue> issues) {
        PartType type = registry.find(node.type()).orElse(null);
        if (type != null) {
            ParamValidator.Result r = ParamValidator.validate(node.id(), type, node.params());
            issues.addAll(r.issues());
            return r.issues().isEmpty() ? r.typed() : null;
        }
        if (templates.find(node.type()).isEmpty()) {
            issues.add(unknownPart(node));
            return null;
        }
        if (!node.params().isEmpty()) {
            issues.add(moduleTakesNoParams(node.id(), node.type()));
            return null;
        }
        return node.params();
    }

    private Issue unknownPart(PlanNode node) {
        List<String> near = new ArrayList<>(registry.suggest(node.type(), SUGGESTION_LIMIT));
        return Issue.of(IssueCode.E_UNKNOWN_PART, NO_KEY, List.of(node.id()),
                "登録簿に無い部品です: " + node.type(), Map.of(DATA_TYPE, node.type()),
                List.of(new FixHint(HINT_USE_PART, Map.of(ARG_IDS, String.join(ID_LIST_SEPARATOR, near)))));
    }

    /** Labels and tags are written into scripts, which cannot carry control characters other than newline and tab. */
    private static boolean checkText(PlanNode node, List<Issue> issues) {
        if (ParamValidator.hasForbiddenControl(node.label()) || node.tags().stream().anyMatch(ParamValidator::hasForbiddenControl)) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_LABEL, List.of(node.id()),
                    "ラベル・タグに、改行(\\n)とタブ以外の制御文字は使えません(スクリプトの往復で失われるため)"));
            return false;
        }
        return true;
    }

    private static boolean outOfRange(int v) {
        return Math.abs((long) v) > MAX_COORD;
    }

    private static boolean anyOutOfRange(int a, int b, int c) {
        return outOfRange(a) || outOfRange(b) || outOfRange(c);
    }

    /** A position on a wall face counts from the wall's corner, so it is never negative. */
    private static boolean outOfSurfaceRange(int v) {
        return v < 0 || v > MAX_COORD;
    }

    private static boolean checkAnchor(State st, String ownerId, Anchor anchor, List<Issue> issues) {
        if (anchor instanceof Anchor.Absolute a && anyOutOfRange(a.pos().u(), a.pos().v(), a.pos().w())) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_ANCHOR, List.of(ownerId),
                    "位置が大きすぎます(各成分は±" + MAX_COORD + "以内): " + a.pos()));
            return false;
        }
        if (!(anchor instanceof Anchor.OnSurface s)) {
            // InSlot is only resolved when the plan is expanded.
            return true;
        }
        String problem = surfaceProblem(st, ownerId, s);
        if (problem != null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_ANCHOR, List.of(ownerId), problem));
            return false;
        }
        if (surfaceChainReaches(st, ownerId, s.nodeId())) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_CYCLE, List.of(ownerId), "面に載せる関係が輪になっています"));
            return false;
        }
        return true;
    }

    /**
     * Whether the node {@code ownerId} is met by following surface anchors from {@code startId}: the start's own
     * anchor, then that target's anchor, and so on until a node that does not sit on a surface. If it is, putting
     * the owner on the start would close a loop, and anything that walks the chain (the expander) would never end.
     * The walk is capped at the number of nodes, so a loop that is already in the plan cannot spin this check.
     */
    private static boolean surfaceChainReaches(State st, String ownerId, String startId) {
        String current = startId;
        for (int step = 0; step <= st.nodes.size(); step++) {
            if (current.equals(ownerId)) {
                return true;
            }
            PlanNode node = st.nodes.get(current);
            if (node == null || !(node.anchor() instanceof Anchor.OnSurface next)) {
                return false;
            }
            current = next.nodeId();
        }
        return false;
    }

    /** Why the anchor cannot sit on that wall face, or null when it can. */
    private static String surfaceProblem(State st, String ownerId, Anchor.OnSurface s) {
        PlanNode target = st.nodes.get(s.nodeId());
        if (s.nodeId().equals(ownerId)) {
            return "自分自身の面には付けられません";
        }
        if (target == null) {
            return "面の対象のノードがありません: " + s.nodeId();
        }
        if (!target.type().equals(BuildingParts.WALL)) {
            return "面の対象は壁(" + BuildingParts.WALL + ")でなければなりません: " + s.nodeId();
        }
        if (s.side() != Side.OUTER && s.side() != Side.INNER) {
            return "面の側は outer か inner です";
        }
        if (outOfSurfaceRange(s.u()) || outOfSurfaceRange(s.v())) {
            return "面の上の位置(u, v)は、0以上" + MAX_COORD + "以下にしてください";
        }
        return null;
    }

    private void updateParams(State st, PlanOp.UpdateParams op, List<Issue> issues) {
        PlanNode existing = existingNode(st, op.id(), issues);
        if (existing == null) {
            return;
        }
        PartType type = registry.find(existing.type()).orElse(null);
        if (type == null) {
            issues.add(moduleTakesNoParams(op.id(), existing.type()));
            return;
        }
        Map<String, ParamValue> merged = new TreeMap<>(existing.params());
        merged.putAll(op.params());
        ParamValidator.Result r = ParamValidator.validate(op.id(), type, merged);
        issues.addAll(r.issues());
        if (r.issues().isEmpty()) {
            st.nodes.put(op.id(), withParams(existing, r.typed()));
        }
    }

    private void moveNode(State st, PlanOp.MoveNode op, List<Issue> issues) {
        PlanNode existing = existingNode(st, op.id(), issues);
        if (existing != null && checkAnchor(st, op.id(), op.anchor(), issues)) {
            st.nodes.put(op.id(), withAnchor(existing, op.anchor()));
        }
    }

    private void removeNode(State st, String id, List<Issue> issues) {
        if (existingNode(st, id, issues) == null) {
            return;
        }
        Set<String> dependents = dependentsOf(st, id);
        if (!dependents.isEmpty()) {
            String list = String.join(ID_LIST_SEPARATOR, dependents);
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_REMOVE, List.of(id),
                    id + "を消す前に、これに依存する物を消してください: " + list, Map.of(DATA_DEPENDENTS, list),
                    List.of(new FixHint(HINT_REMOVE_FIRST, Map.of(ARG_IDS, list)))));
            return;
        }
        st.nodes.remove(id);
    }

    /** The nodes, connections and docks that still refer to the node, by id in dictionary order. */
    private static Set<String> dependentsOf(State st, String id) {
        Set<String> dependents = new TreeSet<>();
        for (PlanNode n : st.nodes.values()) {
            if (id.equals(n.parent()) || (n.anchor() instanceof Anchor.OnSurface s && s.nodeId().equals(id))) {
                dependents.add(n.id());
            }
        }
        for (Connection c : st.connections.values()) {
            if (usesNode(c, id)) {
                dependents.add(c.id());
            }
        }
        if (st.logistics != null) {
            for (LogisticsPlan.Dock d : st.logistics.docks()) {
                if (usesNode(d, id)) {
                    dependents.add(d.id());
                }
            }
        }
        return dependents;
    }

    private static boolean usesNode(Connection c, String nodeId) {
        return c.from().nodeId().equals(nodeId) || c.to().nodeId().equals(nodeId)
                || c.constraints().avoidNodeIds().contains(nodeId)
                || (c.routing() instanceof Routing.Explicit e && e.viaNodeIds().contains(nodeId));
    }

    private static boolean usesNode(LogisticsPlan.Dock d, String nodeId) {
        return d.dockingConnectorNodeIds().contains(nodeId)
                || d.linkedPorts().stream().anyMatch(p -> p.nodeId().equals(nodeId));
    }

    // ------------------------------------------------------------------ connections

    private boolean portExists(State st, PortRef ref) {
        PlanNode n = st.nodes.get(ref.nodeId());
        if (n == null) {
            return false;
        }
        PartType type = registry.find(n.type()).orElse(null);
        if (type != null) {
            return type.port(ref.port()).isPresent();
        }
        return templates.find(n.type()).map(t -> t.port(ref.port()).isPresent()).orElse(false);
    }

    private void addConnection(State st, Connection c, List<Issue> issues) {
        if (!checkNewId(c.id(), st.connections.keySet(), DUPLICATE_CONNECTION_ID, issues)) {
            return;
        }
        boolean ok = true;
        for (PortRef ref : List.of(c.from(), c.to())) {
            if (!portExists(st, ref)) {
                String where = ref.nodeId() + PORT_SEPARATOR + ref.port();
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, where, List.of(c.id()), "つなぎ口がありません: " + where));
                ok = false;
            }
        }
        if (c.routing() instanceof Routing.Explicit e) {
            for (String via : e.viaNodeIds()) {
                if (!st.nodes.containsKey(via)) {
                    issues.add(Issue.of(IssueCode.E_CONN_INVALID, KEY_VIA_PREFIX + via, List.of(c.id()), "経由する部品がありません: " + via));
                    ok = false;
                }
            }
        }
        if (ok) {
            st.connections.put(c.id(), c);
        }
    }

    private static void removeConnection(State st, String id, List<Issue> issues) {
        if (st.connections.remove(id) == null) {
            issues.add(Issue.of(IssueCode.E_CONN_INVALID, List.of(id), "接続" + id + "はありません"));
        }
    }

    // ------------------------------------------------------------------ style, site, logistics

    private static void setStyle(State st, StyleSpec style, List<Issue> issues) {
        boolean ok = true;
        for (Map.Entry<String, String> e : style.palette().entrySet()) {
            if (!ParamValidator.isRoleName(e.getKey()) || !ParamValidator.isBlockId(e.getValue())) {
                issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_STYLE, List.of(e.getKey()),
                        "パレットは 役割名(小文字)→ブロックID(namespace:name)で書いてください: " + e.getKey() + " = " + e.getValue()));
                ok = false;
            }
        }
        if (ok) {
            st.style = style;
        }
    }

    private static void setSite(State st, Site site, List<Issue> issues) {
        IntPos o = site.frame().origin();
        if (anyOutOfRange(o.x(), o.y(), o.z())) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_SITE, List.of(SITE_SUBJECT),
                    "敷地の原点が大きすぎます(各成分は±" + MAX_COORD + "以内)"));
            return;
        }
        // A dimension id is written like a block id: namespace:name.
        if (!ParamValidator.isBlockId(site.dimension())) {
            issues.add(Issue.of(IssueCode.E_PARAM_RANGE, KEY_SITE, List.of(SITE_SUBJECT),
                    "ディメンションは namespace:name の形で書いてください: " + site.dimension()));
            return;
        }
        st.site = site;
    }

    private static void setLogistics(State st, LogisticsPlan logistics, List<Issue> issues) {
        if (logistics == null) {
            st.logistics = null;
            return;
        }
        boolean ok = true;
        Set<String> dockIds = new HashSet<>();
        for (LogisticsPlan.Dock d : logistics.docks()) {
            ok &= recordUniqueId(dockIds, d.id(), DUPLICATE_DOCK_ID, issues);
        }
        Set<String> routeIds = new HashSet<>();
        for (LogisticsPlan.Route r : logistics.routes()) {
            ok &= recordUniqueId(routeIds, r.id(), DUPLICATE_ROUTE_ID, issues);
            ok &= docksExist(dockIds, r.id(), r.fromDock(), r.toDock(), issues);
        }
        for (LogisticsPlan.CargoFlow f : logistics.flows()) {
            ok &= docksExist(dockIds, f.itemId(), f.fromDock(), f.toDock(), issues);
        }
        if (ok) {
            st.logistics = logistics;
        }
    }

    /** Reports each distinct dock among the two ends that is not in {@code dockIds}; pointing at one twice counts once. */
    private static boolean docksExist(Set<String> dockIds, String subject, String fromDock, String toDock, List<Issue> issues) {
        Set<String> ends = new LinkedHashSet<>();
        ends.add(fromDock);
        ends.add(toDock);
        boolean ok = true;
        for (String dock : ends) {
            if (!dockIds.contains(dock)) {
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, KEY_DOCK_PREFIX + dock, List.of(subject),
                        "存在しない発着場を指しています: " + dock));
                ok = false;
            }
        }
        return ok;
    }
}
