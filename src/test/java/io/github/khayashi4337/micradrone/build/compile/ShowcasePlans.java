package io.github.khayashi4337.micradrone.build.compile;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;

import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A 9x9 building (floor height 7, walls 6 rows high) that uses every kind of opening, decoration and attachment once:
 * hangar and double doors, arch / wide-with-lattice / pane windows, a sign, a planter, a trim course, a balcony, a thick
 * wall, a slab floor, a foundation, and inside: a ladder, a lamp post and a staircase. Outside: a chimney and a dock pad.
 * Together with RandomParts (the freestanding parts) it covers every part of the registry.
 */
final class ShowcasePlans {
    static final List<String> ROOFS = List.of("gable", "hip", "flat", "shed", "sawtooth", "monitor");

    /** One side of the square footprint; the trim course uses it as its length so it spans the whole wall. */
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

    private ShowcasePlans() {
    }

    static SemanticPlan showcase(String roofKind, Facing facing) {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node("s", "micra:structure", null, 0, 0, 0,
                params("width", FOOTPRINT, "depth", FOOTPRINT, "floors", 1, "floor_height", FLOOR_HEIGHT)));
        nodes.add(node("fd", "micra:foundation", "s", 0, 0, 0, params("margin", 1)));
        nodes.add(node("f", "micra:floor", "s", 0, 0, 0, params("kind", "slab")));
        for (String side : List.of("north", "east", "south")) {
            nodes.add(node("wall-" + side.charAt(0), "micra:wall", "s", 0, 0, 0, params("side", side)));
        }
        nodes.add(node("wall-w", "micra:wall", "s", 0, 0, 0, params("side", "west", "thickness", 2)));
        nodes.add(onWall("d1", "micra:door", "s", "wall-s", Side.OUTER, HANGAR_U, 0,
                params("kind", "hangar", "width", 3, "height", 4)));
        nodes.add(onWall("d2", "micra:door", "s", "wall-s", Side.INNER, DOUBLE_DOOR_U, 0, params("kind", "double")));
        nodes.add(onWall("w1", "micra:window", "s", "wall-n", Side.OUTER, 1, 1, params("kind", "arch")));
        nodes.add(onWall("w2", "micra:window", "s", "wall-n", Side.INNER, 5, 1, params("kind", "wide", "lattice", true)));
        nodes.add(onWall("w3", "micra:window", "s", "wall-e", Side.OUTER, 3, 1, Map.of()));
        nodes.add(onWall("w4", "micra:window", "s", "wall-w", Side.OUTER, 4, 1, Map.of()));
        nodes.add(onWall("sg", "micra:sign", "s", "wall-n", Side.OUTER, 4, 1, params("text", "SHOP|OPEN")));
        nodes.add(onWall("pl", "micra:planter", "s", "wall-n", Side.OUTER, 0, 0, params("width", 1)));
        nodes.add(onWall("tr", "micra:trim", "s", "wall-n", Side.OUTER, 0, TRIM_ROW,
                params("length", FOOTPRINT, "shape", "slab")));
        nodes.add(onWall("bc", "micra:balcony", "s", "wall-e", Side.OUTER, 5, 0, params("width", 3, "depth", 2)));
        nodes.add(node("r", "micra:roof", "s", 0, 0, 0, params("kind", roofKind, "overhang", 0)));
        nodes.add(node("ld", "micra:ladder", "s", 2, 1, 2, params("height", 3, "facing", "north")));
        nodes.add(node("lp", "micra:lamp", "s", 5, 1, 5, params("kind", "post", "height", 2)));
        // u starts at 2: the doubled west wall owns u 0..1, so a staircase there would overlap it.
        nodes.add(node("st", "micra:stairs", "s", 2, 1, 6, params("steps", 3, "dir", "east")));
        nodes.add(node("ch", "micra:chimney", null, CHIMNEY_U, 0, 0, params("height", 5)));
        nodes.add(node("pad", "micra:dock_pad", null, PAD_U, 0, 0,
                params("width", 5, "depth", 5, "clearance", 4, "cargo_u", 0, "cargo_w", 0)));
        return CompileFixtures.plan(CompileFixtures.site(facing), StyleSpec.EMPTY, nodes);
    }
}
