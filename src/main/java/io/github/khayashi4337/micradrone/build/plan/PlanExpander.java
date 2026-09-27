package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BuildLimits;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamValidator;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.lang.PlanApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Expands a plan into parts only: module instances become their template's parts, connections are resolved.
 * The server runs this itself on what the client sent; the client's own expansion is never trusted (D-3).
 */
public final class PlanExpander {
    public static final String MODULE_PREFIX = "mod:";
    /** Separates an instance id from a template-internal id; not allowed in user ids, so it cannot collide. */
    public static final String ID_SEPARATOR = "/";

    // Keys that tell apart several issues of one code on one subject (see also Origins).
    private static final String KEY_ROT = "rot";
    private static final String KEY_VIA_PREFIX = "via:";
    private static final String TOO_MANY_PARTS = "展開した部品が、施工の大きさの上限(" + BuildLimits.MAX_CELLS
            + "マス)を超えます。部品は1つにつき1マス以上を使うため、これより多い部品は施工できません。"
            + "モジュールの数か、テンプレートの部品の数を減らしてください";
    private static final String TOO_MANY_CONNECTIONS = "展開した接続が、上限(" + BuildLimits.MAX_EXPANDED_CONNECTIONS
            + "本)を超えます。モジュールの数か、テンプレートの内部の接続の数を減らしてください";

    private final PartTypeRegistry registry;
    private final SlotResolver slots;

    public PlanExpander(PartTypeRegistry registry, SlotResolver slots) {
        this.registry = registry;
        this.slots = slots;
    }

    /** Where a module instance sits: its position and rotation, once a slot is resolved. */
    private record Placement(LocalPos pos, Rot rot) {
        /** A mirror counts as a turn: it moves the parts just as a rotation does. */
        boolean turned() {
            return !rot.equals(Rot.NONE);
        }
    }

    /** What a connection can point at once the parts are expanded: the parts, the module instances and their origins. */
    private record Targets(Map<String, PlanNode> parts, Map<String, ModuleTemplate> modules, Map<String, LocalPos> origins) {
    }

    private static boolean isModule(PlanNode node) {
        return node.type().startsWith(MODULE_PREFIX);
    }

    public ExpandResult expand(SemanticPlan plan, TemplateBundle templates, Router router) {
        return expand(plan, templates, router, new TemplateWork());
    }

    /**
     * As above, and {@code work} counts what was done once per distinct template (read by the tests).
     * <p>
     * A plan that would expand to more parts than {@link BuildLimits#MAX_CELLS}, or to more connections than
     * {@link BuildLimits#MAX_EXPANDED_CONNECTIONS}, is refused first, before any part or connection is built. Everything
     * that depends on a template alone is worked out once per distinct template
     * ({@link CheckedTemplate}), so the work per module instance is the parts and connections it produces and nothing else. All of this is
     * in memory; nothing here reaches outside the process except {@code slots} and {@code router}, which the caller
     * supplies.
     */
    ExpandResult expand(SemanticPlan plan, TemplateBundle templates, Router router, TemplateWork work) {
        List<Issue> issues = new ArrayList<>();
        List<PlanNode> primitive = new ArrayList<>();
        Map<String, ModuleTemplate> moduleInstances = new HashMap<>();
        List<Connection> connections = new ArrayList<>(plan.connections());
        TreeSet<String> hashes = new TreeSet<>();
        Map<String, CheckedTemplate> checkedTemplates = new HashMap<>();
        // Nodes left out because they were refused (their issue is reported): the parts that hang from one are not also
        // reported as having a missing parent.
        Set<String> refused = new HashSet<>();

        Set<String> moduleIds = new HashSet<>();
        for (PlanNode n : plan.nodes()) {
            if (isModule(n)) {
                moduleIds.add(n.id());
            }
        }
        Issue tooBig = firstOverLimit(plan, moduleIds, templates);
        if (tooBig != null) {
            issues.add(tooBig);
            return new ExpandResult(null, issues);
        }
        for (PlanNode node : plan.nodes()) {
            if (node.parent() != null && moduleIds.contains(node.parent())) {
                issues.add(Issue.of(IssueCode.E_ANCHOR, Origins.KEY_PARENT, List.of(node.id()),
                        "モジュール(" + node.parent() + ")の中に部品は入れられません。テンプレートの部品として書いてください"));
                refused.add(node.id());
                continue;
            }
            if (!isModule(node)) {
                PlanNode placed = placeOnSlot(node, issues);
                if (placed != null) {
                    primitive.add(placed);
                } else {
                    refused.add(node.id());
                }
                continue;
            }
            CheckedTemplate checked = checkedTemplates.get(node.type());
            if (checked == null) {
                ModuleTemplate template = templates.find(node.type()).orElse(null);
                if (template == null) {
                    issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, List.of(node.id()), "テンプレートがありません: " + node.type()));
                    refused.add(node.id());
                    continue;
                }
                checked = new CheckedTemplate(template, registry, work);
                checkedTemplates.put(node.type(), checked);
            }
            if (instantiate(node, checked, primitive, connections, issues)) {
                moduleInstances.put(node.id(), checked.template());
                hashes.add(checked.hash());
            } else {
                refused.add(node.id());
            }
        }

        Map<String, LocalPos> origins = Origins.resolve(primitive, slots, issues, refused);
        Map<String, PlanNode> primitiveById = new HashMap<>();
        for (PlanNode n : primitive) {
            primitiveById.put(n.id(), n);
        }
        Targets targets = new Targets(primitiveById, moduleInstances, origins);
        List<RoutedConnection> routed = new ArrayList<>();
        for (Connection c : connections) {
            resolveConnection(plan, c, targets, router, routed, issues);
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new ExpandResult(null, issues);
        }
        return new ExpandResult(new ExpandedPlan(plan, primitive, routed, new ArrayList<>(hashes)), issues);
    }

    // ------------------------------------------------------------------ slots

    /**
     * The absolute position of a node placed on a slot, or null after reporting E-ANCHOR. The slot's position is
     * already absolute, so a node on a slot cannot also have a parent.
     */
    private LocalPos slotPosition(PlanNode node, Anchor.InSlot slot, List<Issue> issues) {
        if (node.parent() != null) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, Origins.KEY_PARENT, List.of(node.id()),
                    "スロットに置く部品・モジュールは、親を持てません"));
            return null;
        }
        return Origins.resolveSlot(slots, node.id(), slot.slotId(), issues).orElse(null);
    }

    /** A part on a slot becomes a part at the slot's absolute position; any other part is kept as it is. Null on failure. */
    private PlanNode placeOnSlot(PlanNode node, List<Issue> issues) {
        if (!(node.anchor() instanceof Anchor.InSlot s)) {
            return node;
        }
        LocalPos pos = slotPosition(node, s, issues);
        if (pos == null) {
            return null;
        }
        return new PlanNode(node.id(), node.type(), null, new Anchor.Absolute(pos, s.rot()), node.params(), node.tags(), node.label());
    }

    // ------------------------------------------------------------------ modules

    /** Where the instance sits, or null after reporting E-ANCHOR (a wall face, or a slot that cannot be used). */
    private Placement placementOf(PlanNode instance, List<Issue> issues) {
        return switch (instance.anchor()) {
            case Anchor.Absolute a -> new Placement(a.pos(), a.rot());
            case Anchor.InSlot s -> {
                LocalPos pos = slotPosition(instance, s, issues);
                yield pos == null ? null : new Placement(pos, s.rot());
            }
            case Anchor.OnSurface s -> {
                issues.add(Issue.of(IssueCode.E_ANCHOR, Origins.KEY_ANCHOR, List.of(instance.id()), "モジュールは壁の面には付けられません"));
                yield null;
            }
        };
    }

    /**
     * E-OUT-OF-BOUNDS on the first node that takes the expanded plan past a limit, or null when the plan stays within them.
     * Two things are counted, in one pass and before anything is built:
     * <ul>
     * <li>the parts, against {@link BuildLimits#MAX_CELLS}: a part places at least one cell, and the compiler refuses more
     * cells than that, so a plan with more parts could not be built; this is the compiler's own work-budget refusal (same
     * code, key and data);</li>
     * <li>the connections, against {@link BuildLimits#MAX_EXPANDED_CONNECTIONS}: the plan's own, then the internal
     * connections of each module instance's template.</li>
     * </ul>
     * A part counts as one part, a module instance as the parts and the internal connections of its template, and a node
     * the expansion will refuse (inside a module instance, or of a template the bundle lacks) as none. A node that is
     * refused later for another reason (a slot that does not resolve, a bad template) is still counted, so the counts are
     * upper bounds, and they are exact for a plan that expands. Counting only, so a plan of 100,000 instances of a large
     * template is refused without building a single node or connection. The plan's own connections are counted first; when
     * they alone are over the limit, the one that crosses it is the subject. When a node crosses both limits, the parts
     * are reported. The messages name no id: the node is the subject of the issue.
     */
    private static Issue firstOverLimit(SemanticPlan plan, Set<String> moduleIds, TemplateBundle templates) {
        List<Connection> own = plan.connections();
        if (own.size() > BuildLimits.MAX_EXPANDED_CONNECTIONS) {
            return overLimit(BuildLimits.KEY_CONNECTIONS, BuildLimits.MAX_EXPANDED_CONNECTIONS,
                    own.get(BuildLimits.MAX_EXPANDED_CONNECTIONS).id(), TOO_MANY_CONNECTIONS);
        }
        long parts = 0;
        long connections = own.size();
        for (PlanNode node : plan.nodes()) {
            if (node.parent() != null && moduleIds.contains(node.parent())) {
                continue;
            }
            if (!isModule(node)) {
                parts++;
            } else {
                ModuleTemplate template = templates.find(node.type()).orElse(null);
                if (template != null) {
                    parts += template.nodes().size();
                    connections += template.internal().size();
                }
            }
            if (parts > BuildLimits.MAX_CELLS) {
                return overLimit(BuildLimits.KEY_CELLS, BuildLimits.MAX_CELLS, node.id(), TOO_MANY_PARTS);
            }
            if (connections > BuildLimits.MAX_EXPANDED_CONNECTIONS) {
                return overLimit(BuildLimits.KEY_CONNECTIONS, BuildLimits.MAX_EXPANDED_CONNECTIONS, node.id(), TOO_MANY_CONNECTIONS);
            }
        }
        return null;
    }

    /** The refusal of a size limit: E-OUT-OF-BOUNDS with the limit's key, the node that crossed it, and the limit as data. */
    private static Issue overLimit(String key, int limit, String subject, String message) {
        return Issue.of(IssueCode.E_OUT_OF_BOUNDS, key, List.of(subject), message, Map.of(key, String.valueOf(limit)), List.of());
    }

    /**
     * Adds the template's parts and connections under the instance's id. False (nothing added) after reporting an
     * issue. Every problem of the ids and of the placement is reported; a bad part stops at the first one.
     */
    private boolean instantiate(PlanNode instance, CheckedTemplate template, List<PlanNode> out, List<Connection> connections,
                                List<Issue> issues) {
        String prefix = instance.id() + ID_SEPARATOR;
        template.reportIdProblems(prefix, issues);
        Placement at = placementOf(instance, issues);
        if (!template.idsAreValid() || at == null) {
            return false;
        }
        List<PlanNode> produced = new ArrayList<>();
        for (int i = 0; i < template.partCount(); i++) {
            PlanNode part = instantiatePart(template, i, instance, at, prefix, issues);
            if (part == null) {
                return false;
            }
            produced.add(part);
        }
        out.addAll(produced);
        for (Connection c : template.template().internal()) {
            connections.add(prefixed(c, prefix));
        }
        return true;
    }

    /**
     * The template part at {@code index} inside the instance, or null after reporting an issue. What the registry and the
     * validator say about the part is worked out once for the template, and only when an instance gets this far; what
     * depends on the instance is decided here, in the order it always was: the turn, the anchor, then the part itself.
     */
    private PlanNode instantiatePart(CheckedTemplate template, int index, PlanNode instance, Placement at, String prefix,
                                     List<Issue> issues) {
        PlanNode t = template.template().nodes().get(index);
        String id = prefix + t.id();
        if (at.turned() && BuildingParts.ROTATION_UNSUPPORTED.contains(t.type())) {
            issues.add(Issue.of(IssueCode.E_ANCHOR, KEY_ROT, List.of(instance.id()),
                    "回転・鏡像を付けたモジュールに、回転できない部品(" + t.type() + ")は入れられません"));
            return null;
        }
        Anchor anchor = placedAnchor(t, at, prefix, issues);
        if (anchor == null) {
            return null;
        }
        CheckedTemplate.PartCheck check = template.part(index);
        if (!check.known()) {
            issues.add(Issue.of(IssueCode.E_UNKNOWN_PART, List.of(id), "テンプレートの部品" + t.type()
                    + "は、登録簿にありません(テンプレートの中にテンプレートは入れられません)"));
            return null;
        }
        if (!check.valid()) {
            // a template that arrives from a client is not trusted: its parts are checked like any other part. The
            // verdict is the template's, but the issues name this instance's part, so they are worded here (only for
            // an instance that is refused, so this is never repeated for the instances that expand)
            issues.addAll(ParamValidator.validate(id, check.type(), t.params()).issues());
            return null;
        }
        String parent = t.parent() == null ? instance.parent() : prefix + t.parent();
        return new PlanNode(id, t.type(), parent, anchor, check.typed(), t.tags(), t.label());
    }

    /**
     * The anchor of a template part once the instance is placed, or null after reporting E-ANCHOR. The template's roots
     * take the instance's position; the other parts stay relative to their parent inside the template.
     */
    private static Anchor placedAnchor(PlanNode t, Placement at, String prefix, List<Issue> issues) {
        return switch (t.anchor()) {
            case Anchor.Absolute a -> {
                LocalPos offset = t.parent() == null ? at.pos() : Origins.PLAN_ORIGIN;
                // A template from a client may hold any int, so the sum is bounded rather than trusted to fit. The
                // patcher bounds a plan node at +-MAX_COORD, so a template position at that limit on top of an
                // instance position at that limit reaches 2 * MAX_COORD; that is where the bound sits (see
                // Origins.sum), and a sum of that size cannot overflow an int.
                LocalPos placed = Origins.sum(at.rot().apply(a.pos()), offset);
                if (placed == null) {
                    issues.add(Origins.positionTooLarge(prefix + t.id()));
                    yield null;
                }
                yield new Anchor.Absolute(placed, Rot.compose(at.rot(), a.rot()));
            }
            case Anchor.OnSurface s -> new Anchor.OnSurface(prefix + s.nodeId(), s.side(), s.u(), s.v());
            case Anchor.InSlot s -> {
                issues.add(Issue.of(IssueCode.E_ANCHOR, Origins.KEY_ANCHOR, List.of(prefix + t.id()), "テンプレートの中でスロットは使えません"));
                yield null;
            }
        };
    }

    // ------------------------------------------------------------------ connections

    /** A template's connection inside an instance: its id, its ends, the nodes it names and the nodes it avoids move under the prefix. */
    private static Connection prefixed(Connection c, String prefix) {
        Routing routing = c.routing() instanceof Routing.Explicit e
                ? new Routing.Explicit(prefixAll(e.viaNodeIds(), prefix).toList()) : c.routing();
        Constraints k = c.constraints();
        Constraints constraints = new Constraints(k.maxLength(), prefixAll(k.avoidNodeIds(), prefix).collect(Collectors.toSet()),
                k.maxTurns(), k.allowedEntryDirs());
        return new Connection(prefix + c.id(), prefixed(c.from(), prefix), prefixed(c.to(), prefix), c.kind(), routing, constraints);
    }

    private static PortRef prefixed(PortRef ref, String prefix) {
        return new PortRef(prefix + ref.nodeId(), ref.port());
    }

    private static Stream<String> prefixAll(Collection<String> ids, String prefix) {
        return ids.stream().map(id -> prefix + id);
    }

    private boolean portExists(PortRef ref, Targets targets) {
        ModuleTemplate module = targets.modules().get(ref.nodeId());
        if (module != null) {
            return module.port(ref.port()).isPresent();
        }
        PlanNode node = targets.parts().get(ref.nodeId());
        if (node == null) {
            return false;
        }
        PartType type = registry.find(node.type()).orElse(null);
        return type != null && type.port(ref.port()).isPresent();
    }

    private void resolveConnection(SemanticPlan plan, Connection c, Targets targets, Router router,
                                   List<RoutedConnection> routed, List<Issue> issues) {
        boolean ok = true;
        for (PortRef ref : List.of(c.from(), c.to())) {
            if (!portExists(ref, targets)) {
                String where = ref.nodeId() + PlanApi.NODE_PORT_SEPARATOR + ref.port();
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, where, List.of(c.id()), "つなぎ口がありません: " + where));
                ok = false;
            }
        }
        if (!ok) {
            return;
        }
        if (c.routing() instanceof Routing.Explicit e) {
            routeExplicit(c, e, targets, routed, issues);
            return;
        }
        router.route(plan, c).ifPresentOrElse(routed::add, () -> issues.add(Issue.of(IssueCode.E_NO_ROUTE, List.of(c.id()),
                "経路を自動で作るRouterは、P11で載るまで使えません。経由する部品を書くか(connectのviaに部品IDを並べる)、載るまで待ってください")));
    }

    /** The path of an explicit connection is the origins of its via parts; a part without an origin here is skipped. */
    private static void routeExplicit(Connection c, Routing.Explicit explicit, Targets targets, List<RoutedConnection> routed,
                                      List<Issue> issues) {
        List<LocalPos> path = new ArrayList<>();
        for (String via : explicit.viaNodeIds()) {
            if (!targets.parts().containsKey(via)) {
                issues.add(Issue.of(IssueCode.E_CONN_INVALID, KEY_VIA_PREFIX + via, List.of(c.id()), "経由する部品がありません: " + via));
                return;
            }
            LocalPos origin = targets.origins().get(via);
            if (origin != null) {
                path.add(origin);
            }
        }
        routed.add(new RoutedConnection(c.id(), List.of(), path));
    }
}
