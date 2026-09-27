package io.github.khayashi4337.micradrone.build.compile;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Two structures: a 9x9 building (floor height 7, walls 6 rows high) that uses every kind of opening,
 * decoration and attachment once — hangar and double doors, arch / wide-with-lattice / pane windows, a sign, a
 * planter, a horizontal and a vertical trim course, a balcony, a doubled wall, a slab floor, a foundation, and
 * inside: a ladder, a lamp post and a staircase — and a 7x7 two-storey building that carries the less common
 * parameter values (a two-storey structure, a triple-thick wall, a partial half wall, a right-hinged single
 * door and a rail-less balcony). Outside: a chimney and a dock pad. Together with RandomParts (the
 * freestanding parts) it covers every part of the registry and every declared enum value.
 */
final class ShowcasePlans {
    static final List<String> ROOFS = List.of("gable", "hip", "flat", "shed", "sawtooth", "monitor");

    /** One side of the main square footprint; the trim course uses it as its length so it spans the whole wall. */
    private static final int FOOTPRINT = 9;
    private static final int FLOOR_HEIGHT = 7;
    /** Row of the horizontal trim course: the top wall row (walls are FLOOR_HEIGHT - 1 rows). */
    private static final int TRIM_ROW = 5;
    /** Surface position of the hangar door on the south wall. */
    private static final int HANGAR_U = 1;
    /** Surface position of the double door on the south wall, clear of the hangar door. */
    private static final int DOUBLE_DOOR_U = 6;
    /** Local u of the chimney and the dock pad, outside the footprint. */
    private static final int CHIMNEY_U = 12;
    private static final int PAD_U = 20;
    /** The second structure stands far enough east that nothing else reaches it. */
    private static final int S2_U = 40;
    private static final int S2_SIDE = 7;
    private static final int S2_FLOOR_HEIGHT = 5;

    private ShowcasePlans() {
    }

    static SemanticPlan showcase(String roofKind, Facing facing) {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node("s", "micra:structure", null, 0, 0, 0,
                params(PartParams.WIDTH, FOOTPRINT, PartParams.DEPTH, FOOTPRINT, PartParams.FLOORS, 1,
                        PartParams.FLOOR_HEIGHT, FLOOR_HEIGHT)));
        nodes.add(node("fd", "micra:foundation", "s", 0, 0, 0, params(PartParams.MARGIN, 1)));
        nodes.add(node("f", "micra:floor", "s", 0, 0, 0, params(PartParams.KIND, "slab")));
        for (String side : List.of("north", "east", "south")) {
            nodes.add(node("wall-" + side.charAt(0), "micra:wall", "s", 0, 0, 0, params(PartParams.SIDE, side)));
        }
        nodes.add(node("wall-w", "micra:wall", "s", 0, 0, 0,
                params(PartParams.SIDE, "west", PartParams.THICKNESS, 2)));
        nodes.add(onWall("d1", "micra:door", "s", "wall-s", Side.OUTER, HANGAR_U, 0,
                params(PartParams.KIND, "hangar", PartParams.WIDTH, 3, PartParams.HEIGHT, 4)));
        nodes.add(onWall("d2", "micra:door", "s", "wall-s", Side.INNER, DOUBLE_DOOR_U, 0,
                params(PartParams.KIND, "double")));
        nodes.add(onWall("w1", "micra:window", "s", "wall-n", Side.OUTER, 1, 1, params(PartParams.KIND, "arch")));
        nodes.add(onWall("w2", "micra:window", "s", "wall-n", Side.INNER, 5, 1,
                params(PartParams.KIND, "wide", PartParams.LATTICE, true)));
        nodes.add(onWall("w3", "micra:window", "s", "wall-e", Side.OUTER, 3, 1, Map.of()));
        nodes.add(onWall("w4", "micra:window", "s", "wall-w", Side.OUTER, 4, 1, Map.of()));
        nodes.add(onWall("sg", "micra:sign", "s", "wall-n", Side.OUTER, 4, 1,
                params(PartParams.TEXT, "SHOP|OPEN")));
        nodes.add(onWall("pl", "micra:planter", "s", "wall-n", Side.OUTER, 0, 0, params(PartParams.WIDTH, 1)));
        nodes.add(onWall("tr", "micra:trim", "s", "wall-n", Side.OUTER, 0, TRIM_ROW,
                params(PartParams.LENGTH, FOOTPRINT, PartParams.SHAPE, "slab")));
        nodes.add(onWall("tr2", "micra:trim", "s", "wall-e", Side.OUTER, 2, 1,
                params(PartParams.LENGTH, 3, PartParams.AXIS, "vertical")));
        nodes.add(onWall("bc", "micra:balcony", "s", "wall-e", Side.OUTER, 5, 0,
                params(PartParams.WIDTH, 3, PartParams.DEPTH, 2)));
        nodes.add(node("r", "micra:roof", "s", 0, 0, 0, roofParams(roofKind)));
        nodes.add(node("ld", "micra:ladder", "s", 2, 1, 2,
                params(PartParams.HEIGHT, 3, PartParams.FACING, "north")));
        nodes.add(node("lp", "micra:lamp", "s", 5, 1, 5, params(PartParams.KIND, "post", PartParams.HEIGHT, 2)));
        // u starts at 2: the doubled west wall owns u 0..1, so a staircase there would overlap it.
        nodes.add(node("st", "micra:stairs", "s", 2, 1, 6, params(PartParams.STEPS, 3, PartParams.DIR, "east")));
        nodes.add(node("ch", "micra:chimney", null, CHIMNEY_U, 0, 0, params(PartParams.HEIGHT, 5)));
        nodes.add(node("pad", "micra:dock_pad", null, PAD_U, 0, 0,
                params(PartParams.WIDTH, 5, PartParams.DEPTH, 5, PartParams.CLEARANCE, 4,
                        PartParams.CARGO_U, 0, PartParams.CARGO_W, 0)));
        nodes.addAll(secondStructure());
        return CompileFixtures.plan(CompileFixtures.site(facing), StyleSpec.EMPTY, nodes);
    }

    /**
     * The showcase's roof parameters: each kind also carries the values that no other node ever sets —
     * {@code ridge} both ways, {@code high_side} on the three remaining directions, the narrowest
     * {@code tooth}, a non-default monitor slit and a gable with its ends left open.
     */
    private static Map<String, ParamValue> roofParams(String roofKind) {
        return switch (roofKind) {
            case "gable" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.RIDGE, PartParams.AXIS_U, PartParams.HIGH_SIDE, "north",
                    PartParams.GABLE_FILL, false);
            case "hip" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.RIDGE, PartParams.AXIS_W);
            case "flat" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.HIGH_SIDE, "south");
            case "shed" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.HIGH_SIDE, "west");
            case "sawtooth" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.TOOTH, 2);
            case "monitor" -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0,
                    PartParams.MONITOR_WIDTH, 3, PartParams.MONITOR_HEIGHT, 2);
            default -> params(PartParams.KIND, roofKind, PartParams.OVERHANG, 0);
        };
    }

    /**
     * The two-storey building carries the values the main structure never uses: {@code floors} above one, a
     * triple-thick wall, a partial {@code half} wall, a {@code right}-hinged single door and a rail-less
     * balcony.
     */
    private static List<PlanNode> secondStructure() {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node("s2", "micra:structure", null, S2_U, 0, 0,
                params(PartParams.WIDTH, S2_SIDE, PartParams.DEPTH, S2_SIDE, PartParams.FLOORS, 2,
                        PartParams.FLOOR_HEIGHT, S2_FLOOR_HEIGHT)));
        nodes.add(node("f2", "micra:floor", "s2", 0, 0, 0, Map.of()));
        nodes.add(node("w2s", "micra:wall", "s2", 0, 0, 0,
                params(PartParams.SIDE, "south", PartParams.THICKNESS, 3)));
        nodes.add(node("w2n", "micra:wall", "s2", 0, 0, 0,
                params(PartParams.SIDE, "north", PartParams.PART, "half",
                        PartParams.FROM, 1, PartParams.LENGTH, 3)));
        nodes.add(onWall("d3", "micra:door", "s2", "w2s", Side.OUTER, 1, 0,
                params(PartParams.KIND, "single", PartParams.WIDTH, 3, PartParams.HEIGHT, 3,
                        PartParams.HINGE, "right")));
        nodes.add(onWall("b2", "micra:balcony", "s2", "w2s", Side.OUTER, 4, 0,
                params(PartParams.WIDTH, 2, PartParams.DEPTH, 1, PartParams.RAIL, false)));
        return nodes;
    }
}
