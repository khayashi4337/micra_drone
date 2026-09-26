package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.ruled;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.PlanCompiler;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StairwayPartsTest {
    private static final PartTypeRegistry REGISTRY = CompileFixtures.REGISTRY;

    private static final String STAIRS = "micra:stairs";
    private static final String LADDER = "micra:ladder";
    private static final String RAMP = "micra:ramp";
    private static final String CATWALK = "micra:catwalk";
    private static final String RAILING = "micra:railing";
    private static final String BALCONY = "micra:balcony";
    private static final String STRUCTURE = "micra:structure";
    private static final String WALL = "micra:wall";

    private static final String OAK_STAIRS = "minecraft:oak_stairs";
    private static final String STONE_BRICK_STAIRS = "minecraft:stone_brick_stairs";
    private static final String LADDER_BLOCK = "minecraft:ladder";
    private static final String STONE_SLAB = "minecraft:stone_slab";
    private static final String SMOOTH_STONE_SLAB = "minecraft:smooth_stone_slab";
    private static final String BRICK_SLAB = "minecraft:brick_slab";
    private static final String OAK_SLAB = "minecraft:oak_slab";
    private static final String IRON_TRAPDOOR = "minecraft:iron_trapdoor";
    private static final String OAK_FENCE = "minecraft:oak_fence";
    private static final String SPRUCE_FENCE = "minecraft:spruce_fence";
    private static final String OAK_PLANKS = "minecraft:oak_planks";
    private static final String SPRUCE_PLANKS = "minecraft:spruce_planks";
    private static final String STONE = "minecraft:stone";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String SMOOTH_STONE = "minecraft:smooth_stone";
    private static final String BRICKS = "minecraft:bricks";
    private static final String RED_TERRACOTTA = "minecraft:red_terracotta";
    /** Always refused by the block policy: a role mapped to it shows whether a part looked that role up. */
    private static final String BEDROCK = "minecraft:bedrock";
    /** A role name (no namespace) that the palette does not have. */
    private static final String UNKNOWN_ROLE = "no_such_role";

    // Palette roles the tests override.
    private static final String ROLE_FENCE = "fence";
    private static final String ROLE_FLOOR = "floor";
    private static final String ROLE_CATWALK = "catwalk";

    // Parameter names of the parts under test and of the building.
    private static final String P_STEPS = "steps";
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_HEIGHT = "height";
    private static final String P_LENGTH = "length";
    private static final String P_DIR = "dir";
    private static final String P_FACING = "facing";
    private static final String P_RAIL = "rail";
    private static final String P_MATERIAL = "material";
    private static final String P_SIDE = "side";
    private static final String P_LEVEL = "level";
    private static final String P_FROM = "from";
    private static final String P_THICKNESS = "thickness";
    private static final String P_FLOORS = "floors";
    private static final String P_FLOOR_HEIGHT = "floor_height";

    // Block-state property names and values.
    private static final String PROP_FACING = "facing";
    private static final String PROP_HALF = "half";
    private static final String PROP_TYPE = "type";
    private static final String BOTTOM = "bottom";
    private static final String TOP = "top";
    private static final String NORTH = "north";
    private static final String EAST = "east";
    private static final String SOUTH = "south";
    private static final String WEST = "west";

    // Issue ids: CODE:node#key.
    private static final String PART_ID = "p";
    private static final String BALCONY_ID = "b";
    private static final String STRUCTURE_ID = "s";
    private static final String WALL_N = "wall-n";
    private static final String WALL_E = "wall-e";
    private static final String WALL_S = "wall-s";
    private static final String WALL_W = "wall-w";
    private static final String WALL_N_UPPER = "wall-n1";
    private static final String BAD_MATERIAL_ID = "E-PARAM-RANGE:" + PART_ID + "#material";
    private static final String BAD_BALCONY_MATERIAL_ID = "E-PARAM-RANGE:" + BALCONY_ID + "#material";
    private static final String BALCONY_ANCHOR_ID = "E-ANCHOR:" + BALCONY_ID + "#anchor";
    private static final String BALCONY_OFF_WALL_ID = "E-OPENING-NO-WALL:" + BALCONY_ID + "#anchor";
    private static final String BUDGET_ISSUE_ID = "E-OUT-OF-BOUNDS:#cells";

    /** The hall: 7 x 7, one floor, floor height 4: walls are 3 rows high (v = 1..3), the floor row is v = 0. */
    private static final int HALL_SIDE = 7;
    private static final int HALL_FLOOR_HEIGHT = 4;
    private static final int FLOORS = 1;
    private static final int TWO_FLOORS = 2;
    private static final int MAX_CELLS = 1_000;

    private static final StyleSpec NO_STYLE = StyleSpec.EMPTY;

    /**
     * A heading with its two unit steps worked out by hand: one cell ahead, and one cell to the right-hand side seen
     * from above (north is +w, east is +u).
     */
    private record Heading(String name, int aheadU, int aheadW, int rightU, int rightW) {
        LocalPos cell(int ahead, int right, int v) {
            return new LocalPos(ahead * aheadU + right * rightU, v, ahead * aheadW + right * rightW);
        }
    }

    private static final List<Heading> HEADINGS = List.of(
            new Heading(NORTH, 0, 1, 1, 0),    // heading +w, right is +u
            new Heading(EAST, 1, 0, 0, -1),    // heading +u, right is -w
            new Heading(SOUTH, 0, -1, -1, 0),  // heading -w, right is -u
            new Heading(WEST, -1, 0, 0, 1));   // heading -u, right is +w

    private static Map<LocalPos, BlockSpec> build(PlanNode... nodes) {
        return build(NO_STYLE, List.of(nodes));
    }

    private static Map<LocalPos, BlockSpec> build(StyleSpec style, List<PlanNode> nodes) {
        CompileResult r = compile(style, nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    private static List<String> ids(CompileResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    private static PlanNode single(String type, Map<String, ParamValue> params) {
        return node(PART_ID, type, null, 0, 0, 0, params);
    }

    private static StyleSpec roleIs(String role, String block) {
        return new StyleSpec(Map.of(role, block), Set.of());
    }

    private static BlockSpec stairs(String id, String facing) {
        return BlockSpec.of(id, PROP_FACING, facing, PROP_HALF, BOTTOM);
    }

    private static BlockSpec slab(String id, String type) {
        return BlockSpec.of(id, PROP_TYPE, type);
    }

    /** The cells of a rectangle at height v, both ends of each range included. */
    private static Set<LocalPos> box(int v, int u0, int u1, int w0, int w1) {
        Set<LocalPos> out = new HashSet<>();
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                out.add(new LocalPos(u, v, w));
            }
        }
        return out;
    }

    @SafeVarargs
    private static Set<LocalPos> union(Set<LocalPos> first, Set<LocalPos>... more) {
        Set<LocalPos> out = new HashSet<>(first);
        for (Set<LocalPos> s : more) {
            out.addAll(s);
        }
        return out;
    }

    private static Set<LocalPos> positionsOf(Map<LocalPos, BlockSpec> cells, String blockId) {
        Set<LocalPos> out = new HashSet<>();
        cells.forEach((pos, block) -> {
            if (block.blockId().equals(blockId)) {
                out.add(pos);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ stairs

    @Test
    void aStaircaseRisesOneStepPerCellAndGrowsToTheRight() {
        Map<LocalPos, BlockSpec> c = build(node("st", STAIRS, null, 0, 0, 0, params(P_STEPS, 3, P_WIDTH, 2, P_DIR, NORTH)));
        assertEquals(6, c.size());
        BlockSpec step = stairs(OAK_STAIRS, NORTH);
        assertEquals(step, c.get(new LocalPos(0, 0, 0)));
        assertEquals(step, c.get(new LocalPos(1, 1, 1)), "right of north is east (+u)");
        assertEquals(step, c.get(new LocalPos(1, 2, 2)));
        assertEquals(Set.of(new LocalPos(0, 0, 0), new LocalPos(1, 0, 0), new LocalPos(0, 1, 1), new LocalPos(1, 1, 1),
                new LocalPos(0, 2, 2), new LocalPos(1, 2, 2)), c.keySet());
    }

    @Test
    void aStaircaseClimbsTowardsEachOfTheFourDirectionsAndFacesThatWay() {
        for (Heading h : HEADINGS) {
            Map<LocalPos, BlockSpec> c = build(single(STAIRS, params(P_STEPS, 3, P_WIDTH, 2, P_DIR, h.name())));
            Set<LocalPos> expected = new HashSet<>();
            for (int s = 0; s < 3; s++) {
                for (int j = 0; j < 2; j++) {
                    expected.add(h.cell(s, j, s));
                }
            }
            assertEquals(expected, c.keySet(), h.name());
            assertEquals(6, countOf(c, OAK_STAIRS), h.name());
            assertTrue(c.values().stream().allMatch(b -> b.equals(stairs(OAK_STAIRS, h.name()))), h.name());
        }
    }

    @Test
    void aStaircaseIsMadeOfTheStairsFormOfItsMaterial() {
        Map<LocalPos, BlockSpec> c = build(single(STAIRS, params(P_STEPS, 1, P_MATERIAL, STONE_BRICKS)));
        assertEquals(stairs(STONE_BRICK_STAIRS, NORTH), c.get(new LocalPos(0, 0, 0)));
        // smooth stone has a slab but no stairs in the game; unlike a ramp, a staircase cannot be made of it
        assertEquals(List.of(BAD_MATERIAL_ID), ids(compile(List.of(single(STAIRS, params(P_MATERIAL, SMOOTH_STONE))))));
        assertEquals(List.of(BAD_MATERIAL_ID), ids(compile(List.of(single(STAIRS, params(P_MATERIAL, UNKNOWN_ROLE))))));
    }

    // ------------------------------------------------------------------ ladder

    @Test
    void aLadderClimbsAndFacesTheGivenDirection() {
        Map<LocalPos, BlockSpec> c = build(node("l", LADDER, null, 0, 0, 0, params(P_HEIGHT, 3, P_FACING, EAST)));
        assertEquals(3, c.size());
        assertEquals(BlockSpec.of(LADDER_BLOCK, PROP_FACING, EAST), c.get(new LocalPos(0, 2, 0)));
        assertEquals(Set.of(new LocalPos(0, 0, 0), new LocalPos(0, 1, 0), new LocalPos(0, 2, 0)), c.keySet());
    }

    @Test
    void aLadderFacesEachOfTheFourDirections() {
        for (Heading h : HEADINGS) {
            Map<LocalPos, BlockSpec> c = build(single(LADDER, params(P_HEIGHT, 2, P_FACING, h.name())));
            assertEquals(2, c.size(), h.name());
            assertEquals(BlockSpec.of(LADDER_BLOCK, PROP_FACING, h.name()), c.get(new LocalPos(0, 1, 0)), h.name());
        }
    }

    // ------------------------------------------------------------------ ramp

    @Test
    void aRampAlternatesBottomAndTopSlabs() {
        Map<LocalPos, BlockSpec> c = build(node("r", RAMP, null, 0, 0, 0, params(P_LENGTH, 4, P_DIR, EAST, P_WIDTH, 1)));
        assertEquals(slab(STONE_SLAB, BOTTOM), c.get(new LocalPos(0, 0, 0)));
        assertEquals(slab(STONE_SLAB, TOP), c.get(new LocalPos(1, 0, 0)));
        assertEquals(slab(STONE_SLAB, BOTTOM), c.get(new LocalPos(2, 1, 0)));
        assertEquals(slab(STONE_SLAB, TOP), c.get(new LocalPos(3, 1, 0)));
        assertEquals(4, c.size());
    }

    @Test
    void aRampRisesHalfABlockPerCellAndGrowsToTheRightWhicheverWayItPoints() {
        // 4 long, 2 wide: cell i is at height i / 2, a bottom slab when i is even and a top slab when it is odd
        int[] heightOf = {0, 0, 1, 1};
        String[] halfOf = {BOTTOM, TOP, BOTTOM, TOP};
        for (Heading h : HEADINGS) {
            Map<LocalPos, BlockSpec> c = build(single(RAMP, params(P_LENGTH, 4, P_WIDTH, 2, P_DIR, h.name())));
            assertEquals(8, c.size(), h.name());
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 2; j++) {
                    assertEquals(slab(STONE_SLAB, halfOf[i]), c.get(h.cell(i, j, heightOf[i])), h.name() + " i=" + i + " j=" + j);
                }
            }
        }
    }

    @Test
    void aRampOfOddLengthEndsOnABottomSlabOneBlockUp() {
        Map<LocalPos, BlockSpec> c = build(single(RAMP, params(P_LENGTH, 3, P_DIR, NORTH, P_WIDTH, 1)));
        assertEquals(Set.of(new LocalPos(0, 0, 0), new LocalPos(0, 0, 1), new LocalPos(0, 1, 2)), c.keySet());
        assertEquals(slab(STONE_SLAB, TOP), c.get(new LocalPos(0, 0, 1)));
        assertEquals(slab(STONE_SLAB, BOTTOM), c.get(new LocalPos(0, 1, 2)));
    }

    @Test
    void aRampIsMadeOfTheSlabFormOfItsMaterial() {
        assertEquals(slab(SMOOTH_STONE_SLAB, BOTTOM),
                build(single(RAMP, params(P_LENGTH, 2, P_WIDTH, 1, P_MATERIAL, SMOOTH_STONE))).get(new LocalPos(0, 0, 0)),
                "smooth stone has a slab, so a ramp can be made of it");
        assertEquals(slab(BRICK_SLAB, BOTTOM),
                build(single(RAMP, params(P_LENGTH, 2, P_WIDTH, 1, P_MATERIAL, BRICKS))).get(new LocalPos(0, 0, 0)));
        // terracotta has neither a stairs nor a slab in the game
        assertEquals(List.of(BAD_MATERIAL_ID), ids(compile(List.of(single(RAMP, params(P_MATERIAL, RED_TERRACOTTA))))));
    }

    // ------------------------------------------------------------------ catwalk

    @Test
    void aCatwalkHasAGratedFloorAndRailingsOnBothEdges() {
        Map<LocalPos, BlockSpec> c = build(node("c", CATWALK, null, 0, 0, 0, params(P_LENGTH, 3, P_DIR, NORTH, P_WIDTH, 2)));
        assertEquals(6, countOf(c, IRON_TRAPDOOR));
        assertEquals(BlockSpec.of(IRON_TRAPDOOR, PROP_HALF, BOTTOM), c.get(new LocalPos(1, 0, 2)));
        assertEquals(6, countOf(c, OAK_FENCE));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(0, 1, 0)));
        assertEquals(12, c.size());
        Map<LocalPos, BlockSpec> bare = build(node("c", CATWALK, null, 0, 0, 0, params(P_LENGTH, 3, P_RAIL, false)));
        assertEquals(6, bare.size());
    }

    @Test
    void aCatwalkRailsOnlyItsTwoOuterColumns() {
        // 3 wide: the middle column (j = 1) has no fence
        Map<LocalPos, BlockSpec> wide = build(single(CATWALK, params(P_LENGTH, 2, P_WIDTH, 3, P_DIR, NORTH)));
        assertEquals(box(0, 0, 2, 0, 1), positionsOf(wide, IRON_TRAPDOOR));
        assertEquals(union(box(1, 0, 0, 0, 1), box(1, 2, 2, 0, 1)), positionsOf(wide, OAK_FENCE));
        // 1 wide: the one column is both outer columns, and gets one fence, not two
        Map<LocalPos, BlockSpec> narrow = build(single(CATWALK, params(P_LENGTH, 2, P_WIDTH, 1, P_DIR, NORTH)));
        assertEquals(box(0, 0, 0, 0, 1), positionsOf(narrow, IRON_TRAPDOOR));
        assertEquals(box(1, 0, 0, 0, 1), positionsOf(narrow, OAK_FENCE));
        assertEquals(4, narrow.size());
    }

    @Test
    void aCatwalkRunsTowardsEachOfTheFourDirections() {
        for (Heading h : HEADINGS) {
            Map<LocalPos, BlockSpec> c = build(single(CATWALK, params(P_LENGTH, 3, P_WIDTH, 2, P_DIR, h.name())));
            Set<LocalPos> floor = new HashSet<>();
            Set<LocalPos> fence = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 2; j++) {
                    floor.add(h.cell(i, j, 0));
                    fence.add(h.cell(i, j, 1)); // 2 wide: both columns are outer columns
                }
            }
            assertEquals(floor, positionsOf(c, IRON_TRAPDOOR), h.name());
            assertEquals(fence, positionsOf(c, OAK_FENCE), h.name());
        }
    }

    @Test
    void aCatwalkFloorLiesFlatWhateverItIsMadeOf() {
        Map<LocalPos, BlockSpec> slabs = build(single(CATWALK, params(P_LENGTH, 1, P_WIDTH, 1, P_RAIL, false, P_MATERIAL, OAK_SLAB)));
        assertEquals(slab(OAK_SLAB, BOTTOM), slabs.get(new LocalPos(0, 0, 0)));
        Map<LocalPos, BlockSpec> blocks = build(single(CATWALK, params(P_LENGTH, 1, P_WIDTH, 1, P_RAIL, false, P_MATERIAL, STONE)));
        assertEquals(BlockSpec.of(STONE), blocks.get(new LocalPos(0, 0, 0)));
    }

    // ------------------------------------------------------------------ railing

    @Test
    void aRailingIsARunOfFence() {
        Map<LocalPos, BlockSpec> c = build(node("r", RAILING, null, 0, 0, 0, params(P_LENGTH, 3, P_DIR, WEST, P_HEIGHT, 2)));
        assertEquals(6, c.size());
        assertTrue(c.containsKey(new LocalPos(-2, 1, 0)));
        assertEquals(Set.of(new LocalPos(0, 0, 0), new LocalPos(0, 1, 0), new LocalPos(-1, 0, 0), new LocalPos(-1, 1, 0),
                new LocalPos(-2, 0, 0), new LocalPos(-2, 1, 0)), c.keySet());
        assertEquals(6, countOf(c, OAK_FENCE));
    }

    @Test
    void aRailingRunsTowardsEachOfTheFourDirectionsAndTakesItsMaterial() {
        for (Heading h : HEADINGS) {
            Map<LocalPos, BlockSpec> c = build(single(RAILING, params(P_LENGTH, 2, P_HEIGHT, 3, P_DIR, h.name(), P_MATERIAL, SPRUCE_FENCE)));
            Set<LocalPos> expected = new HashSet<>();
            for (int i = 0; i < 2; i++) {
                for (int v = 0; v < 3; v++) {
                    expected.add(h.cell(i, 0, v));
                }
            }
            assertEquals(expected, c.keySet(), h.name());
            assertEquals(6, countOf(c, SPRUCE_FENCE), h.name());
        }
    }

    // ------------------------------------------------------------------ balcony

    /** The hall with every balcony given, on the walls wall-n / wall-e / wall-s / wall-w. */
    private static List<PlanNode> hallWith(PlanNode... balconies) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.addAll(List.of(balconies));
        return nodes;
    }

    private static PlanNode balcony(String wall, Side side, int u, int v, Map<String, ParamValue> params) {
        return onWall(BALCONY_ID, BALCONY, STRUCTURE_ID, wall, side, u, v, params);
    }

    /** The hall's walls, its structure moved to (u, v, w) and made {@code floors} storeys high. */
    private static List<PlanNode> hallAt(int u, int v, int w, int floors) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, floors, HALL_FLOOR_HEIGHT));
        nodes.replaceAll(n -> n.id().equals(STRUCTURE_ID) ? node(STRUCTURE_ID, STRUCTURE, null, u, v, w,
                params(P_WIDTH, HALL_SIDE, P_DEPTH, HALL_SIDE, P_FLOORS, floors, P_FLOOR_HEIGHT, HALL_FLOOR_HEIGHT)) : n);
        return nodes;
    }

    private static Map<LocalPos, BlockSpec> buildBalconies(List<PlanNode> nodes) {
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    @Test
    void aBalconyProjectsFromAWallWithRailings() {
        CompileResult r = compile(hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        // the north wall is at w=6; the balcony floor is at the storey's floor level (v=0), w=7..8
        assertEquals(BlockSpec.of(OAK_PLANKS), c.get(new LocalPos(2, 0, 7)));
        assertEquals(BlockSpec.of(OAK_PLANKS), c.get(new LocalPos(4, 0, 8)));
        assertEquals(6, countOf(c, OAK_PLANKS), "the balcony floor only (there is no floor part in this plan)");
        assertEquals(5, countOf(c, OAK_FENCE), "far edge of 3 plus one more on each side");
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(2, 1, 7)));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(3, 1, 8)));
    }

    @Test
    void aBalconyOnTheInnerSideIsRefused() {
        CompileResult r = compile(hallWith(balcony(WALL_N, Side.INNER, 2, 0, Map.of())));
        assertEquals(List.of("E-ANCHOR"), codes(r));
        assertEquals(List.of(BALCONY_ANCHOR_ID), ids(r));
    }

    @Test
    void aBalconyProjectsOutwardFromEachSideOfTheBuilding() {
        // 3 wide from position 2 and 2 deep, on the 7 x 7 hall at the origin: the floor is at v=0 and the fence at v=1.
        // The wall's own u grows east on the north and south walls and north on the east and west walls.
        Map<LocalPos, BlockSpec> north = buildBalconies(hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(0, 2, 4, 7, 8), positionsOf(north, OAK_PLANKS), "north wall is at w=6, outward is +w");
        assertEquals(union(box(1, 2, 4, 8, 8), box(1, 2, 2, 7, 7), box(1, 4, 4, 7, 7)), positionsOf(north, OAK_FENCE));

        Map<LocalPos, BlockSpec> south = buildBalconies(hallWith(balcony(WALL_S, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(0, 2, 4, -2, -1), positionsOf(south, OAK_PLANKS), "south wall is at w=0, outward is -w");
        assertEquals(union(box(1, 2, 4, -2, -2), box(1, 2, 2, -1, -1), box(1, 4, 4, -1, -1)), positionsOf(south, OAK_FENCE));

        Map<LocalPos, BlockSpec> east = buildBalconies(hallWith(balcony(WALL_E, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(0, 7, 8, 2, 4), positionsOf(east, OAK_PLANKS), "east wall is at u=6, outward is +u");
        assertEquals(union(box(1, 8, 8, 2, 4), box(1, 7, 7, 2, 2), box(1, 7, 7, 4, 4)), positionsOf(east, OAK_FENCE));

        Map<LocalPos, BlockSpec> west = buildBalconies(hallWith(balcony(WALL_W, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(0, -2, -1, 2, 4), positionsOf(west, OAK_PLANKS), "west wall is at u=0, outward is -u");
        assertEquals(union(box(1, -2, -2, 2, 4), box(1, -1, -1, 2, 2), box(1, -1, -1, 4, 4)), positionsOf(west, OAK_FENCE));
    }

    @Test
    void aBalconyStandsWhereTheBuildingStands() {
        // The building's origin is (5, 2, -3): its north wall is at w = -3 + 6 = 3 and its east wall at u = 5 + 6 = 11, and the
        // storey's floor level is v = 2. The wall's own position u = 2 is 2 cells from the wall's start (u = 5 on the north wall,
        // w = -3 on the east wall).
        List<PlanNode> nodes = hallAt(5, 2, -3, FLOORS);
        nodes.add(onWall("bn", BALCONY, STRUCTURE_ID, WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2)));
        nodes.add(onWall("be", BALCONY, STRUCTURE_ID, WALL_E, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2)));
        Map<LocalPos, BlockSpec> c = buildBalconies(nodes);
        Set<LocalPos> northFloor = box(2, 7, 9, 4, 5);
        Set<LocalPos> eastFloor = box(2, 12, 13, -1, 1);
        assertEquals(union(northFloor, eastFloor), positionsOf(c, OAK_PLANKS));
        Set<LocalPos> northFence = union(box(3, 7, 9, 5, 5), box(3, 7, 7, 4, 4), box(3, 9, 9, 4, 4));
        Set<LocalPos> eastFence = union(box(3, 13, 13, -1, 1), box(3, 12, 12, -1, -1), box(3, 12, 12, 1, 1));
        assertEquals(union(northFence, eastFence), positionsOf(c, OAK_FENCE));
    }

    @Test
    void aBalconyOnAnUpperStoreyStandsAtThatStoreysFloor() {
        // Two storeys of height 4 at origin (5, 2, -3): storey 1's floor is at v = 2 + 4 = 6 and its wall starts at v = 7
        List<PlanNode> nodes = hallAt(5, 2, -3, TWO_FLOORS);
        nodes.add(node(WALL_N_UPPER, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, NORTH, P_LEVEL, 1)));
        nodes.add(onWall("bu", BALCONY, STRUCTURE_ID, WALL_N_UPPER, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2)));
        Map<LocalPos, BlockSpec> c = buildBalconies(nodes);
        assertEquals(box(6, 7, 9, 4, 5), positionsOf(c, OAK_PLANKS));
        assertEquals(union(box(7, 7, 9, 5, 5), box(7, 7, 7, 4, 4), box(7, 9, 9, 4, 4)), positionsOf(c, OAK_FENCE));
    }

    @Test
    void aBalconysHeightIsCountedFromTheFloorOfItsStorey() {
        // v = 2 puts the floor 2 rows above the storey's floor (v = 0 + 2), the fence on top of it
        Map<LocalPos, BlockSpec> c = buildBalconies(hallWith(balcony(WALL_N, Side.OUTER, 2, 2, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(2, 2, 4, 7, 8), positionsOf(c, OAK_PLANKS));
        assertEquals(union(box(3, 2, 4, 8, 8), box(3, 2, 2, 7, 7), box(3, 4, 4, 7, 7)), positionsOf(c, OAK_FENCE));
        // v = 1 is the wall's lowest row: the floor is one above the storey's floor
        Map<LocalPos, BlockSpec> low = buildBalconies(hallWith(balcony(WALL_N, Side.OUTER, 2, 1, params(P_WIDTH, 3, P_DEPTH, 2))));
        assertEquals(box(1, 2, 4, 7, 8), positionsOf(low, OAK_PLANKS));
    }

    @Test
    void aBalconyProjectsFromTheOutermostLayerOfAThickWall() {
        List<PlanNode> nodes = hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2)));
        nodes.replaceAll(n -> n.id().equals(WALL_N) ? node(WALL_N, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, NORTH, P_THICKNESS, 3)) : n);
        Map<LocalPos, BlockSpec> c = buildBalconies(nodes);
        // the thick wall takes w = 6, 5 and 4; the balcony still starts right outside the outermost layer, at w = 7
        assertEquals(box(0, 2, 4, 7, 8), positionsOf(c, OAK_PLANKS));
    }

    @Test
    void aBalconysPositionCountsFromTheStartOfItsWallSegment() {
        // the north wall covers u = 2..5 only; position 1 of it is u = 3. One deep: the far edge is the whole railing.
        List<PlanNode> nodes = hallWith(balcony(WALL_N, Side.OUTER, 1, 0, params(P_WIDTH, 2, P_DEPTH, 1)));
        nodes.replaceAll(n -> n.id().equals(WALL_N) ? node(WALL_N, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, NORTH, P_FROM, 2, P_LENGTH, 4)) : n);
        Map<LocalPos, BlockSpec> c = buildBalconies(nodes);
        assertEquals(box(0, 3, 4, 7, 7), positionsOf(c, OAK_PLANKS));
        assertEquals(box(1, 3, 4, 7, 7), positionsOf(c, OAK_FENCE));
    }

    @Test
    void aBalconyOneCellWideHasOneRunOfFenceAndNoCellTwice() {
        Map<LocalPos, BlockSpec> c = buildBalconies(hallWith(balcony(WALL_N, Side.OUTER, 3, 0, params(P_WIDTH, 1, P_DEPTH, 3))));
        assertEquals(box(0, 3, 3, 7, 9), positionsOf(c, OAK_PLANKS));
        assertEquals(box(1, 3, 3, 7, 9), positionsOf(c, OAK_FENCE), "the one column is both sides and the far edge is its end");
    }

    @Test
    void aBalconyWithoutARailIsOnlyAFloor() {
        Map<LocalPos, BlockSpec> c = buildBalconies(hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 3, P_DEPTH, 2, P_RAIL, false))));
        assertEquals(6, countOf(c, OAK_PLANKS));
        assertEquals(0, countOf(c, OAK_FENCE));
    }

    @Test
    void aBalconysFloorIsMadeOfItsMaterial() {
        Map<LocalPos, BlockSpec> c = buildBalconies(hallWith(
                balcony(WALL_N, Side.OUTER, 2, 0, params(P_WIDTH, 1, P_DEPTH, 1, P_MATERIAL, SPRUCE_PLANKS))));
        assertEquals(BlockSpec.of(SPRUCE_PLANKS), c.get(new LocalPos(2, 0, 7)));
        assertEquals(0, countOf(c, OAK_PLANKS));
    }

    @Test
    void aBalconyNeedsAnOuterPositionOnAWallFace() {
        // not an OnSurface anchor at all
        assertEquals(List.of(BALCONY_ANCHOR_ID), ids(compile(hallWith(node(BALCONY_ID, BALCONY, STRUCTURE_ID, 0, 0, 0, Map.of())))));
        // the position is past the wall's end (7 long: 0..6), or above its top (3 rows: 0..2)
        assertEquals(List.of(BALCONY_OFF_WALL_ID), ids(compile(hallWith(balcony(WALL_N, Side.OUTER, HALL_SIDE, 0, Map.of())))));
        assertEquals(List.of(BALCONY_OFF_WALL_ID), ids(compile(hallWith(balcony(WALL_N, Side.OUTER, 0, HALL_FLOOR_HEIGHT - 1, Map.of())))));
    }

    // ------------------------------------------------------------------ freestanding parts inside a building

    @Test
    void partsInsideABuildingStandAtTheBuildingsOrigin() {
        // The building is at (5, 2, -3); each part is placed relative to it, so its origin is (5, 2, -3) plus its own position.
        Map<LocalPos, BlockSpec> c = build(NO_STYLE, List.of(
                node(STRUCTURE_ID, STRUCTURE, null, 5, 2, -3, Map.of()),
                node("st", STAIRS, STRUCTURE_ID, 1, 0, 1, params(P_STEPS, 2, P_WIDTH, 1, P_DIR, NORTH)),         // origin (6, 2, -2)
                node("l", LADDER, STRUCTURE_ID, 0, 0, 3, params(P_HEIGHT, 2, P_FACING, SOUTH)),                  // origin (5, 2, 0)
                node("rp", RAMP, STRUCTURE_ID, 2, 0, 4, params(P_LENGTH, 2, P_WIDTH, 1, P_DIR, EAST)),           // origin (7, 2, 1)
                node("c", CATWALK, STRUCTURE_ID, 0, 3, 0, params(P_LENGTH, 1, P_WIDTH, 1)),                      // origin (5, 5, -3)
                node("rl", RAILING, STRUCTURE_ID, 4, 0, 0, params(P_LENGTH, 2, P_DIR, WEST))));                  // origin (9, 2, -3)
        assertEquals(10, c.size());
        assertEquals(stairs(OAK_STAIRS, NORTH), c.get(new LocalPos(6, 2, -2)));
        assertEquals(stairs(OAK_STAIRS, NORTH), c.get(new LocalPos(6, 3, -1)));
        assertEquals(BlockSpec.of(LADDER_BLOCK, PROP_FACING, SOUTH), c.get(new LocalPos(5, 2, 0)));
        assertEquals(BlockSpec.of(LADDER_BLOCK, PROP_FACING, SOUTH), c.get(new LocalPos(5, 3, 0)));
        assertEquals(slab(STONE_SLAB, BOTTOM), c.get(new LocalPos(7, 2, 1)));
        assertEquals(slab(STONE_SLAB, TOP), c.get(new LocalPos(8, 2, 1)));
        assertEquals(BlockSpec.of(IRON_TRAPDOOR, PROP_HALF, BOTTOM), c.get(new LocalPos(5, 5, -3)));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(5, 6, -3)));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(9, 2, -3)));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(8, 2, -3)));
    }

    @Test
    void aTurnedStaircaseTurnsItsCellsAndItsFacing() {
        // One clockwise turn maps (u, w) to (w, -u): the offsets (0,0,0) (1,0,0) (0,1,1) (1,1,1) of a north-facing staircase
        // become (0,0,0) (0,0,-1) (1,1,0) (1,1,-1), and it faces east: the same cells as a staircase that heads east.
        PlanNode turned = ruled("st", STAIRS, null, new Rot(1, false), 10, 0, 20, params(P_STEPS, 2, P_WIDTH, 2, P_DIR, NORTH));
        Map<LocalPos, BlockSpec> c = build(turned);
        assertEquals(Set.of(new LocalPos(10, 0, 20), new LocalPos(10, 0, 19), new LocalPos(11, 1, 20), new LocalPos(11, 1, 19)), c.keySet());
        assertTrue(c.values().stream().allMatch(b -> b.equals(stairs(OAK_STAIRS, EAST))), c.toString());
    }

    // ------------------------------------------------------------------ materials that are not placed

    @Test
    void aRoleThatIsNotPlacedIsNotHeldAgainstThePart() {
        StyleSpec noFence = roleIs(ROLE_FENCE, BEDROCK);
        // controls: a catwalk with a rail and a balcony with a rail do place the fence role, and are refused
        String catwalkRefused = "E-BLOCK-FORBIDDEN:" + PART_ID + "#" + BEDROCK;
        String balconyRefused = "E-BLOCK-FORBIDDEN:" + BALCONY_ID + "#" + BEDROCK;
        assertEquals(List.of(catwalkRefused), ids(compile(noFence, List.of(single(CATWALK, Map.of())))));
        assertEquals(List.of(balconyRefused), ids(compile(noFence, hallWith(balcony(WALL_N, Side.OUTER, 2, 0, Map.of())))));
        // without the rail nothing asks for the fence role
        assertEquals(List.of(), ids(compile(noFence, List.of(single(CATWALK, params(P_RAIL, false))))));
        assertEquals(List.of(), ids(compile(noFence, hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_RAIL, false))))));
        // the floor of a balcony and of a catwalk are held against the part, as their own roles
        assertEquals(List.of(balconyRefused),
                ids(compile(roleIs(ROLE_FLOOR, BEDROCK), hallWith(balcony(WALL_N, Side.OUTER, 2, 0, params(P_RAIL, false))))));
        assertEquals(List.of(catwalkRefused), ids(compile(roleIs(ROLE_CATWALK, BEDROCK), List.of(single(CATWALK, params(P_RAIL, false))))));
    }

    private static PartGenerator generatorOf(String partId) {
        return PartGenerators.find(partId).orElseThrow().generator();
    }

    private static Params resolved(PlanNode n) {
        return Params.resolve(REGISTRY.get(n.type()), n.params());
    }

    /** A context holding the hall's building and walls, generated, and the part, known to the context but not generated. */
    private static GenContext hallAndPart(PlanNode part) {
        List<PlanNode> hall = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        List<PlanNode> all = new ArrayList<>(hall);
        all.add(part);
        List<Issue> issues = new ArrayList<>();
        Map<String, PlanNode> byId = new HashMap<>();
        all.forEach(n -> byId.put(n.id(), n));
        Map<String, LocalPos> origins = Origins.resolve(all, SlotResolver.NONE, issues);
        GenContext ctx = new GenContext(REGISTRY, new Palette(REGISTRY.defaultPalette(), Map.of()), new Canvas(MAX_CELLS), issues,
                byId, origins);
        for (PlanNode n : hall) {
            generatorOf(n.type()).generate(ctx, n, resolved(n));
        }
        assertTrue(issues.isEmpty(), issues.toString());
        return ctx;
    }

    private record Refusal(PlanNode part, String issueId) {
    }

    @Test
    void aRefusedPartLeavesNothingOfItselfOnTheCanvas() {
        // Each part asks the palette for every block it will place before the first one goes down, so a refusal is clean.
        List<Refusal> refusals = List.of(
                new Refusal(single(STAIRS, params(P_MATERIAL, SMOOTH_STONE)), BAD_MATERIAL_ID),
                new Refusal(single(RAMP, params(P_MATERIAL, RED_TERRACOTTA)), BAD_MATERIAL_ID),
                new Refusal(single(CATWALK, params(P_MATERIAL, UNKNOWN_ROLE)), BAD_MATERIAL_ID),
                new Refusal(single(RAILING, params(P_MATERIAL, UNKNOWN_ROLE)), BAD_MATERIAL_ID),
                new Refusal(balcony(WALL_N, Side.OUTER, 2, 0, params(P_MATERIAL, UNKNOWN_ROLE)), BAD_BALCONY_MATERIAL_ID),
                new Refusal(balcony(WALL_N, Side.INNER, 2, 0, Map.of()), BALCONY_ANCHOR_ID),
                // the patcher refuses a target that is not a wall, so only a hand-built plan gets this far
                new Refusal(balcony(STRUCTURE_ID, Side.OUTER, 0, 0, Map.of()), BALCONY_OFF_WALL_ID));
        for (Refusal r : refusals) {
            GenContext ctx = hallAndPart(r.part());
            int before = ctx.canvas().size();
            GenAbort refusal = assertThrows(GenAbort.class, () -> generatorOf(r.part().type()).generate(ctx, r.part(), resolved(r.part())));
            assertEquals(r.issueId(), refusal.issue().id());
            assertEquals(before, ctx.canvas().size(), r.issueId() + ": nothing was placed");
            assertTrue(ctx.canvas().all().stream().noneMatch(cell -> cell.ownerId().equals(r.part().id())), r.issueId());
        }
    }

    // ------------------------------------------------------------------ work budget

    @Test
    void aBalconysRailWorkCountsTheCellsItLeavesEmpty() {
        // A 16 x 3 building with three balconies, all 16 wide and 8 deep on the same spot, so only the first places anything.
        // The building's walls place 68 cells in 76 attempts; a balcony is 128 floor + 30 fence cells, which are 158 attempts,
        // plus 98 interior cells of the fence's loop that are looked at and left empty. PlanCompiler(300) allows 2 x 300 = 600
        // attempts: the placements alone use 76 + 3 x 158 = 550, and the cells left empty tip it over (76 + 3 x 256 = 844).
        List<PlanNode> nodes = new ArrayList<>(shell(16, 3, FLOORS, 3));
        for (int k = 1; k <= 3; k++) {
            nodes.add(onWall("b" + k, BALCONY, STRUCTURE_ID, WALL_N, Side.OUTER, 0, 0, params(P_WIDTH, 16, P_DEPTH, 8)));
        }
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), NO_STYLE, nodes);
        assertEquals(List.of("E-OVERLAP:b1,b2", "E-OVERLAP:b1,b3"), ids(compile(plan)),
                "with the default budget the balconies only overlap, both with the first, which placed the cells");
        CompileResult small = CompileFixtures.compile(plan, new PlanCompiler(300));
        assertNull(small.manifest());
        assertEquals(List.of(BUDGET_ISSUE_ID), ids(small));
    }

    // ------------------------------------------------------------------ registration

    @Test
    void allSixPartsAreBaseStageGenerators() {
        for (String id : List.of(STAIRS, LADDER, RAMP, CATWALK, RAILING, BALCONY)) {
            assertEquals(PartGenerators.Stage.BASE, PartGenerators.find(id).orElseThrow().stage(), id);
        }
    }
}
