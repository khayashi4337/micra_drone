package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Grows a plan by random operations of EVERY kind, one at a time: a candidate operation is applied to the plan so far
 * and kept only when the patcher accepts it. The plans that come out are exactly the ones the patcher lets in - the
 * ones a round-trip guarantee has to hold for - including plans whose stored node order is not a dependency order
 * (a node relocated onto a wall added after it).
 */
final class RandomPlanOps {
    private static final String PLAN_ID = "rt-plan";
    private static final String STAGE_ID = "random";
    private static final String MODULE_TYPE = "mod:test_line";
    /** An id no node has: an operation on it is refused, which keeps the walk moving when the plan is still empty. */
    private static final String MISSING_ID = "ghost-2";
    /** A node id that is well formed but does not exist, and a port name with a dot: both are legal in a dock. */
    private static final String UNPLACED_NODE = "ghost-1";
    private static final String DOTTED_PORT = "p.q";

    /** The kinds of operation and how often each is tried (relative weights). */
    private enum Kind {
        ADD_NODE(34), MOVE_NODE(22), UPDATE_PARAMS(6), REMOVE_NODE(5), ADD_CONNECTION(12), REMOVE_CONNECTION(3),
        SET_STYLE(5), SET_SITE(4), SET_LOGISTICS(9);

        final int weight;

        Kind(int weight) {
            this.weight = weight;
        }
    }

    private static final int TOTAL_WEIGHT = Arrays.stream(Kind.values()).mapToInt(k -> k.weight).sum();

    /** One in this many draws takes the rarer branch (a parent, an int written as a number, a skipped parameter, ...). */
    private static final int ODDS_PARENT = 3;
    private static final int ODDS_INT_AS_NUMBER = 4;
    private static final int ODDS_SKIP_PARAM = 3;
    private static final int ODDS_NO_LOGISTICS = 8;
    private static final int ODDS_UNPLACED_DOCK_NODE = 8;
    private static final int ODDS_DOTTED_DOCK_PORT = 8;
    private static final int ODDS_LARGE_RATE = 10;
    /** Anchor rolls out of {@link #ANCHOR_ROLLS}: below this many is a wall face, exactly this many is a slot. */
    private static final int ANCHOR_ROLLS = 10;
    private static final int ANCHOR_ON_SURFACE_BELOW = 4;
    private static final int ANCHOR_IN_SLOT_AT = 4;
    /** Routing rolls: 0 and 1 are automatic, 2 is an explicit empty list, 3 and 4 an explicit list of nodes. */
    private static final int ROUTING_ROLLS = 5;
    private static final int ROUTING_EMPTY_EXPLICIT_AT = 2;
    private static final int ROUTING_AUTO_BELOW = 2;
    /** Site rolls: 0 neither, 1 digest only, 2 claim id only, 3 both. */
    private static final int SITE_ROLLS = 4;
    private static final int SITE_DIGEST_ONLY = 1;
    private static final int SITE_CLAIM_ONLY = 2;
    private static final int SITE_BOTH = 3;

    /** Random ints stay this close to their minimum, so the parameters are valid and small. */
    private static final int INT_PARAM_SPAN = 6;
    private static final int MAX_LIST_VALUES = 5;
    private static final int POSITION_RANGE = 50;
    private static final int SURFACE_RANGE = 30;
    private static final int SLOT_COUNT = 4;
    private static final int QUARTER_TURNS = 4;
    private static final int MAX_TEXT_CHARS = 11;
    private static final int MAX_ITEMS = 3;
    private static final int CONSTRAINT_LIMIT = 40;
    private static final int DIGITS = 10;
    private static final int AIRSHIP_KINDS = 3;

    /** Numbers a NUM parameter or a cargo rate may take (all inside the dial's 0..256 range): the sign of zero, tiny and long fractions. */
    private static final double[] NUMBERS = {-0.0, 0.0, 12.5, 0.1, 0.0000001, 255.0, 0.30000000000000004, 100.25};
    /** A rate no NUM parameter may take: fifteen digits in plain decimal text. */
    private static final double LARGE_RATE = 1.0E15;
    private static final List<String> ROLES = List.of("roof", "wall", "floor", "door", "glass");
    private static final List<String> BLOCKS = List.of("minecraft:stone_bricks", "minecraft:oak_planks", "mod:some_block",
            "minecraft:red_nether_bricks");
    private static final List<String> MOODS = List.of("cozy", "warm", "industrial", "日本語");
    private static final List<String> DIMENSIONS = List.of("minecraft:overworld", "mod:dim");
    private static final List<String> ITEMS = List.of("minecraft:iron_ingot", "create:iron_sheet", "mod:crate");
    private static final String TEXT_ALPHABET = "abc \"\\\n\t日本🏠'#|_-.0";

    private final Random rnd;
    private final PlanPatcher patcher;
    private final PartTypeRegistry registry;
    private final List<String> nodeTypes = new ArrayList<>();
    private final Map<String, List<String>> portNames = new TreeMap<>();
    private final Map<String, Integer> accepted = new TreeMap<>();
    private SemanticPlan plan = SemanticPlan.empty(PLAN_ID);
    private int nextNode;
    private int nextConnection;

    RandomPlanOps(long seed, PlanPatcher patcher, PartTypeRegistry registry) {
        this.rnd = new Random(seed);
        this.patcher = patcher;
        this.registry = registry;
        List<String> ids = new ArrayList<>();
        for (PartType t : registry.all()) {
            ids.add(t.id());
            if (!t.ports().isEmpty()) {
                portNames.put(t.id(), t.ports().stream().map(PortSpec::name).toList());
            }
        }
        ids.sort(null);
        nodeTypes.addAll(ids);
        nodeTypes.add(MODULE_TYPE);
        portNames.put(MODULE_TYPE, TestParts.lineTemplate().ports().stream().map(PortSpec::name).toList());
    }

    /** Tries {@code attempts} random operations and returns the plan made of the ones that were accepted. */
    SemanticPlan build(int attempts) {
        for (int i = 0; i < attempts; i++) {
            PlanOp op = randomOp();
            PatchResult r = patcher.apply(plan, new PlanPatch("p-" + i, plan.revision(), STAGE_ID, List.of(op)));
            if (r.ok()) {
                plan = r.plan();
                accepted.merge(op.getClass().getSimpleName(), 1, Integer::sum);
            }
        }
        return plan;
    }

    /** How many operations of each kind (AddNode, MoveNode, ...) the patcher accepted while the plan was grown. */
    Map<String, Integer> acceptedByKind() {
        return accepted;
    }

    // ------------------------------------------------------------------ operations

    private boolean oneIn(int odds) {
        return rnd.nextInt(odds) == 0;
    }

    private PlanOp randomOp() {
        int roll = rnd.nextInt(TOTAL_WEIGHT);
        for (Kind kind : Kind.values()) {
            if (roll < kind.weight) {
                return op(kind);
            }
            roll -= kind.weight;
        }
        throw new AssertionError("the weights add up to " + TOTAL_WEIGHT);
    }

    private PlanOp op(Kind kind) {
        return switch (kind) {
            case ADD_NODE -> new PlanOp.AddNode(node());
            case MOVE_NODE -> new PlanOp.MoveNode(anyNodeId(), anchor());
            case UPDATE_PARAMS -> updateParams();
            case REMOVE_NODE -> new PlanOp.RemoveNode(anyNodeId());
            case ADD_CONNECTION -> new PlanOp.AddConnection(connection());
            case REMOVE_CONNECTION -> new PlanOp.RemoveConnection("c" + rnd.nextInt(Math.max(1, nextConnection)));
            case SET_STYLE -> new PlanOp.SetStyle(style());
            case SET_SITE -> new PlanOp.SetSite(site());
            case SET_LOGISTICS -> new PlanOp.SetLogistics(logistics());
        };
    }

    private PlanOp updateParams() {
        PlanNode target = randomNode();
        if (target == null) {
            return new PlanOp.UpdateParams(MISSING_ID, Map.of());
        }
        return new PlanOp.UpdateParams(target.id(), params(target.type(), false));
    }

    // ------------------------------------------------------------------ nodes

    private PlanNode node() {
        String type = pick(nodeTypes);
        String parent = oneIn(ODDS_PARENT) ? existingNodeIdOrNull() : null;
        Set<String> tags = new TreeSet<>();
        for (int t = rnd.nextInt(MAX_ITEMS); t > 0; t--) {
            tags.add(text());
        }
        return new PlanNode("n" + nextNode++, type, parent, anchor(), params(type, true), tags, text());
    }

    private String anyNodeId() {
        PlanNode n = randomNode();
        return n == null ? MISSING_ID : n.id();
    }

    private String existingNodeIdOrNull() {
        PlanNode n = randomNode();
        return n == null ? null : n.id();
    }

    private PlanNode randomNode() {
        return plan.nodes().isEmpty() ? null : plan.nodes().get(rnd.nextInt(plan.nodes().size()));
    }

    /** A position anywhere: absolute, on ANY existing wall (also one added after the node that is moved), or in a slot. */
    private Anchor anchor() {
        List<PlanNode> walls = plan.nodes().stream().filter(n -> n.type().equals(BuildingParts.WALL)).toList();
        int roll = rnd.nextInt(ANCHOR_ROLLS);
        if (roll < ANCHOR_ON_SURFACE_BELOW && !walls.isEmpty()) {
            PlanNode wall = pick(walls);
            return new Anchor.OnSurface(wall.id(), rnd.nextBoolean() ? Side.OUTER : Side.INNER,
                    rnd.nextInt(SURFACE_RANGE), rnd.nextInt(SURFACE_RANGE));
        }
        if (roll == ANCHOR_IN_SLOT_AT) {
            return new Anchor.InSlot("slot-" + rnd.nextInt(SLOT_COUNT), rot());
        }
        return new Anchor.Absolute(new LocalPos(coordinate(), coordinate(), coordinate()), rot());
    }

    private int coordinate() {
        return rnd.nextInt(2 * POSITION_RANGE + 1) - POSITION_RANGE;
    }

    private Rot rot() {
        return new Rot(rnd.nextInt(QUARTER_TURNS), rnd.nextBoolean());
    }

    /** Values for the parameters of the type (a module instance takes none); optional ones are sometimes left out. */
    private Map<String, ParamValue> params(String type, boolean requiredOnesAlways) {
        Map<String, ParamValue> out = new TreeMap<>();
        PartType part = registry.find(type).orElse(null);
        if (part == null) {
            return out;
        }
        for (ParamSpec spec : part.params()) {
            boolean forced = requiredOnesAlways && spec.required();
            if (forced || !oneIn(ODDS_SKIP_PARAM)) {
                out.put(spec.name(), value(spec));
            }
        }
        return out;
    }

    private ParamValue value(ParamSpec spec) {
        return switch (spec.type()) {
            case INT -> {
                int v = intInRange(spec);
                yield oneIn(ODDS_INT_AS_NUMBER) ? new ParamValue.NumV(v) : new ParamValue.IntV(v);
            }
            case NUM -> new ParamValue.NumV(pick(NUMBERS));
            case BOOL -> new ParamValue.BoolV(rnd.nextBoolean());
            case ENUM -> new ParamValue.StrV(pick(spec.enumValues()));
            case MATERIAL -> new ParamValue.StrV(rnd.nextBoolean() ? pick(ROLES) : pick(BLOCKS));
            case STR -> new ParamValue.StrV(text(((ParamValue.IntV) spec.max()).value()));
            case INT_LIST -> {
                List<ParamValue> items = new ArrayList<>();
                for (int i = rnd.nextInt(MAX_LIST_VALUES + 1); i > 0; i--) {
                    items.add(new ParamValue.IntV(intInRange(spec)));
                }
                yield new ParamValue.ListV(items);
            }
        };
    }

    /** An int within the spec's bounds and within {@link #INT_PARAM_SPAN} of its minimum. */
    private int intInRange(ParamSpec spec) {
        int min = ((ParamValue.IntV) spec.min()).value();
        int max = ((ParamValue.IntV) spec.max()).value();
        return min + rnd.nextInt(Math.min(max - min, INT_PARAM_SPAN) + 1);
    }

    private String text() {
        return text(MAX_TEXT_CHARS);
    }

    /** Up to {@code maxChars} characters with quotes, backslashes, newlines, tabs, non-ASCII and a surrogate pair. */
    private String text(int maxChars) {
        StringBuilder sb = new StringBuilder();
        int wanted = rnd.nextInt(Math.min(maxChars, MAX_TEXT_CHARS) + 1);
        while (sb.length() < wanted) {
            int cp = TEXT_ALPHABET.codePointAt(rnd.nextInt(TEXT_ALPHABET.length()));
            if (sb.length() + Character.charCount(cp) > wanted) {
                break;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ connections

    private List<PlanNode> nodesWithPorts() {
        return plan.nodes().stream().filter(n -> portNames.containsKey(n.type())).toList();
    }

    private Connection connection() {
        List<PlanNode> withPorts = nodesWithPorts();
        if (withPorts.isEmpty()) {
            return new Connection("c" + nextConnection++, new PortRef(MISSING_ID, "out"), new PortRef(MISSING_ID, "in"),
                    ConnKind.ROTATION, Routing.AUTO, Constraints.NONE);
        }
        PlanNode from = pick(withPorts);
        PlanNode to = pick(withPorts);
        int routingRoll = rnd.nextInt(ROUTING_ROLLS);
        Routing routing = routingRoll < ROUTING_AUTO_BELOW ? Routing.AUTO
                : routingRoll == ROUTING_EMPTY_EXPLICIT_AT ? new Routing.Explicit(List.of())
                : new Routing.Explicit(someNodeIds());
        Constraints constraints = rnd.nextBoolean() ? Constraints.NONE : new Constraints(
                rnd.nextBoolean() ? Integer.valueOf(rnd.nextInt(CONSTRAINT_LIMIT)) : null, Set.copyOf(someNodeIds()),
                rnd.nextBoolean() ? Integer.valueOf(rnd.nextInt(MAX_ITEMS + 1)) : null, someDirections());
        return new Connection("c" + nextConnection++, new PortRef(from.id(), pick(portNames.get(from.type()))),
                new PortRef(to.id(), pick(portNames.get(to.type()))), pick(List.of(ConnKind.values())), routing, constraints);
    }

    private List<String> someNodeIds() {
        List<String> ids = new ArrayList<>();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0 && !plan.nodes().isEmpty(); i--) {
            ids.add(randomNode().id());
        }
        return ids;
    }

    private Set<Dir6> someDirections() {
        Set<Dir6> dirs = new TreeSet<>();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
            dirs.add(pick(List.of(Dir6.values())));
        }
        return dirs;
    }

    // ------------------------------------------------------------------ style, site, logistics

    private StyleSpec style() {
        Map<String, String> palette = new TreeMap<>();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
            palette.put(pick(ROLES), pick(BLOCKS));
        }
        Set<String> moods = new TreeSet<>();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
            moods.add(pick(MOODS));
        }
        return new StyleSpec(palette, moods);
    }

    /** A site with a digest only, a claim id only, both or neither. */
    private Site site() {
        int roll = rnd.nextInt(SITE_ROLLS);
        String digest = roll == SITE_DIGEST_ONLY || roll == SITE_BOTH ? "digest-" + rnd.nextInt(DIGITS) : "";
        String claim = roll == SITE_CLAIM_ONLY || roll == SITE_BOTH ? "claim-" + rnd.nextInt(DIGITS) : "";
        return new Site(pick(DIMENSIONS), new BuildFrame(new IntPos(coordinate(), coordinate(), coordinate()),
                pick(List.of(Facing.values()))), box(), digest, claim);
    }

    private Box box() {
        return Box.of(coordinate(), coordinate(), coordinate(), coordinate(), coordinate(), coordinate());
    }

    private LogisticsPlan logistics() {
        if (oneIn(ODDS_NO_LOGISTICS)) {
            return null;
        }
        List<LogisticsPlan.Dock> docks = new ArrayList<>();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
            docks.add(new LogisticsPlan.Dock("dock-" + docks.size(), box(), box(), pick(List.of(Facing.values())),
                    dockPorts(), someNodeIds()));
        }
        List<LogisticsPlan.Route> routes = new ArrayList<>();
        List<LogisticsPlan.CargoFlow> flows = new ArrayList<>();
        if (!docks.isEmpty()) {
            for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
                List<LocalPos> waypoints = new ArrayList<>();
                for (int w = rnd.nextInt(MAX_ITEMS + 1); w > 0; w--) {
                    waypoints.add(new LocalPos(coordinate(), coordinate(), coordinate()));
                }
                routes.add(new LogisticsPlan.Route("route-" + routes.size(), pick(docks).id(), pick(docks).id(), waypoints,
                        rnd.nextBoolean() ? null : "mod:airship_" + rnd.nextInt(AIRSHIP_KINDS)));
            }
            for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
                double rate = oneIn(ODDS_LARGE_RATE) ? LARGE_RATE : pick(NUMBERS);
                flows.add(new LogisticsPlan.CargoFlow(pick(ITEMS), rate, pick(docks).id(), pick(docks).id()));
            }
        }
        return new LogisticsPlan(docks, routes, flows);
    }

    /** Ports of nodes that exist, of a node that does not (yet), and now and then a port name with a dot. */
    private List<PortRef> dockPorts() {
        List<PortRef> ports = new ArrayList<>();
        List<PlanNode> withPorts = nodesWithPorts();
        for (int i = rnd.nextInt(MAX_ITEMS + 1); i > 0; i--) {
            if (withPorts.isEmpty() || oneIn(ODDS_UNPLACED_DOCK_NODE)) {
                ports.add(new PortRef(UNPLACED_NODE, "out"));
            } else if (oneIn(ODDS_DOTTED_DOCK_PORT)) {
                ports.add(new PortRef(pick(withPorts).id(), DOTTED_PORT));
            } else {
                PlanNode n = pick(withPorts);
                ports.add(new PortRef(n.id(), pick(portNames.get(n.type()))));
            }
        }
        return ports;
    }

    private <T> T pick(List<T> items) {
        return items.get(rnd.nextInt(items.size()));
    }

    private double pick(double[] numbers) {
        return numbers[rnd.nextInt(numbers.length)];
    }
}
