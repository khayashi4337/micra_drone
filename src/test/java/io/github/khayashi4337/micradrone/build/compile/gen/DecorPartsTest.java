package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.onWall;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.IntPos;
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

class DecorPartsTest {
    private static final PartTypeRegistry REGISTRY = CompileFixtures.REGISTRY;

    private static final String LAMP = "micra:lamp";
    private static final String SIGN = "micra:sign";
    private static final String PLANTER = "micra:planter";
    private static final String TRIM = "micra:trim";
    private static final String BALCONY = "micra:balcony";
    private static final String STRUCTURE = "micra:structure";
    private static final String WALL = "micra:wall";
    private static final String STRUCTURE_ID = "s";
    private static final String WALL_N = "wall-n";
    private static final String WALL_E = "wall-e";

    private static final String LANTERN = "minecraft:lantern";
    private static final String TORCH = "minecraft:torch";
    private static final String OAK_FENCE = "minecraft:oak_fence";
    private static final String OAK_WALL_SIGN = "minecraft:oak_wall_sign";
    private static final String SPRUCE_WALL_SIGN = "minecraft:spruce_wall_sign";
    private static final String DIRT = "minecraft:dirt";
    private static final String POPPY = "minecraft:poppy";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String STONE_BRICK_SLAB = "minecraft:stone_brick_slab";
    /** Always refused by the block policy: a role mapped to it shows whether a part looked that role up. */
    private static final String BEDROCK = "minecraft:bedrock";

    // Palette roles the tests override.
    private static final String ROLE_FENCE = "fence";
    private static final String ROLE_FLOOR = "floor";
    private static final String ROLE_PLANT = "plant";
    private static final String ROLE_SIGN = "sign";
    private static final String ROLE_TRIM = "trim";

    // Parameter names and values of the parts under test, and of the building and its walls.
    private static final String P_KIND = "kind";
    private static final String P_HEIGHT = "height";
    private static final String P_TEXT = "text";
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_LENGTH = "length";
    private static final String P_AXIS = "axis";
    private static final String P_SHAPE = "shape";
    private static final String P_MATERIAL = "material";
    private static final String P_SIDE = "side";
    private static final String P_FROM = "from";
    private static final String P_THICKNESS = "thickness";
    private static final String P_FLOORS = "floors";
    private static final String P_FLOOR_HEIGHT = "floor_height";
    private static final String KIND_HANGING = "hanging";
    private static final String KIND_TORCH = "torch";
    private static final String KIND_POST = "post";
    private static final String AXIS_VERTICAL = "vertical";
    private static final String SHAPE_SLAB = "slab";
    private static final String NORTH = "north";
    private static final String SOUTH = "south";
    private static final String EAST = "east";

    // Block-state property names and values, as they are written in a block state.
    private static final String PROP_FACING = "facing";
    private static final String PROP_HANGING = "hanging";
    private static final String PROP_TYPE = "type";
    private static final String BOTTOM = "bottom";
    private static final String FALSE = "false";
    private static final String TRUE = "true";

    // Issue ids: CODE:node#key.
    private static final String SIGN_ID = "sg";
    private static final String PLANTER_ID = "pl";
    private static final String TRIM_ID = "tr";
    private static final String BALCONY_ID = "b";
    private static final String LAMP_ID = "l";
    private static final String SIGN_BAD_TEXT_ID = "E-PARAM-RANGE:" + SIGN_ID + "#text";
    private static final String SIGN_OFF_WALL_ID = "E-OPENING-NO-WALL:" + SIGN_ID + "#anchor";
    private static final String PLANTER_SIDE_ID = "E-ANCHOR:" + PLANTER_ID + "#anchor";
    private static final String PLANTER_EXTENT_ID = "E-ANCHOR:" + PLANTER_ID + "#extent";
    private static final String PLANTER_OFF_WALL_ID = "E-OPENING-NO-WALL:" + PLANTER_ID + "#anchor";
    private static final String TRIM_EXTENT_ID = "E-ANCHOR:" + TRIM_ID + "#extent";
    private static final String BALCONY_EXTENT_ID = "E-ANCHOR:" + BALCONY_ID + "#extent";
    private static final String BALCONY_INNER_ID = "E-ANCHOR:" + BALCONY_ID + "#anchor";
    private static final String TRIM_OFF_WALL_ID = "E-OPENING-NO-WALL:" + TRIM_ID + "#anchor";

    /** The hall: 7 x 7, one floor, floor height 4: walls are 3 rows high (v = 1..3, the floor takes v = 0), 7 long. */
    private static final int HALL_SIDE = 7;
    private static final int HALL_FLOOR_HEIGHT = 4;
    private static final int FLOORS = 1;
    private static final int HALL_WALL_ROWS = HALL_FLOOR_HEIGHT - 1;
    /** The hall's north wall in pieces: it covers u = 2..5 only, so its positions 0..3 are the building's u = 2..5. */
    private static final int SEGMENT_FROM = 2;
    private static final int SEGMENT_LENGTH = 4;
    private static final int MAX_CELLS = 1_000;

    private static PlanNode onNorthWall(String id, String type, Side side, int u, int v, Map<String, ParamValue> p) {
        return onWall(id, type, STRUCTURE_ID, WALL_N, side, u, v, p);
    }

    /** The hall's walls plus the decoration node given. */
    private static List<PlanNode> hallWith(PlanNode decor) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.add(decor);
        return nodes;
    }

    /** The hall whose north wall covers only u = SEGMENT_FROM.. (SEGMENT_LENGTH cells), with the part given. */
    private static List<PlanNode> hallWithNorthSegment(PlanNode decor) {
        List<PlanNode> nodes = hallWith(decor);
        nodes.replaceAll(n -> n.id().equals(WALL_N)
                ? node(WALL_N, WALL, STRUCTURE_ID, 0, 0, 0,
                        params(P_SIDE, NORTH, P_FROM, SEGMENT_FROM, P_LENGTH, SEGMENT_LENGTH))
                : n);
        return nodes;
    }

    /** The hall whose north wall is {@code thickness} cells thick, with the part given. */
    private static List<PlanNode> hallWithNorthWallThickness(int thickness, PlanNode decor) {
        List<PlanNode> nodes = hallWith(decor);
        nodes.replaceAll(n -> n.id().equals(WALL_N)
                ? node(WALL_N, WALL, STRUCTURE_ID, 0, 0, 0, params(P_SIDE, NORTH, P_THICKNESS, thickness))
                : n);
        return nodes;
    }

    /** The hall's walls moved so the building stands at (u, v, w), with the decoration nodes given. */
    private static List<PlanNode> hallAt(int u, int v, int w, PlanNode... decor) {
        List<PlanNode> nodes = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        nodes.replaceAll(n -> n.id().equals(STRUCTURE_ID) ? node(STRUCTURE_ID, STRUCTURE, null, u, v, w,
                params(P_WIDTH, HALL_SIDE, P_DEPTH, HALL_SIDE, P_FLOORS, FLOORS, P_FLOOR_HEIGHT, HALL_FLOOR_HEIGHT)) : n);
        nodes.addAll(List.of(decor));
        return nodes;
    }

    private static CompileResult withWalls(PlanNode decor) {
        return compile(hallWith(decor));
    }

    private static Map<LocalPos, BlockSpec> build(PlanNode decor) {
        CompileResult r = withWalls(decor);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    private static List<String> ids(CompileResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    private static StyleSpec roleIs(String role, String block) {
        return new StyleSpec(Map.of(role, block), Set.of());
    }

    private static Placement placementOf(CompileResult r, String partId) {
        return r.manifest().placements().stream().filter(p -> p.partNodeId().equals(partId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ lamp

    @Test
    void lampsInTheFourKinds() {
        // lantern (the default) stands; hanging hangs; a torch is a plain torch; a post is fence + a lantern on top
        Map<LocalPos, BlockSpec> lantern = cells(compile(List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, Map.of()))).manifest());
        assertEquals(BlockSpec.of(LANTERN, PROP_HANGING, FALSE), lantern.get(new LocalPos(0, 0, 0)));
        Map<LocalPos, BlockSpec> hanging = cells(
                compile(List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_HANGING)))).manifest());
        assertEquals(TRUE, hanging.get(new LocalPos(0, 0, 0)).get(PROP_HANGING));
        Map<LocalPos, BlockSpec> torch = cells(
                compile(List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_TORCH)))).manifest());
        assertEquals(BlockSpec.of(TORCH), torch.get(new LocalPos(0, 0, 0)));
        Map<LocalPos, BlockSpec> post = cells(
                compile(List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_POST, P_HEIGHT, 2)))).manifest());
        assertEquals(3, post.size());
        assertEquals(BlockSpec.of(OAK_FENCE), post.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of(OAK_FENCE), post.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of(LANTERN, PROP_HANGING, FALSE), post.get(new LocalPos(0, 2, 0)));
    }

    @Test
    void aLampPostStandsWhereItIsPut() {
        // The node is at (5, 2, -3): the pole is 2 fences (v = 2, 3) and the lantern sits on top (v = 4).
        CompileResult r = compile(List.of(node(LAMP_ID, LAMP, null, 5, 2, -3, params(P_KIND, KIND_POST, P_HEIGHT, 2))));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(5, 2, -3)));
        assertEquals(BlockSpec.of(OAK_FENCE), c.get(new LocalPos(5, 3, -3)));
        assertEquals(BlockSpec.of(LANTERN, PROP_HANGING, FALSE), c.get(new LocalPos(5, 4, -3)));
        // the lantern's world position: local (5,4,-3) on a NORTH frame at (100,64,200) is (105,68,203)
        IntPos lantern = r.manifest().placements().stream()
                .filter(p -> p.partNodeId().equals(LAMP_ID) && p.block().blockId().equals(LANTERN))
                .findFirst().orElseThrow().pos();
        assertEquals(new IntPos(105, 68, 203), lantern);
    }

    @Test
    void onlyALampPostAsksForTheFenceRole() {
        StyleSpec noFence = roleIs(ROLE_FENCE, BEDROCK);
        // a post places the fence role and is refused by the block policy
        assertEquals(List.of("E-BLOCK-FORBIDDEN:" + LAMP_ID + "#" + BEDROCK),
                ids(compile(noFence, List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_POST))))));
        // the other kinds never look the role up
        assertEquals(List.of(), ids(compile(noFence, List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, Map.of())))));
        assertEquals(List.of(), ids(compile(noFence, List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_HANGING))))));
        assertEquals(List.of(), ids(compile(noFence, List.of(node(LAMP_ID, LAMP, null, 0, 0, 0, params(P_KIND, KIND_TORCH))))));
    }

    // ------------------------------------------------------------------ sign

    @Test
    void aSignCarriesItsTextLinesAsBlockEntityConfig() {
        CompileResult r = withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "SHOP|OPEN 9-5")));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Placement sign = placementOf(r, SIGN_ID);
        // the north wall is at w = 6 facing north; the sign is one cell out (w = 7) at row 1 (v = 2)
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, NORTH), sign.block());
        assertEquals(Map.of("line1", "SHOP", "line2", "OPEN 9-5"), sign.blockEntityConfig());
        assertEquals(new IntPos(103, 66, 193), sign.pos(), "local (3,2,7) on a NORTH frame at (100,64,200)");
    }

    @Test
    void anInnerSignFacesIndoors() {
        Map<LocalPos, BlockSpec> c = build(onNorthWall(SIGN_ID, SIGN, Side.INNER, 3, 1, params(P_TEXT, "EXIT")));
        // the inner face of the one-thick wall is w = 5; an inner sign faces the other way
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, SOUTH), c.get(new LocalPos(3, 2, 5)));
    }

    @Test
    void anOuterSignOnAnEastWallFacesEast() {
        Map<LocalPos, BlockSpec> c = build(
                onWall(SIGN_ID, SIGN, STRUCTURE_ID, WALL_E, Side.OUTER, 2, 1, params(P_TEXT, "E")));
        // the east wall is at u = 6 facing east; the sign is one cell out (u = 7), position 2 along it (w = 2)
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, EAST), c.get(new LocalPos(7, 2, 2)));
    }

    @Test
    void aSignTakesFourFullLinesOfFifteenCharacters() {
        // the longest text the registry accepts: four lines of fifteen characters plus the three separators (63)
        String line = "123456789012345";
        CompileResult r = withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1,
                params(P_TEXT, line + "|" + line + "|" + line + "|" + line)));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        assertEquals(Map.of("line1", line, "line2", line, "line3", line, "line4", line),
                placementOf(r, SIGN_ID).blockEntityConfig());
    }

    @Test
    void signTextLimitsAreParamRangeIssues() {
        assertEquals(List.of(SIGN_BAD_TEXT_ID),
                ids(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "a|b|c|d|e")))), "five lines");
        assertEquals(List.of(SIGN_BAD_TEXT_ID),
                ids(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "a|b|c|d|")))),
                "a trailing separator opens a fifth, empty line");
        assertEquals(List.of(SIGN_BAD_TEXT_ID),
                ids(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "0123456789abcdef")))),
                "a sixteen-letter line");
        // a first line of exactly fifteen characters with three shorter lines after it is accepted
        assertTrue(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "123456789012345|b|c|d")))
                .issues().isEmpty());
        assertTrue(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "日本語の看板"))).issues().isEmpty());
        // an empty line keeps its place but writes no line key
        CompileResult skipped = withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "A||B")));
        assertTrue(skipped.issues().isEmpty(), skipped.issues().toString());
        assertEquals(Map.of("line1", "A", "line3", "B"), placementOf(skipped, SIGN_ID).blockEntityConfig());
        // a refused text is checked before the material: a sign-role lookup would be caught by the block policy
        assertEquals(List.of(SIGN_BAD_TEXT_ID),
                ids(compile(roleIs(ROLE_SIGN, BEDROCK),
                        hallWith(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "a|b|c|d|e"))))),
                "no E-BLOCK-FORBIDDEN: the sign role was never resolved");
    }

    @Test
    void aSignTakesTheSignRoleOrAGivenBlock() {
        assertEquals(List.of("E-BLOCK-FORBIDDEN:" + SIGN_ID + "#" + BEDROCK),
                ids(compile(roleIs(ROLE_SIGN, BEDROCK),
                        hallWith(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "X"))))));
        Map<LocalPos, BlockSpec> c = build(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1,
                params(P_TEXT, "X", P_MATERIAL, SPRUCE_WALL_SIGN)));
        assertEquals(BlockSpec.of(SPRUCE_WALL_SIGN, PROP_FACING, NORTH), c.get(new LocalPos(3, 2, 7)));
    }

    // ------------------------------------------------------------------ planter

    @Test
    void aPlanterIsSoilWithFlowersOnTop() {
        Map<LocalPos, BlockSpec> c = build(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 1, 0, params(P_WIDTH, 3)));
        // one cell outside the wall (w = 7): soil at the anchored row (v = 1), flowers one row up
        assertEquals(BlockSpec.of(DIRT), c.get(new LocalPos(1, 1, 7)));
        assertEquals(BlockSpec.of(DIRT), c.get(new LocalPos(3, 1, 7)));
        assertEquals(BlockSpec.of(POPPY), c.get(new LocalPos(1, 2, 7)));
        assertEquals(BlockSpec.of(POPPY), c.get(new LocalPos(3, 2, 7)));
        assertEquals(3, countOf(c, DIRT));
        assertEquals(3, countOf(c, POPPY));
        assertEquals(List.of(PLANTER_SIDE_ID),
                ids(withWalls(onNorthWall(PLANTER_ID, PLANTER, Side.INNER, 1, 0, Map.of()))),
                "a planter is only ever on the outer side");
    }

    @Test
    void aPlanterRunsAlongItsWallNotPastIt() {
        // the north wall is 7 long: positions 0..6. From position 4 a width of 3 ends on position 6, the last one.
        Map<LocalPos, BlockSpec> fits = build(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 4, 0, params(P_WIDTH, 3)));
        assertEquals(BlockSpec.of(DIRT), fits.get(new LocalPos(6, 1, 7)));
        // one cell over: from position 5 the same planter ends past the wall's end
        assertEquals(List.of(PLANTER_EXTENT_ID),
                ids(withWalls(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 5, 0, params(P_WIDTH, 3)))));
        // the extent is the wall's, not the side's: the segment covers u = 2..5 (positions 0..3)
        assertEquals(List.of(PLANTER_EXTENT_ID),
                ids(compile(hallWithNorthSegment(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 2, 0, params(P_WIDTH, 3))))));
        Map<LocalPos, BlockSpec> segment = cells(compile(
                hallWithNorthSegment(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 1, 0, params(P_WIDTH, 3)))).manifest());
        assertEquals(BlockSpec.of(DIRT), segment.get(new LocalPos(5, 1, 7)),
                "position 3 of the segment is the building's u = 5");
    }

    @Test
    void aPlanterOffTheWallIsRefusedBeforeItsMaterialsAreLookedUp() {
        // plant -> bedrock: a lookup would be caught by the block policy, so its absence says the extent check ran first
        assertEquals(List.of(PLANTER_EXTENT_ID),
                ids(compile(roleIs(ROLE_PLANT, BEDROCK),
                        hallWith(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 5, 0, params(P_WIDTH, 3))))));
        // and a fitting planter does look the role up
        assertEquals(List.of("E-BLOCK-FORBIDDEN:" + PLANTER_ID + "#" + BEDROCK),
                ids(compile(roleIs(ROLE_PLANT, BEDROCK),
                        hallWith(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 1, 0, params(P_WIDTH, 3))))));
    }

    // ------------------------------------------------------------------ trim

    @Test
    void aTrimCourseRunsAlongOrUpTheWall() {
        Map<LocalPos, BlockSpec> c = build(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 0, 2, params(P_LENGTH, 4, P_SHAPE, SHAPE_SLAB)));
        // one cell outside the wall (w = 7): four bottom slabs at row 2 (v = 3, the wall's top row)
        assertEquals(BlockSpec.of(STONE_BRICK_SLAB, PROP_TYPE, BOTTOM), c.get(new LocalPos(3, 3, 7)));
        assertEquals(4, countOf(c, STONE_BRICK_SLAB));
        Map<LocalPos, BlockSpec> v = build(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 2, P_AXIS, AXIS_VERTICAL)));
        assertEquals(BlockSpec.of(STONE_BRICKS), v.get(new LocalPos(5, 1, 7)));
        assertEquals(BlockSpec.of(STONE_BRICKS), v.get(new LocalPos(5, 2, 7)));
    }

    @Test
    void anInnerTrimRunsAlongTheInnerFace() {
        // position 2: the inside face at position 0 is the west wall's corner column, an overlap, not a free cell
        Map<LocalPos, BlockSpec> c = build(onNorthWall(TRIM_ID, TRIM, Side.INNER, 2, 1, params(P_LENGTH, 2)));
        // the inner face of the one-thick wall is w = 5
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(2, 2, 5)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(3, 2, 5)));
    }

    @Test
    void anInnerFaceIsTheFirstCellInsideTheWallsOwnThickness() {
        // layer = thickness: a constant inside layer would land on w = 5 at every thickness
        CompileResult signOnTwo = compile(
                hallWithNorthWallThickness(2, onNorthWall(SIGN_ID, SIGN, Side.INNER, 3, 1, params(P_TEXT, "IN"))));
        assertTrue(signOnTwo.issues().isEmpty(), signOnTwo.issues().toString());
        // the two-cell wall occupies w = 6 and w = 5; the sign sits on the first cell inside, w = 4
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, SOUTH),
                cells(signOnTwo.manifest()).get(new LocalPos(3, 2, 4)));
        CompileResult signOnThree = compile(
                hallWithNorthWallThickness(3, onNorthWall(SIGN_ID, SIGN, Side.INNER, 3, 1, params(P_TEXT, "IN"))));
        assertTrue(signOnThree.issues().isEmpty(), signOnThree.issues().toString());
        // the three-cell wall occupies w = 6, 5 and 4; the sign sits on the first cell inside, w = 3
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, SOUTH),
                cells(signOnThree.manifest()).get(new LocalPos(3, 2, 3)));
        CompileResult trimOnTwo = compile(
                hallWithNorthWallThickness(2, onNorthWall(TRIM_ID, TRIM, Side.INNER, 2, 1, params(P_LENGTH, 2))));
        assertTrue(trimOnTwo.issues().isEmpty(), trimOnTwo.issues().toString());
        Map<LocalPos, BlockSpec> onTwo = cells(trimOnTwo.manifest());
        assertEquals(BlockSpec.of(STONE_BRICKS), onTwo.get(new LocalPos(2, 2, 4)));
        assertEquals(BlockSpec.of(STONE_BRICKS), onTwo.get(new LocalPos(3, 2, 4)));
        CompileResult trimOnThree = compile(
                hallWithNorthWallThickness(3, onNorthWall(TRIM_ID, TRIM, Side.INNER, 2, 1, params(P_LENGTH, 2))));
        assertTrue(trimOnThree.issues().isEmpty(), trimOnThree.issues().toString());
        Map<LocalPos, BlockSpec> onThree = cells(trimOnThree.manifest());
        assertEquals(BlockSpec.of(STONE_BRICKS), onThree.get(new LocalPos(2, 2, 3)));
        assertEquals(BlockSpec.of(STONE_BRICKS), onThree.get(new LocalPos(3, 2, 3)));
    }

    @Test
    void aTrimCourseMustFitItsWall() {
        // the north wall is 7 long and 3 rows high: positions 0..6, rows 0..2
        assertEquals(List.of(TRIM_EXTENT_ID),
                ids(withWalls(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 3)))),
                "length 3 from position 5 ends past the wall's end");
        assertEquals(List.of(TRIM_EXTENT_ID),
                ids(withWalls(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 0, 2, params(P_LENGTH, 2, P_AXIS, AXIS_VERTICAL)))),
                "two rows from row 2 run past the wall's top");
        // exact fits: length 2 from position 5 ends on position 6; three rows from row 0 end on the top row
        assertTrue(withWalls(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 2))).issues().isEmpty());
        assertTrue(withWalls(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 0, 0, params(P_LENGTH, 3, P_AXIS, AXIS_VERTICAL)))
                .issues().isEmpty());
        // the material is not looked up when the course does not fit
        assertEquals(List.of(TRIM_EXTENT_ID),
                ids(compile(roleIs(ROLE_TRIM, BEDROCK),
                        hallWith(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 3))))));
        assertEquals(List.of("E-BLOCK-FORBIDDEN:" + TRIM_ID + "#" + BEDROCK),
                ids(compile(roleIs(ROLE_TRIM, BEDROCK),
                        hallWith(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 2))))));
    }

    // ------------------------------------------------------------------ anchor

    @Test
    void decorationsNeedAPositionOnTheWallFace() {
        // u and v are checked against the wall's own extent (7 long, 3 high): off the face is E-OPENING-NO-WALL
        assertEquals(List.of(SIGN_OFF_WALL_ID),
                ids(withWalls(onNorthWall(SIGN_ID, SIGN, Side.OUTER, HALL_SIDE, 0, params(P_TEXT, "X")))));
        assertEquals(List.of(TRIM_OFF_WALL_ID),
                ids(withWalls(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 0, HALL_WALL_ROWS, Map.of()))));
        // a target that is not a wall is refused by the patcher (E-ANCHOR); the generator's own refusal for it is
        // pinned in aRefusedDecorationLeavesNothingOfItselfOnTheCanvas, which calls the generator directly
    }

    // ------------------------------------------------------------------ non-zero origin

    @Test
    void decorationsStandWhereTheBuildingStands() {
        // The building's origin is (5, 2, -3): its north wall is at w = 3 (one cell out is w = 4), its positions are
        // u = 5..11 and its rows are v = 3..5 (the storey's floor is v = 2).
        List<PlanNode> nodes = hallAt(5, 2, -3,
                onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "OPEN")),
                onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 1, 0, params(P_WIDTH, 2)),
                onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 2, params(P_LENGTH, 2)));
        CompileResult r = compile(nodes);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        Map<LocalPos, BlockSpec> c = cells(r.manifest());
        // sign: position 3, row 1 -> local (8, 4, 4)
        assertEquals(BlockSpec.of(OAK_WALL_SIGN, PROP_FACING, NORTH), c.get(new LocalPos(8, 4, 4)));
        // planter: positions 1..2 -> u = 6..7; soil at row 0 (v = 3), plants at v = 4
        assertEquals(BlockSpec.of(DIRT), c.get(new LocalPos(6, 3, 4)));
        assertEquals(BlockSpec.of(DIRT), c.get(new LocalPos(7, 3, 4)));
        assertEquals(BlockSpec.of(POPPY), c.get(new LocalPos(6, 4, 4)));
        assertEquals(BlockSpec.of(POPPY), c.get(new LocalPos(7, 4, 4)));
        // trim: positions 5..6 -> u = 10..11, row 2 -> v = 5
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(10, 5, 4)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(11, 5, 4)));
        // the same cells in the world: local (u,v,w) on a NORTH frame at (100,64,200) is (100+u, 64+v, 200-w)
        assertEquals(new IntPos(108, 68, 196), placementOf(r, SIGN_ID).pos());
        assertEquals(new IntPos(106, 67, 196), placementOf(r, PLANTER_ID).pos());
        assertEquals(new IntPos(110, 69, 196), placementOf(r, TRIM_ID).pos());
    }

    // ------------------------------------------------------------------ refusal leaves nothing

    private static PartGenerator generatorOf(String partId) {
        return PartGenerators.find(partId).orElseThrow().generator();
    }

    private static Params resolved(PlanNode n) {
        return Params.resolve(REGISTRY.get(n.type()), n.params());
    }

    /** A context holding the hall's building and walls, generated, and the part, known to the context but not generated. */
    private static GenContext hallAndPart(PlanNode part, Map<String, String> paletteOverride) {
        List<PlanNode> hall = new ArrayList<>(shell(HALL_SIDE, HALL_SIDE, FLOORS, HALL_FLOOR_HEIGHT));
        List<PlanNode> all = new ArrayList<>(hall);
        all.add(part);
        List<Issue> issues = new ArrayList<>();
        Map<String, PlanNode> byId = new HashMap<>();
        all.forEach(n -> byId.put(n.id(), n));
        Map<String, LocalPos> origins = Origins.resolve(all, SlotResolver.NONE, issues);
        GenContext ctx = new GenContext(REGISTRY, new Palette(REGISTRY.defaultPalette(), paletteOverride),
                new Canvas(MAX_CELLS), issues, byId, origins);
        for (PlanNode n : hall) {
            generatorOf(n.type()).generate(ctx, n, resolved(n));
        }
        assertTrue(issues.isEmpty(), issues.toString());
        return ctx;
    }

    private record Refusal(PlanNode part, Map<String, String> paletteOverride, String issueId) {
    }

    @Test
    void aRefusedDecorationLeavesNothingOfItselfOnTheCanvas() {
        // What this pins: the issue id of each refusal, that the canvas is untouched afterwards, and that no
        // material was resolved before the check — a lookup would record the block in palette().usedBy(). The
        // bedrock overrides are inert in a direct generator call (the block policy only runs in a full compile),
        // so usedBy, not an issue, is what proves the order.
        List<Refusal> refusals = List.of(
                new Refusal(onNorthWall(PLANTER_ID, PLANTER, Side.INNER, 1, 0, Map.of()), Map.of(), PLANTER_SIDE_ID),
                new Refusal(onNorthWall(PLANTER_ID, PLANTER, Side.OUTER, 5, 0, params(P_WIDTH, 3)),
                        Map.of(ROLE_PLANT, BEDROCK), PLANTER_EXTENT_ID),
                new Refusal(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 5, 0, params(P_LENGTH, 3)),
                        Map.of(ROLE_TRIM, BEDROCK), TRIM_EXTENT_ID),
                new Refusal(onNorthWall(TRIM_ID, TRIM, Side.OUTER, 0, 2, params(P_LENGTH, 2, P_AXIS, AXIS_VERTICAL)),
                        Map.of(ROLE_TRIM, BEDROCK), TRIM_EXTENT_ID),
                new Refusal(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "a|b|c|d|e")),
                        Map.of(ROLE_SIGN, BEDROCK), SIGN_BAD_TEXT_ID),
                new Refusal(onNorthWall(SIGN_ID, SIGN, Side.OUTER, 3, 1, params(P_TEXT, "0123456789abcdef")),
                        Map.of(ROLE_SIGN, BEDROCK), SIGN_BAD_TEXT_ID),
                // a balcony has two blocks to look up (the floor's material and the fence role); neither may be asked
                // for before it is refused, on the inner face or past the wall's end
                new Refusal(onNorthWall(BALCONY_ID, BALCONY, Side.INNER, 1, 0, Map.of()),
                        Map.of(ROLE_FLOOR, BEDROCK, ROLE_FENCE, BEDROCK), BALCONY_INNER_ID),
                new Refusal(onNorthWall(BALCONY_ID, BALCONY, Side.OUTER, 5, 0, params(P_WIDTH, 3)),
                        Map.of(ROLE_FLOOR, BEDROCK, ROLE_FENCE, BEDROCK), BALCONY_EXTENT_ID),
                // a target that is not a wall (the patcher refuses it at plan level, so this calls the generator
                // directly): the anchor check of wallOfAnchor
                new Refusal(onWall(PLANTER_ID, PLANTER, STRUCTURE_ID, STRUCTURE_ID, Side.OUTER, 0, 0, Map.of()),
                        Map.of(), PLANTER_OFF_WALL_ID));
        for (Refusal r : refusals) {
            GenContext ctx = hallAndPart(r.part(), r.paletteOverride());
            int before = ctx.canvas().size();
            GenAbort refusal = assertThrows(GenAbort.class,
                    () -> generatorOf(r.part().type()).generate(ctx, r.part(), resolved(r.part())));
            assertEquals(r.issueId(), refusal.issue().id());
            assertEquals(before, ctx.canvas().size(), r.issueId() + ": nothing was placed");
            assertTrue(ctx.canvas().all().stream().noneMatch(cell -> cell.ownerId().equals(r.part().id())), r.issueId());
            for (String block : r.paletteOverride().values()) {
                assertFalse(ctx.palette().usedBy().containsKey(block), r.issueId() + ": " + block + " was resolved");
            }
        }
    }

    // ------------------------------------------------------------------ registration

    @Test
    void allFourPartsAreBaseStageGenerators() {
        for (String id : List.of(LAMP, SIGN, PLANTER, TRIM)) {
            assertEquals(PartGenerators.Stage.BASE, PartGenerators.find(id).orElseThrow().stage(), id);
        }
    }
}
