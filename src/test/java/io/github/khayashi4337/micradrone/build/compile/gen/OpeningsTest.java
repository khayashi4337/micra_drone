package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpeningsTest {
    private static final PartTypeRegistry REGISTRY = CompileFixtures.REGISTRY;

    private static final String DOOR = "micra:door";
    private static final String WINDOW = "micra:window";
    private static final String WALL = "micra:wall";
    private static final String STRUCTURE_ID = "s";
    private static final String WALL_N = "wall-n";
    private static final String WALL_E = "wall-e";
    private static final String WALL_S = "wall-s";
    private static final String WALL_W = "wall-w";
    /** The south wall of the second storey. */
    private static final String WALL_S_UPPER = "wall-s1";
    /** The two segments of a south wall that is built in pieces. */
    private static final String WALL_SEGMENT_A = "wall-a";
    private static final String WALL_SEGMENT_B = "wall-b";
    private static final String DOOR_ID = "d";
    private static final String WINDOW_ID = "w";

    private static final String OAK_DOOR = "minecraft:oak_door";
    private static final String SPRUCE_DOOR = "minecraft:spruce_door";
    private static final String OAK_FENCE_GATE = "minecraft:oak_fence_gate";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String STONE_BRICK_STAIRS = "minecraft:stone_brick_stairs";
    private static final String BRICKS = "minecraft:bricks";
    private static final String BRICK_STAIRS = "minecraft:brick_stairs";
    private static final String GLASS_PANE = "minecraft:glass_pane";
    private static final String GLASS = "minecraft:glass";
    private static final String BEDROCK = "minecraft:bedrock";
    /** A role name (no namespace) that the palette does not have. */
    private static final String UNKNOWN_ROLE = "no_such_role";
    /** A door kind that the part does not have. */
    private static final String UNKNOWN_KIND = "gangway";

    // Palette roles the tests override.
    private static final String ROLE_DOOR = "door";
    private static final String ROLE_GATE = "gate";
    private static final String ROLE_GLASS = "glass";
    private static final String ROLE_TRIM = "trim";

    // Parameter names and values of the parts under test, and of the wall.
    private static final String P_KIND = "kind";
    private static final String P_HINGE = "hinge";
    private static final String P_WIDTH = "width";
    private static final String P_HEIGHT = "height";
    private static final String P_LATTICE = "lattice";
    private static final String P_MATERIAL = "material";
    private static final String P_SIDE = "side";
    private static final String P_THICKNESS = "thickness";
    private static final String P_LEVEL = "level";
    private static final String P_FROM = "from";
    private static final String P_LENGTH = "length";
    private static final String KIND_DOUBLE = "double";
    private static final String KIND_HANGAR = "hangar";
    private static final String KIND_WIDE = "wide";
    private static final String KIND_ARCH = "arch";

    // Block-state property names and values, and the four directions, as they are written in a block state.
    private static final String PROP_FACING = "facing";
    private static final String PROP_HALF = "half";
    private static final String PROP_HINGE = "hinge";
    private static final String LOWER = "lower";
    private static final String UPPER = "upper";
    private static final String TOP = "top";
    private static final String LEFT = "left";
    private static final String RIGHT = "right";
    private static final String NORTH = "north";
    private static final String EAST = "east";
    private static final String SOUTH = "south";
    private static final String WEST = "west";

    /**
     * The hall: 7x7, one floor, floor height 4. Its walls are 3 rows high (v=1..3, the floor takes v=0) and 7 cells long;
     * the south wall is at w=0, the north wall at w=6, the west wall at u=0 and the east wall at u=6.
     */
    private static final int HALL_SIDE = 7;
    private static final int FLOORS = 1;
    private static final int TWO_FLOORS = 2;
    private static final int UPPER_STOREY = 1;
    /** Where the south wall of the split hall changes segment: segment A is u=0..2, segment B is u=3..6. */
    private static final int SPLIT_AT = 3;
    /** The first u of the south wall of the partial hall (its wall does not start at the corner). */
    private static final int PARTIAL_FROM = 2;
    private static final int HALL_FLOOR_HEIGHT = 4;
    private static final int HALL_WALL_ROWS = HALL_FLOOR_HEIGHT - 1;
    /** A ground-storey wall one row taller than usual: its top row touches the upper storey's wall. */
    private static final int TALL_WALL_ROWS = HALL_FLOOR_HEIGHT;
    /** The four walls share their corner columns: 4 x 7 - 4 columns stand around the hall. */
    private static final int HALL_PERIMETER_COLUMNS = 4 * HALL_SIDE - 4;
    private static final int HALL_WALL_CELLS = HALL_PERIMETER_COLUMNS * HALL_WALL_ROWS;
    /** The hangar hall: 9x9, floor height 7, so its walls are 6 rows high. */
    private static final int HANGAR_SIDE = 9;
    private static final int HANGAR_FLOOR_HEIGHT = 7;
    private static final int HANGAR_WALL_ROWS = HANGAR_FLOOR_HEIGHT - 1;
    private static final int HANGAR_PERIMETER_COLUMNS = 4 * HANGAR_SIDE - 4;
    /** The v of the lowest wall row: the floor takes v=0. */
    private static final int V_FIRST = 1;
    /** Where the double doors of the hinge table stand along their wall: the leaves are at DOOR_AT and DOOR_AT + 1. */
    private static final int DOOR_AT = 2;
    private static final int MAX_CELLS = 1_000;

    private static final StyleSpec NO_STYLE = StyleSpec.EMPTY;

    private static PlanNode on(String id, String type, String wall, Side side, int u, int v, Map<String, ParamValue> p) {
        return onWall(id, type, STRUCTURE_ID, wall, side, u, v, p);
    }

    private static List<PlanNode> withOpening(PlanNode opening) {
        return hallWith(List.of(opening));
    }

    private static List<PlanNode> hallWith(List<PlanNode> openings) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.addAll(openings);
        return nodes;
    }

    /** The hall with its south wall given a thickness. */
    private static List<PlanNode> hallWithSouthWall(int thickness) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.replaceAll(n -> n.id().equals(WALL_S)
                ? node(WALL_S, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, SOUTH, P_THICKNESS, thickness)) : n);
        return nodes;
    }

    private static List<PlanNode> hallWithoutSouthWall() {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.removeIf(n -> n.id().equals(WALL_S));
        return nodes;
    }

    private static PlanNode southSegment(String id, int from, int length) {
        return node(id, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, SOUTH, P_FROM, from, P_LENGTH, length));
    }

    /** The hall whose south wall is built in two segments, u=0..2 and u=3..6. */
    private static List<PlanNode> hallWithSplitSouthWall() {
        List<PlanNode> nodes = hallWithoutSouthWall();
        nodes.add(southSegment(WALL_SEGMENT_A, 0, SPLIT_AT));
        nodes.add(southSegment(WALL_SEGMENT_B, SPLIT_AT, HALL_SIDE - SPLIT_AT));
        return nodes;
    }

    /** Two storeys, the ground wall on the south side one row taller than usual, and the upper storey's south wall above it. */
    private static List<PlanNode> hallWithTallGroundWall() {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, TWO_FLOORS, HALL_FLOOR_HEIGHT));
        nodes.replaceAll(n -> n.id().equals(WALL_S)
                ? node(WALL_S, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, SOUTH, P_HEIGHT, TALL_WALL_ROWS)) : n);
        nodes.add(node(WALL_S_UPPER, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, SOUTH, P_LEVEL, UPPER_STOREY)));
        return nodes;
    }

    private static Map<LocalPos, BlockSpec> buildIn(List<PlanNode> nodes, PlanNode... openings) {
        List<PlanNode> all = new ArrayList<>(nodes);
        all.addAll(List.of(openings));
        CompileResult r = compile(all);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    /** The hall with the given openings, walls only. */
    private static Map<LocalPos, BlockSpec> build(PlanNode... openings) {
        return buildIn(hallWith(List.of()), openings);
    }

    private static List<String> ids(CompileResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    private static CompileResult compileWith(StyleSpec style, PlanNode opening) {
        return compile(style, withOpening(opening));
    }

    private static StyleSpec roleIs(String role, String block) {
        return new StyleSpec(Map.of(role, block), Set.of());
    }

    private static String refused(String partId) {
        return "E-BLOCK-FORBIDDEN:" + partId + "#" + BEDROCK;
    }

    private static BlockSpec doorBlock(String facing, String half, String hinge) {
        return BlockSpec.of(OAK_DOOR, PROP_FACING, facing, PROP_HALF, half, PROP_HINGE, hinge);
    }

    private static BlockSpec gate(String facing) {
        return BlockSpec.of(OAK_FENCE_GATE, PROP_FACING, facing);
    }

    /** An upside-down stair with its back to {@code back}: the arch's corner. */
    private static BlockSpec archStair(String stairsId, String back) {
        return BlockSpec.of(stairsId, PROP_FACING, back, PROP_HALF, TOP);
    }

    private static BlockSpec archStair(String back) {
        return archStair(STONE_BRICK_STAIRS, back);
    }

    private static BlockSpec bricks() {
        return BlockSpec.of(BRICKS);
    }

    private static BlockSpec stoneBricks() {
        return BlockSpec.of(STONE_BRICKS);
    }

    private static BlockSpec glassPane() {
        return BlockSpec.of(GLASS_PANE);
    }

    @Test
    void aSingleDoorReplacesTwoWallCellsAndFacesIndoors() {
        Map<LocalPos, BlockSpec> c = build(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, Map.of()));
        // south wall is at w=0 facing south; indoors is north
        assertEquals(doorBlock(NORTH, LOWER, LEFT), c.get(new LocalPos(3, 1, 0)));
        assertEquals(doorBlock(NORTH, UPPER, LEFT), c.get(new LocalPos(3, 2, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 3, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(2, 1, 0)));
        assertEquals(HALL_WALL_CELLS, c.size(), "the door takes the place of wall cells: the total does not change");
    }

    @Test
    void anInnerDoorFacesOutdoorsAndTheHingeCanBeRight() {
        Map<LocalPos, BlockSpec> c = build(on(DOOR_ID, DOOR, WALL_S, Side.INNER, 3, 0, params(P_HINGE, RIGHT)));
        assertEquals(doorBlock(SOUTH, LOWER, RIGHT), c.get(new LocalPos(3, 1, 0)));
        assertEquals(doorBlock(SOUTH, UPPER, RIGHT), c.get(new LocalPos(3, 2, 0)));
    }

    @Test
    void aDoubleDoorHasTwoLeavesHingedOnTheOutsideEdges() {
        Map<LocalPos, BlockSpec> c = build(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0, params(P_KIND, KIND_DOUBLE)));
        assertEquals(LEFT, c.get(new LocalPos(2, 1, 0)).get(PROP_HINGE));
        assertEquals(RIGHT, c.get(new LocalPos(3, 1, 0)).get(PROP_HINGE));
        assertEquals(4, countOf(c, OAK_DOOR));
    }

    /** A double door on a wall: which way it faces and how each of its two leaves is hinged. */
    private record HingeCase(String wall, Side side, String facing, String firstLeafHinge, String secondLeafHinge) {
    }

    /** The lower half of leaf k of a double door at DOOR_AT on the hall: leaf 0 is the one nearer the wall's start. */
    private static LocalPos leaf(String wall, int k) {
        return switch (wall) {
            case WALL_S -> new LocalPos(DOOR_AT + k, V_FIRST, 0);
            case WALL_N -> new LocalPos(DOOR_AT + k, V_FIRST, HALL_SIDE - 1);
            case WALL_E -> new LocalPos(HALL_SIDE - 1, V_FIRST, DOOR_AT + k);
            default -> new LocalPos(0, V_FIRST, DOOR_AT + k);
        };
    }

    @Test
    void theOutsideEdgeRuleHoldsOnEveryWall() {
        Map<LocalPos, BlockSpec> north = build(on(DOOR_ID, DOOR, WALL_N, Side.OUTER, 2, 0, params(P_KIND, KIND_DOUBLE)));
        // north wall: indoors is south and the wall grows toward +u, so the first leaf sits on the west edge and hinges right
        assertEquals(RIGHT, north.get(new LocalPos(2, 1, 6)).get(PROP_HINGE));
        assertEquals(LEFT, north.get(new LocalPos(3, 1, 6)).get(PROP_HINGE));

        // Worked out by walking through the door: the walker heads the way the door faces, and the first leaf (nearer the
        // wall's start) is hinged on the walker's left when the wall's start is on the walker's left, else on the right.
        // The walls grow toward +u (south, north) or +w (east, west); an INNER door faces the other way.
        List<HingeCase> table = List.of(
                new HingeCase(WALL_S, Side.OUTER, NORTH, LEFT, RIGHT),   // heads north, the start (west) is on the left
                new HingeCase(WALL_S, Side.INNER, SOUTH, RIGHT, LEFT),   // heads south, the start (west) is on the right
                new HingeCase(WALL_N, Side.OUTER, SOUTH, RIGHT, LEFT),
                new HingeCase(WALL_N, Side.INNER, NORTH, LEFT, RIGHT),
                new HingeCase(WALL_E, Side.OUTER, WEST, LEFT, RIGHT),    // heads west, the start (south) is on the left
                new HingeCase(WALL_E, Side.INNER, EAST, RIGHT, LEFT),    // heads east, the start (south) is on the right
                new HingeCase(WALL_W, Side.OUTER, EAST, RIGHT, LEFT),
                new HingeCase(WALL_W, Side.INNER, WEST, LEFT, RIGHT));
        for (HingeCase h : table) {
            Map<LocalPos, BlockSpec> c = build(on(DOOR_ID, DOOR, h.wall(), h.side(), DOOR_AT, 0, params(P_KIND, KIND_DOUBLE)));
            for (int k = 0; k < 2; k++) {
                String hinge = k == 0 ? h.firstLeafHinge() : h.secondLeafHinge();
                LocalPos lower = leaf(h.wall(), k);
                assertEquals(doorBlock(h.facing(), LOWER, hinge), c.get(lower), h + " leaf " + k + " lower");
                assertEquals(doorBlock(h.facing(), UPPER, hinge), c.get(lower.plus(0, 1, 0)), h + " leaf " + k + " upper");
            }
        }
    }

    @Test
    void theHingeParameterOnlyTurnsASingleDoor() {
        Map<LocalPos, BlockSpec> c = build(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0, params(P_KIND, KIND_DOUBLE, P_HINGE, RIGHT)));
        assertEquals(LEFT, c.get(new LocalPos(2, 1, 0)).get(PROP_HINGE), "the outside edges decide a double door");
        assertEquals(RIGHT, c.get(new LocalPos(3, 1, 0)).get(PROP_HINGE));
    }

    @Test
    void aHangarDoorIsAnOpeningWithTwoRowsOfGates() {
        List<PlanNode> nodes = new ArrayList<>(shell(HANGAR_SIDE, HANGAR_SIDE, FLOORS, HANGAR_FLOOR_HEIGHT)); // walls are 6 rows high
        nodes.add(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, params(P_KIND, KIND_HANGAR, P_WIDTH, 3, P_HEIGHT, 4)));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals(6, countOf(c, OAK_FENCE_GATE));
        assertEquals(gate(NORTH), c.get(new LocalPos(4, 1, 0)));
        assertNull(c.get(new LocalPos(4, 3, 0)), "above the gates the opening is empty");
        assertNull(c.get(new LocalPos(4, 4, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(4, 5, 0)));
    }

    @Test
    void aHangarDoorOpensExactlyItsSizeAndFacesTheWayOfItsSide() {
        int width = 3;
        int height = 4;
        int gateRows = 2;
        List<PlanNode> nodes = new ArrayList<>(shell(HANGAR_SIDE, HANGAR_SIDE, FLOORS, HANGAR_FLOOR_HEIGHT));
        nodes.add(on(DOOR_ID, DOOR, WALL_S, Side.INNER, 3, 0, params(P_KIND, KIND_HANGAR, P_WIDTH, width, P_HEIGHT, height)));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        // width x height wall cells are taken out and width x gateRows gates come back
        assertEquals(HANGAR_PERIMETER_COLUMNS * HANGAR_WALL_ROWS - width * height + width * gateRows, c.size());
        assertEquals(gate(SOUTH), c.get(new LocalPos(3, 1, 0)), "an inner opening faces outdoors");
        assertEquals(gate(SOUTH), c.get(new LocalPos(5, 2, 0)));
        assertNull(c.get(new LocalPos(5, 3, 0)));
        assertNull(c.get(new LocalPos(3, 4, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(2, 1, 0)), "one cell left of the opening");
        assertEquals(stoneBricks(), c.get(new LocalPos(6, 1, 0)), "one cell right of the opening");
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 5, 0)), "one row above the opening");
    }

    @Test
    void theMaterialParameterPicksTheBlocksButAHangarUsesTheGateRole() {
        Map<LocalPos, BlockSpec> single = build(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, params(P_MATERIAL, SPRUCE_DOOR)));
        assertEquals(BlockSpec.of(SPRUCE_DOOR, PROP_FACING, NORTH, PROP_HALF, LOWER, PROP_HINGE, LEFT),
                single.get(new LocalPos(3, 1, 0)));
        Map<LocalPos, BlockSpec> hangar = build(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0,
                params(P_KIND, KIND_HANGAR, P_WIDTH, 3, P_HEIGHT, 3, P_MATERIAL, SPRUCE_DOOR)));
        assertEquals(6, countOf(hangar, OAK_FENCE_GATE), "the gates come from the palette role gate, not from the door material");
        assertEquals(0, countOf(hangar, SPRUCE_DOOR));
        Map<LocalPos, BlockSpec> plainGlass = build(on(WINDOW_ID, WINDOW, WALL_S, Side.OUTER, 3, 1, params(P_MATERIAL, GLASS)));
        assertEquals(2, countOf(plainGlass, GLASS));
    }

    @Test
    void windowsPaneWideAndArch() {
        Map<LocalPos, BlockSpec> pane = build(on(WINDOW_ID, WINDOW, WALL_E, Side.OUTER, 3, 1, Map.of()));
        // east wall is at u=6 and grows along +w; window at w=3, rows 1..2 = v 2..3
        assertEquals(glassPane(), pane.get(new LocalPos(6, 2, 3)));
        assertEquals(glassPane(), pane.get(new LocalPos(6, 3, 3)));
        assertEquals(stoneBricks(), pane.get(new LocalPos(6, 1, 3)));
        assertEquals(2, countOf(pane, GLASS_PANE));
        assertEquals(HALL_WALL_CELLS, pane.size(), "the window takes the place of wall cells: the total does not change");

        Map<LocalPos, BlockSpec> wide = build(on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE)));
        assertEquals(6, countOf(wide, GLASS_PANE));
        assertEquals(glassPane(), wide.get(new LocalPos(2, 2, 6)));
        assertEquals(glassPane(), wide.get(new LocalPos(4, 3, 6)));
        assertEquals(stoneBricks(), wide.get(new LocalPos(1, 2, 6)), "left of the window");
        assertEquals(stoneBricks(), wide.get(new LocalPos(5, 2, 6)), "right of the window");
        assertEquals(stoneBricks(), wide.get(new LocalPos(3, 1, 6)), "below the window");
        assertEquals(HALL_WALL_CELLS, wide.size());

        Map<LocalPos, BlockSpec> arch = build(on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 1, 0, params(P_KIND, KIND_ARCH)));
        assertEquals(7, countOf(arch, GLASS_PANE), "two full rows of three, and the middle of the top row");
        assertEquals(archStair(WEST), arch.get(new LocalPos(1, 3, 6)));
        assertEquals(archStair(EAST), arch.get(new LocalPos(3, 3, 6)));
        assertEquals(glassPane(), arch.get(new LocalPos(2, 3, 6)));
        assertEquals(HALL_WALL_CELLS, arch.size());
    }

    @Test
    void archCornersOnAnEastWallFaceSouthAndNorth() {
        Map<LocalPos, BlockSpec> arch = build(on(WINDOW_ID, WINDOW, WALL_E, Side.OUTER, 1, 0, params(P_KIND, KIND_ARCH)));
        // east wall grows along +w: the left corner (smaller w) faces south (-w), the right corner north (+w)
        assertEquals(SOUTH, arch.get(new LocalPos(6, 3, 1)).get(PROP_FACING));
        assertEquals(NORTH, arch.get(new LocalPos(6, 3, 3)).get(PROP_FACING));
        assertEquals(TOP, arch.get(new LocalPos(6, 3, 1)).get(PROP_HALF), "the corner stairs are upside down");
    }

    @Test
    void aLatticeReplacesTheMiddleColumnWithTrim() {
        Map<LocalPos, BlockSpec> c = build(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE, P_LATTICE, true)));
        assertEquals(4, countOf(c, GLASS_PANE));
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 2, 6)), "the mullion is the trim material");
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 3, 6)));
        CompileResult onPane = compile(withOpening(on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_LATTICE, true))));
        assertEquals(List.of("E-PARAM-RANGE:" + WINDOW_ID + "#lattice"), ids(onPane), "a one-wide pane has no middle column");
    }

    private static Map<LocalPos, BlockSpec> buildWithTrim(PlanNode opening) {
        CompileResult r = compileWith(roleIs(ROLE_TRIM, BRICKS), opening);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    @Test
    void aLatticeMakesTheWholeMiddleColumnTrimOnEveryWindowKind() {
        // the trim is bricks here, so a mullion can be told from the stone-brick wall around it
        Map<LocalPos, BlockSpec> arch = buildWithTrim(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 1, 0, params(P_KIND, KIND_ARCH, P_LATTICE, true)));
        // the arch is u=1..3 and v=1..3 on the north wall (w=6): its middle column is u=2, in all three rows
        for (int v = V_FIRST; v < V_FIRST + HALL_WALL_ROWS; v++) {
            assertEquals(bricks(), arch.get(new LocalPos(2, v, 6)), "the middle column at v=" + v);
        }
        assertEquals(HALL_WALL_ROWS, countOf(arch, BRICKS));
        assertEquals(4, countOf(arch, GLASS_PANE), "the two side columns, two rows each");
        assertEquals(archStair(BRICK_STAIRS, WEST), arch.get(new LocalPos(1, 3, 6)));
        assertEquals(archStair(BRICK_STAIRS, EAST), arch.get(new LocalPos(3, 3, 6)));
        assertEquals(2, countOf(arch, BRICK_STAIRS));
        assertEquals(HALL_WALL_CELLS, arch.size());

        Map<LocalPos, BlockSpec> plainArch = buildWithTrim(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 1, 0, params(P_KIND, KIND_ARCH)));
        assertEquals(7, countOf(plainArch, GLASS_PANE), "without a lattice the middle of the top row is glass");
        assertEquals(glassPane(), plainArch.get(new LocalPos(2, 3, 6)));
        assertEquals(0, countOf(plainArch, BRICKS));
        assertEquals(2, countOf(plainArch, BRICK_STAIRS));
        assertEquals(HALL_WALL_CELLS, plainArch.size());

        // the wide window is u=2..4 and v=2..3: its middle column is u=3, in both rows
        Map<LocalPos, BlockSpec> wide = buildWithTrim(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE, P_LATTICE, true)));
        assertEquals(bricks(), wide.get(new LocalPos(3, 2, 6)));
        assertEquals(bricks(), wide.get(new LocalPos(3, 3, 6)));
        assertEquals(2, countOf(wide, BRICKS));
        assertEquals(4, countOf(wide, GLASS_PANE));
        assertEquals(HALL_WALL_CELLS, wide.size());

        Map<LocalPos, BlockSpec> plainWide = buildWithTrim(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE)));
        assertEquals(6, countOf(plainWide, GLASS_PANE));
        assertEquals(0, countOf(plainWide, BRICKS));
    }

    @Test
    void openingsThatReachOutsideTheWallAreRefused() {
        assertEquals(List.of("E-OPENING-NO-WALL:" + DOOR_ID), ids(compile(withOpening(
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 6, 0, params(P_KIND, KIND_DOUBLE))))), "the second leaf would be past the end");
        assertEquals(List.of("E-OPENING-NO-WALL:" + WINDOW_ID), ids(compile(withOpening(
                on(WINDOW_ID, WINDOW, WALL_S, Side.OUTER, 2, 2, params(P_KIND, KIND_ARCH))))), "the top row is above the wall");
        assertEquals(List.of("E-OPENING-NO-WALL:" + DOOR_ID + "#anchor"), ids(compile(withOpening(
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 9, 0, Map.of())))), "the anchor itself is off the wall");
    }

    /** Runs the opening's generator, expects the refusal {@code expectedId}, and checks that nothing was carved or placed. */
    private static void assertRefusedUntouched(GenContext ctx, PlanNode opening, String expectedId) {
        int before = ctx.canvas().size();
        GenAbort refusal = assertThrows(GenAbort.class, () -> generatorOf(opening.type()).generate(ctx, opening, resolved(opening)));
        assertEquals(expectedId, refusal.issue().id());
        assertEquals(before, ctx.canvas().size(), "nothing was carved out of the wall or placed");
        assertTrue(ctx.canvas().all().stream().noneMatch(cell -> cell.ownerId().equals(opening.id())),
                "no block of the refused opening was placed");
    }

    @Test
    void anOpeningCannotReachIntoTheNextWallSegmentOfTheSameBuilding() {
        // The south wall is two segments. A double door at u=2 of segment A has its second leaf at u=3, which is a cell of
        // segment B: a wall cell of this very building, but not of the wall the door is on.
        PlanNode straddling = on(DOOR_ID, DOOR, WALL_SEGMENT_A, Side.OUTER, SPLIT_AT - 1, 0, params(P_KIND, KIND_DOUBLE));
        List<PlanNode> nodes = hallWithSplitSouthWall();
        nodes.add(straddling);
        assertEquals(List.of("E-OPENING-NO-WALL:" + DOOR_ID), ids(compile(nodes)));
        assertRefusedUntouched(generatedIn(hallWithSplitSouthWall(), Map.of()), straddling, "E-OPENING-NO-WALL:" + DOOR_ID);

        // each segment on its own takes an opening, and a position counts from the start of the segment it is on
        Map<LocalPos, BlockSpec> c = buildIn(hallWithSplitSouthWall(),
                on(DOOR_ID, DOOR, WALL_SEGMENT_A, Side.OUTER, SPLIT_AT - 2, 0, params(P_KIND, KIND_DOUBLE)),
                on(WINDOW_ID, WINDOW, WALL_SEGMENT_B, Side.OUTER, 0, 1, params(P_KIND, KIND_WIDE)));
        assertEquals(LOWER, c.get(new LocalPos(1, 1, 0)).get(PROP_HALF));
        assertEquals(LOWER, c.get(new LocalPos(2, 1, 0)).get(PROP_HALF));
        assertEquals(glassPane(), c.get(new LocalPos(3, 2, 0)), "u=0 of segment B is u=3 of the building");
        assertEquals(glassPane(), c.get(new LocalPos(5, 3, 0)));
        assertEquals(6, countOf(c, GLASS_PANE));
        assertEquals(HALL_WALL_CELLS, c.size());
    }

    @Test
    void anOpeningCannotReachIntoTheWallOfTheStoreyAbove() {
        // The ground wall is 4 rows (v=1..4) and the upper storey's wall starts at v=5. A hangar door of 5 rows from the
        // bottom has its top row in the wall above: one row too many.
        int width = 3;
        PlanNode tooTall = on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0,
                params(P_KIND, KIND_HANGAR, P_WIDTH, width, P_HEIGHT, TALL_WALL_ROWS + 1));
        List<PlanNode> nodes = hallWithTallGroundWall();
        nodes.add(tooTall);
        assertEquals(List.of("E-OPENING-NO-WALL:" + DOOR_ID), ids(compile(nodes)));
        assertRefusedUntouched(generatedIn(hallWithTallGroundWall(), Map.of()), tooTall, "E-OPENING-NO-WALL:" + DOOR_ID);

        // as tall as the wall itself is fine, and the wall above is left alone
        Map<LocalPos, BlockSpec> c = buildIn(hallWithTallGroundWall(),
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0, params(P_KIND, KIND_HANGAR, P_WIDTH, width, P_HEIGHT, TALL_WALL_ROWS)));
        assertEquals(gate(NORTH), c.get(new LocalPos(2, 1, 0)));
        assertNull(c.get(new LocalPos(2, 4, 0)), "the opening's top row, above the gates, is empty");
        assertEquals(stoneBricks(), c.get(new LocalPos(2, 5, 0)), "the upper storey's wall is untouched");
    }

    @Test
    void aPositionOnAPartialWallCountsFromItsStart() {
        // the south wall starts at u=2, so its position 0 is the cell u=2 of the building and its last position is u=6
        List<PlanNode> nodes = hallWithoutSouthWall();
        nodes.add(southSegment(WALL_S, PARTIAL_FROM, HALL_SIDE - PARTIAL_FROM));
        Map<LocalPos, BlockSpec> c = buildIn(nodes,
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 0, 0, Map.of()),
                on(WINDOW_ID, WINDOW, WALL_S, Side.OUTER, HALL_SIDE - PARTIAL_FROM - 1, 1, Map.of()));
        assertEquals(doorBlock(NORTH, LOWER, LEFT), c.get(new LocalPos(2, 1, 0)));
        assertEquals(doorBlock(NORTH, UPPER, LEFT), c.get(new LocalPos(2, 2, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 1, 0)));
        assertNull(c.get(new LocalPos(1, 1, 0)), "the wall does not reach back before its start");
        assertEquals(glassPane(), c.get(new LocalPos(6, 2, 0)), "the last position of the wall is the last cell");
        assertEquals(glassPane(), c.get(new LocalPos(6, 3, 0)));
    }

    @Test
    void anOpeningThatEndsExactlyAtTheWallsEndIsAccepted() {
        Map<LocalPos, BlockSpec> c = build(
                // u=5 and u=6: the last two cells of the south wall
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, HALL_SIDE - 2, 0, params(P_KIND, KIND_DOUBLE)),
                // u=4..6 and all three rows of the north wall
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, HALL_SIDE - 3, 0, params(P_KIND, KIND_ARCH)));
        assertEquals(4, countOf(c, OAK_DOOR));
        assertEquals(doorBlock(NORTH, LOWER, LEFT), c.get(new LocalPos(5, 1, 0)));
        assertEquals(doorBlock(NORTH, LOWER, RIGHT), c.get(new LocalPos(6, 1, 0)));
        assertEquals(7, countOf(c, GLASS_PANE));
        assertEquals(archStair(WEST), c.get(new LocalPos(4, 3, 6)));
        assertEquals(archStair(EAST), c.get(new LocalPos(6, 3, 6)));
        assertEquals(HALL_WALL_CELLS, c.size());
    }

    @Test
    void twoOpeningsCannotShareACell() {
        CompileResult r = compile(withOpening(on("d1", DOOR, WALL_S, Side.OUTER, 3, 0, Map.of())));
        assertTrue(r.issues().isEmpty());
        List<PlanNode> twice = withOpening(on("d1", DOOR, WALL_S, Side.OUTER, 3, 0, Map.of()));
        twice.add(on("d2", WINDOW, WALL_S, Side.OUTER, 3, 1, Map.of()));
        CompileResult clash = compile(twice);
        assertNull(clash.manifest());
        assertTrue(codes(clash).contains("E-OVERLAP"), clash.issues().toString());
        // the carve of the second opening reports it, and the canvas reports the pair (both are E-OVERLAP)
        assertEquals(Set.of("E-OVERLAP:d2#carve", "E-OVERLAP:d1,d2"), Set.copyOf(ids(clash)), clash.issues().toString());
        assertEquals(2, clash.issues().size());
    }

    @Test
    void openingsNextToEachOtherDoNotClash() {
        Map<LocalPos, BlockSpec> c = build(
                on("d1", DOOR, WALL_S, Side.OUTER, 3, 0, Map.of()),
                on("w1", WINDOW, WALL_S, Side.OUTER, 4, 1, Map.of()),
                on("w2", WINDOW, WALL_S, Side.OUTER, 2, 1, Map.of()));
        assertEquals(2, countOf(c, OAK_DOOR));
        assertEquals(4, countOf(c, GLASS_PANE));
        assertEquals(HALL_WALL_CELLS, c.size());
    }

    @Test
    void aThickWallIsCarvedThroughAndTheDoorSitsOnTheChosenSide() {
        Map<LocalPos, BlockSpec> c = buildIn(hallWithSouthWall(2), on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, Map.of()));
        assertEquals(LOWER, c.get(new LocalPos(3, 1, 0)).get(PROP_HALF), "outer layer is w=0");
        assertNull(c.get(new LocalPos(3, 1, 1)), "the inner layer is carved to air");
        assertNull(c.get(new LocalPos(3, 2, 1)));
        assertEquals(stoneBricks(), c.get(new LocalPos(2, 1, 1)));
    }

    @Test
    void anInnerDoorOnAThickWallSitsOnTheInnermostLayer() {
        Map<LocalPos, BlockSpec> c = buildIn(hallWithSouthWall(3), on(DOOR_ID, DOOR, WALL_S, Side.INNER, 3, 0, Map.of()));
        assertEquals(doorBlock(SOUTH, LOWER, LEFT), c.get(new LocalPos(3, 1, 2)), "the innermost layer of a 3-thick wall is w=2");
        assertEquals(doorBlock(SOUTH, UPPER, LEFT), c.get(new LocalPos(3, 2, 2)));
        for (int w = 0; w < 2; w++) {
            assertNull(c.get(new LocalPos(3, 1, w)), "layer w=" + w + " is carved to air");
            assertNull(c.get(new LocalPos(3, 2, w)));
        }
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 3, 2)), "above the door");
        assertEquals(stoneBricks(), c.get(new LocalPos(2, 1, 2)), "beside the door");
    }

    @Test
    void aWindowOnAThickWallSitsOnTheChosenLayer() {
        Map<LocalPos, BlockSpec> outer = buildIn(hallWithSouthWall(2), on(WINDOW_ID, WINDOW, WALL_S, Side.OUTER, 3, 1, Map.of()));
        assertEquals(glassPane(), outer.get(new LocalPos(3, 2, 0)));
        assertEquals(glassPane(), outer.get(new LocalPos(3, 3, 0)));
        assertNull(outer.get(new LocalPos(3, 2, 1)));
        assertNull(outer.get(new LocalPos(3, 3, 1)));
        Map<LocalPos, BlockSpec> inner = buildIn(hallWithSouthWall(2), on(WINDOW_ID, WINDOW, WALL_S, Side.INNER, 3, 1, Map.of()));
        assertEquals(glassPane(), inner.get(new LocalPos(3, 2, 1)));
        assertEquals(glassPane(), inner.get(new LocalPos(3, 3, 1)));
        assertNull(inner.get(new LocalPos(3, 2, 0)));
        assertNull(inner.get(new LocalPos(3, 3, 0)));
        assertEquals(2, countOf(inner, GLASS_PANE));
    }

    @Test
    void aPositionOnAnUpperStoreysWallCountsFromThatWallsLowestRow() {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, TWO_FLOORS, HALL_FLOOR_HEIGHT));
        nodes.add(node(WALL_S_UPPER, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, SOUTH, P_LEVEL, UPPER_STOREY)));
        nodes.add(on(DOOR_ID, DOOR, WALL_S_UPPER, Side.OUTER, 3, 0, Map.of()));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        // the upper storey's wall starts one row above its floor row: v = UPPER_STOREY * floor height + 1 = 5
        assertEquals(doorBlock(NORTH, LOWER, LEFT), c.get(new LocalPos(3, 5, 0)));
        assertEquals(doorBlock(NORTH, UPPER, LEFT), c.get(new LocalPos(3, 6, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 7, 0)));
        assertEquals(stoneBricks(), c.get(new LocalPos(3, 1, 0)), "the ground storey's wall is untouched");
    }

    @Test
    void anOpeningNeedsAWallAnchor() {
        List<PlanNode> nodes = hallWith(List.of());
        nodes.add(node(DOOR_ID, DOOR, STRUCTURE_ID, 0, 0, 0, Map.of()));
        assertEquals(List.of("E-ANCHOR"), codes(compile(nodes)));
        assertTrue(CompileFixtures.REGISTRY.contains(DOOR));
    }

    @Test
    void aRoleThatIsNotPlacedIsNotHeldAgainstTheOpening() {
        PlanNode single = on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, Map.of());
        PlanNode hangar = on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 2, 0, params(P_KIND, KIND_HANGAR, P_WIDTH, 3, P_HEIGHT, 3));
        PlanNode wide = on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE));
        PlanNode wideLattice = on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_KIND, KIND_WIDE, P_LATTICE, true));
        PlanNode pane = on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, Map.of());
        // controls: the door role is placed by a single door, the gate role by a hangar door, glass and trim by a lattice window
        assertEquals(List.of(refused(DOOR_ID)), ids(compileWith(roleIs(ROLE_DOOR, BEDROCK), single)));
        assertEquals(List.of(refused(DOOR_ID)), ids(compileWith(roleIs(ROLE_GATE, BEDROCK), hangar)));
        assertEquals(List.of(refused(WINDOW_ID)), ids(compileWith(roleIs(ROLE_GLASS, BEDROCK), pane)));
        assertEquals(List.of(refused(WINDOW_ID)), ids(compileWith(roleIs(ROLE_TRIM, BEDROCK), wideLattice)));
        // not placed: a hangar has no door leaves, a single door no gates, a window without lattice or arch no trim
        assertEquals(List.of(), ids(compileWith(roleIs(ROLE_DOOR, BEDROCK), hangar)));
        assertEquals(List.of(), ids(compileWith(roleIs(ROLE_GATE, BEDROCK), single)));
        assertEquals(List.of(), ids(compileWith(roleIs(ROLE_TRIM, BEDROCK), wide)));
        assertEquals(List.of(), ids(compileWith(roleIs(ROLE_TRIM, BEDROCK), pane)));
        assertEquals(List.of(), ids(compileWith(roleIs(ROLE_TRIM, BEDROCK), single)));
    }

    /** A hall's four walls generated into a context whose palette is the default plus {@code palette}. */
    private static GenContext wallsIn(Map<String, String> palette) {
        return generatedIn(hallWith(List.of()), palette);
    }

    /** The given nodes (structure and walls) generated into a context whose palette is the default plus {@code palette}. */
    private static GenContext generatedIn(List<PlanNode> nodes, Map<String, String> palette) {
        List<Issue> issues = new ArrayList<>();
        Map<String, PlanNode> byId = new HashMap<>();
        for (PlanNode n : nodes) {
            byId.put(n.id(), n);
        }
        Map<String, LocalPos> origins = Origins.resolve(nodes, SlotResolver.NONE, issues);
        GenContext ctx = new GenContext(REGISTRY, new Palette(REGISTRY.defaultPalette(), palette), new Canvas(MAX_CELLS), issues,
                byId, origins);
        for (PlanNode n : nodes) {
            PartGenerators.Entry entry = PartGenerators.find(n.type()).orElseThrow();
            entry.generator().generate(ctx, n, resolved(n));
        }
        assertTrue(issues.isEmpty(), issues.toString());
        return ctx;
    }

    private static PartGenerator generatorOf(String partId) {
        return PartGenerators.find(partId).orElseThrow().generator();
    }

    private static Params resolved(PlanNode n) {
        return Params.resolve(REGISTRY.get(n.type()), n.params());
    }

    @Test
    void aRefusedMaterialLeavesNoCellOfTheOpening() {
        // The arch's corner stairs come from the trim role, which has no stairs form here. The palette is asked for
        // it before the first pane goes down, so the refusal leaves nothing of the window on the canvas.
        GenContext ctx = wallsIn(Map.of(ROLE_TRIM, BEDROCK));
        PlanNode arch = on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 1, 0, params(P_KIND, KIND_ARCH));
        GenAbort refusal = assertThrows(GenAbort.class, () -> generatorOf(WINDOW).generate(ctx, arch, resolved(arch)));
        assertEquals("E-PARAM-RANGE:" + WINDOW_ID + "#material", refusal.issue().id());
        assertTrue(ctx.canvas().all().stream().noneMatch(cell -> cell.ownerId().equals(WINDOW_ID)),
                "no pane and no stair of the refused window was placed");
    }

    @Test
    void anUnknownMaterialRoleIsACleanIssueOnTheOpening() {
        assertEquals(List.of("E-PARAM-RANGE:" + DOOR_ID + "#material"), ids(compile(withOpening(
                on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, params(P_MATERIAL, UNKNOWN_ROLE))))));
        assertEquals(List.of("E-PARAM-RANGE:" + WINDOW_ID + "#material"), ids(compile(withOpening(
                on(WINDOW_ID, WINDOW, WALL_N, Side.OUTER, 2, 1, params(P_MATERIAL, UNKNOWN_ROLE))))));
    }

    @Test
    void anUnknownDoorKindIsNotTakenForAHangar() {
        // PlanCompiler checks the kind against the part's values before generating, so a compile never gets here; the
        // generator still must not guess a width for a kind it does not know.
        GenContext ctx = wallsIn(Map.of());
        PlanNode odd = on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, params(P_KIND, UNKNOWN_KIND));
        int before = ctx.canvas().size();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> generatorOf(DOOR).generate(ctx, odd, resolved(odd)));
        assertTrue(refusal.getMessage().contains(UNKNOWN_KIND), refusal.getMessage());
        assertEquals(before, ctx.canvas().size(), "nothing was carved out of the wall or placed");
    }

    @Test
    void aDoorTurnsWithTheSite() {
        List<PlanNode> nodes = withOpening(on(DOOR_ID, DOOR, WALL_S, Side.OUTER, 3, 0, Map.of()));
        CompileResult r = compile(CompileFixtures.plan(CompileFixtures.site(Facing.EAST), NO_STYLE, nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Placement lower = r.manifest().placements().stream()
                .filter(p -> p.partNodeId().equals(DOOR_ID) && LOWER.equals(p.block().get(PROP_HALF))).findFirst().orElseThrow();
        // in the local frame the door faces north; an east-facing site turns that a quarter turn clockwise, to east
        assertEquals(EAST, lower.block().get(PROP_FACING));
        assertEquals(LEFT, lower.block().get(PROP_HINGE), "a turn does not change the hinge");
    }
}
