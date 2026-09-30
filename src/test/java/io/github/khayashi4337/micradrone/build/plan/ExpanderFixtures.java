package io.github.khayashi4337.micradrone.build.plan;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Provenance;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PortKind;
import io.github.khayashi4337.micradrone.build.parts.PortSpec;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Random templates, module instances and plans for the tests that compare expansions. A seed fixes everything. The
 * templates and plans can be wrong in every way the expander checks (ids, unknown or badly parameterised parts, parts
 * that cannot turn, slots, positions that only add up too large for the instance they sit in, connections to nothing)
 * and can be right, so that both outcomes are compared. An error percent of 0 means nothing is wrong on purpose.
 */
final class ExpanderFixtures {
    static final String MOTOR = "test:motor";
    static final String SHAFT = "test:shaft";
    static final String PRESS = "test:press";
    static final String PILLAR = "micra:pillar";
    static final String WALL = "micra:wall";
    static final String STRUCTURE = "micra:structure";
    private static final String UNKNOWN_TYPE = "x:nope";
    private static final String NESTED_MODULE = "mod:test_line";
    private static final String MISSING_TEMPLATE = "mod:missing";

    /** The slot the test resolvers know; any other slot id does not resolve. */
    static final String KNOWN_SLOT = "slot-a";
    static final LocalPos SLOT_POS = new LocalPos(5, 0, 5);
    static final SlotResolver SLOTS = id -> id.equals(KNOWN_SLOT) ? Optional.of(SLOT_POS) : Optional.empty();

    /** A router that finds a (path-less) route for every connection, so a plan with automatic connections can expand. */
    static final Router ROUTER = (plan, c) -> Optional.of(new RoutedConnection(c.id(), List.of(), List.of()));

    private static final int MAX_TEMPLATE_PARTS = 5;
    private static final int MAX_PLAN_NODES = 8;
    private static final int MAX_INTERNAL_CONNECTIONS = 2;
    private static final int MAX_PLAN_CONNECTIONS = 3;
    private static final int MAX_VIA = 2;
    private static final int PERCENT = 100;
    private static final int COORD_SPAN = 10;
    private static final int COORD_MIN = -3;
    private static final int QUARTER_TURNS = 4;
    private static final int MIRROR_PERCENT = 25;
    /** Chances, in percent, of a part on a wall face and of a part far from its template's origin (not mistakes on their own). */
    private static final int SURFACE_PERCENT = 10;
    private static final int FAR_PERCENT = 8;
    private static final int MODULE_PERCENT = 50;
    private static final int PARENT_PERCENT = 33;
    private static final int MAX_HEIGHT = 8;
    /** A height out of range, and positions that are fine alone and only add up too large. */
    private static final int BAD_HEIGHT = 999;
    private static final int FAR = 40_000_000;
    private static final int INSTANCE_FAR = 30_000_000;
    private static final String KEY_HEIGHT = "height";
    private static final String KEY_BASE = "base";
    private static final String UNKNOWN_PARAM = "nope";
    private static final String UNKNOWN_PORT = "nope";
    private static final List<String> BAD_PART_IDS = List.of("Bad", "a/b", "");
    private static final List<String> BAD_CONNECTION_IDS = List.of("RUN", "run");
    private static final String PORT_OUT = "out";
    private static final String PORT_IN = "in";
    private static final String GHOST = "ghost";
    private static final String OTHER_SLOT = "slot-x";
    private static final List<String> CLEAN_TYPES = List.of(MOTOR, MOTOR, SHAFT, SHAFT, PRESS, PILLAR, PILLAR, STRUCTURE);
    private static final List<String> BROKEN_TYPES = List.of(UNKNOWN_TYPE, NESTED_MODULE, WALL, PILLAR);

    private ExpanderFixtures() {
    }

    /**
     * The generator of a seed. The seeds of the tests are consecutive numbers, and the first values of a plain
     * {@code new Random(seed)} for consecutive seeds are nearly equal, so every seed would make the same first choices;
     * the seed is scrambled first.
     */
    static Random random(long seed) {
        return new Random(new SplittableRandom(seed).nextLong());
    }

    static boolean chance(Random r, int percent) {
        return r.nextInt(PERCENT) < percent;
    }

    private static <T> T pick(Random r, List<T> items) {
        return items.get(r.nextInt(items.size()));
    }

    /** No turn half of the time: a turned instance cannot hold a building part, so turning every one would refuse most plans. */
    static Rot rot(Random r) {
        return chance(r, PERCENT / 2) ? Rot.NONE : new Rot(r.nextInt(QUARTER_TURNS), chance(r, MIRROR_PERCENT));
    }

    private static LocalPos smallPos(Random r) {
        return new LocalPos(COORD_MIN + r.nextInt(COORD_SPAN), COORD_MIN + r.nextInt(COORD_SPAN), COORD_MIN + r.nextInt(COORD_SPAN));
    }

    /** A plan that did not pass the patcher, so that every kind of mistake can be put into it. */
    static SemanticPlan plan(List<PlanNode> nodes, List<Connection> connections) {
        return new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "random", 0, null, null, StyleSpec.EMPTY, nodes, connections, null,
                Provenance.NONE);
    }

    // ------------------------------------------------------------------ templates

    /**
     * The kinds of mistake a template can hold. A template holds only some of them, so that each kind is reached before
     * another one stops the check of the template.
     */
    private enum Mistake { ID, PARENT, TYPE, PARAM, ANCHOR, CONNECTION }

    private static final int MISTAKE_KIND_PERCENT = 35;

    private static Set<Mistake> mistakesOf(Random r, int errorPercent) {
        Set<Mistake> kinds = EnumSet.noneOf(Mistake.class);
        if (errorPercent == 0) {
            return kinds;
        }
        for (Mistake m : Mistake.values()) {
            if (chance(r, MISTAKE_KIND_PERCENT)) {
                kinds.add(m);
            }
        }
        if (kinds.isEmpty()) {
            kinds.add(Mistake.values()[r.nextInt(Mistake.values().length)]);
        }
        return kinds;
    }

    private static boolean mistake(Random r, Set<Mistake> kinds, Mistake kind, int errorPercent) {
        return kinds.contains(kind) && chance(r, errorPercent);
    }

    /** The parameters of a part: valid ones, or (when wrong) an out-of-range height on a pillar, or a parameter no part has. */
    private static Map<String, ParamValue> paramsFor(Random r, String type, boolean wrong) {
        if (wrong) {
            return type.equals(PILLAR) && r.nextBoolean() ? Map.of(KEY_HEIGHT, new IntV(BAD_HEIGHT)) : Map.of(UNKNOWN_PARAM, new IntV(1));
        }
        if (type.equals(PILLAR)) {
            return Map.of(KEY_HEIGHT, new IntV(1 + r.nextInt(MAX_HEIGHT)), KEY_BASE, new BoolV(r.nextBoolean()));
        }
        return Map.of();
    }

    private static Anchor templateAnchor(Random r, List<String> ids, boolean wrong) {
        if (wrong) {
            return r.nextBoolean() ? new Anchor.InSlot(KNOWN_SLOT, Rot.NONE)
                    : new Anchor.Absolute(new LocalPos(Integer.MAX_VALUE, 0, 0), Rot.NONE);
        }
        int roll = r.nextInt(PERCENT);
        if (roll < SURFACE_PERCENT) {
            return new Anchor.OnSurface(pick(r, ids), Side.OUTER, r.nextInt(COORD_SPAN), r.nextInt(COORD_SPAN));
        }
        if (roll < SURFACE_PERCENT + FAR_PERCENT) {
            // fine on its own; too far once an instance that is itself far away is added
            return new Anchor.Absolute(new LocalPos(FAR, 0, 0), rot(r));
        }
        return new Anchor.Absolute(smallPos(r), rot(r));
    }

    private static List<String> viaList(Random r, List<String> partIds, int errorPercent) {
        List<String> via = new ArrayList<>();
        int count = r.nextInt(MAX_VIA + 1);
        for (int i = 0; i < count; i++) {
            via.add(chance(r, errorPercent) ? GHOST : pick(r, partIds));
        }
        return via;
    }

    /** A connection from the port "out" of a motor to the port "in" of a shaft, when both are among the parts. */
    static List<Connection> motorToShaft(String id, List<PlanNode> parts, Random r) {
        List<PlanNode> motors = parts.stream().filter(n -> n.type().equals(MOTOR) && PlanIds.isValid(n.id())).toList();
        List<PlanNode> shafts = parts.stream().filter(n -> n.type().equals(SHAFT) && PlanIds.isValid(n.id())).toList();
        if (motors.isEmpty() || shafts.isEmpty()) {
            return List.of();
        }
        Routing routing = r.nextBoolean() ? new Routing.Explicit(List.of()) : Routing.AUTO;
        return List.of(new Connection(id, new PortRef(pick(r, motors).id(), PORT_OUT), new PortRef(pick(r, shafts).id(), PORT_IN),
                ConnKind.ROTATION, routing, Constraints.NONE));
    }

    private static List<Connection> internalConnections(Random r, List<PlanNode> parts, Set<Mistake> kinds, int errorPercent) {
        if (!kinds.contains(Mistake.CONNECTION)) {
            return motorToShaft("c0", parts, r);
        }
        List<String> partIds = parts.stream().map(PlanNode::id).toList();
        List<Connection> out = new ArrayList<>();
        int count = 1 + r.nextInt(MAX_INTERNAL_CONNECTIONS);
        for (int i = 0; i < count; i++) {
            String id = chance(r, errorPercent) ? pick(r, BAD_CONNECTION_IDS) : "c" + i;
            String from = chance(r, errorPercent) ? GHOST : pick(r, partIds);
            String to = pick(r, partIds);
            Routing routing = r.nextBoolean() ? new Routing.Explicit(viaList(r, partIds, errorPercent)) : Routing.AUTO;
            Constraints constraints = r.nextBoolean() ? Constraints.NONE : new Constraints(null, Set.of(pick(r, partIds)), null, Set.of());
            out.add(new Connection(id, new PortRef(from, PORT_OUT), new PortRef(to, chance(r, errorPercent) ? UNKNOWN_PORT : PORT_IN),
                    ConnKind.ROTATION, routing, constraints));
        }
        return out;
    }

    /**
     * A template of 1 to 5 parts. {@code errorPercent} is the chance, per part and per connection, of each kind of mistake the
     * template holds (0: none); a template without mistakes can still be spoiled by its instance (a part that cannot turn under a
     * turned instance, a far part under a far one).
     */
    static ModuleTemplate template(Random r, String id, int errorPercent) {
        Set<Mistake> kinds = mistakesOf(r, errorPercent);
        int parts = 1 + r.nextInt(MAX_TEMPLATE_PARTS);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            ids.add("p" + i);
        }
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            String partId = mistake(r, kinds, Mistake.ID, errorPercent) ? pick(r, BAD_PART_IDS) : ids.get(i);
            if (i > 0 && mistake(r, kinds, Mistake.ID, errorPercent)) {
                partId = ids.get(r.nextInt(i));
            }
            String parent = i == 0 || r.nextBoolean() ? null : ids.get(r.nextInt(i));
            if (mistake(r, kinds, Mistake.PARENT, errorPercent)) {
                parent = GHOST;
            }
            String type = mistake(r, kinds, Mistake.TYPE, errorPercent) ? pick(r, BROKEN_TYPES) : pick(r, CLEAN_TYPES);
            Anchor anchor = templateAnchor(r, ids, mistake(r, kinds, Mistake.ANCHOR, errorPercent));
            nodes.add(new PlanNode(partId, type, parent, anchor, paramsFor(r, type, mistake(r, kinds, Mistake.PARAM, errorPercent)),
                    Set.of(), ""));
        }
        List<PortSpec> ports = r.nextBoolean() ? List.of()
                : List.of(new PortSpec(PORT_OUT, PortKind.ROTATION_OUT, new LocalPos(1, 0, 0), Dir6.EAST, Set.of()));
        return new ModuleTemplate(1, id, "k." + id, PartCategory.MODULE, VersionRange.ALWAYS, null, ports, nodes,
                internalConnections(r, nodes, kinds, errorPercent), null, null, Set.of());
    }

    // ------------------------------------------------------------------ instances and plans

    static Anchor instanceAnchor(Random r, int errorPercent) {
        if (chance(r, errorPercent)) {
            return switch (r.nextInt(3)) {
                case 0 -> new Anchor.InSlot(r.nextBoolean() ? KNOWN_SLOT : OTHER_SLOT, rot(r));
                case 1 -> new Anchor.OnSurface("n0", Side.OUTER, 0, 0);
                default -> new Anchor.Absolute(new LocalPos(INSTANCE_FAR, 0, 0), rot(r));
            };
        }
        return new Anchor.Absolute(smallPos(r), rot(r));
    }

    static PlanNode instance(String id, String type, String parent, Anchor anchor) {
        return new PlanNode(id, type, parent, anchor, Map.of(), Set.of(), "");
    }

    /**
     * A random plan of 1 to 8 nodes: plain parts and module instances of the given templates, with parents, slots and
     * connections. With {@code errorPercent} 0 nothing is wrong on purpose.
     */
    static SemanticPlan randomPlan(Random r, List<String> templateIds, int errorPercent) {
        int count = 1 + r.nextInt(MAX_PLAN_NODES);
        List<String> ids = new ArrayList<>();
        List<String> plainIds = new ArrayList<>();
        List<PlanNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "n" + i;
            List<String> parents = errorPercent == 0 ? plainIds : ids;
            String parent = !parents.isEmpty() && chance(r, PARENT_PERCENT) ? pick(r, parents) : null;
            if (chance(r, errorPercent / 2)) {
                parent = GHOST;
            }
            ids.add(id);
            if (chance(r, MODULE_PERCENT)) {
                String type = chance(r, errorPercent / 2) ? MISSING_TEMPLATE : pick(r, templateIds);
                nodes.add(instance(id, type, parent, instanceAnchor(r, errorPercent)));
            } else {
                plainIds.add(id);
                String type = pick(r, CLEAN_TYPES);
                Anchor anchor = chance(r, errorPercent)
                        ? new Anchor.InSlot(r.nextBoolean() ? KNOWN_SLOT : OTHER_SLOT, rot(r))
                        : new Anchor.Absolute(smallPos(r), rot(r));
                nodes.add(new PlanNode(id, type, parent, anchor, paramsFor(r, type, false), Set.of(), ""));
            }
        }
        List<Connection> connections = new ArrayList<>();
        if (errorPercent == 0) {
            connections.addAll(motorToShaft("k0", nodes, r));
        } else {
            int connectionCount = r.nextInt(MAX_PLAN_CONNECTIONS + 1);
            for (int i = 0; i < connectionCount; i++) {
                String to = chance(r, errorPercent) ? GHOST : pick(r, ids);
                Routing routing = r.nextBoolean() ? new Routing.Explicit(viaList(r, ids, errorPercent)) : Routing.AUTO;
                connections.add(new Connection("k" + i, new PortRef(pick(r, ids), PORT_OUT), new PortRef(to, PORT_IN), ConnKind.ROTATION,
                        routing, Constraints.NONE));
            }
        }
        return plan(nodes, connections);
    }
}
