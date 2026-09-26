package io.github.khayashi4337.micradrone.build.compile.gen;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoofTest {
    private static final String STAIRS = "minecraft:oak_stairs";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String OAK_SLAB = "minecraft:oak_slab";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final String GLASS = "minecraft:glass";
    private static final String RED_TERRACOTTA = "minecraft:red_terracotta";
    private static final String RED_NETHER_BRICK_STAIRS = "minecraft:red_nether_brick_stairs";
    /** Always refused by the block policy: a role mapped to it shows whether the roof looked that role up. */
    private static final String BEDROCK = "minecraft:bedrock";

    private static final String STRUCTURE = "micra:structure";
    private static final String ROOF = "micra:roof";
    private static final String PILLAR = "micra:pillar";
    private static final String STRUCTURE_ID = "s";
    private static final String ROOF_ID = "r";
    private static final String PILLAR_ID = "a";

    // Palette roles.
    private static final String ROLE_ROOF = "roof";
    private static final String ROLE_ROOF_STAIRS = "roof_stairs";
    private static final String ROLE_ROOF_SLAB = "roof_slab";
    private static final String ROLE_GLASS = "glass";
    private static final String ROLE_WALL = "wall";

    // Parameter names.
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_FLOORS = "floors";
    private static final String P_FLOOR_HEIGHT = "floor_height";
    private static final String P_KIND = "kind";
    private static final String P_OVERHANG = "overhang";
    private static final String P_RIDGE = "ridge";
    private static final String P_HIGH_SIDE = "high_side";
    private static final String P_GABLE_FILL = "gable_fill";
    private static final String P_TOOTH = "tooth";
    private static final String P_MONITOR_WIDTH = "monitor_width";
    private static final String P_MONITOR_HEIGHT = "monitor_height";
    private static final String P_HEIGHT = "height";

    private static final String HIP = "hip";
    private static final String FLAT = "flat";
    private static final String SHED = "shed";
    private static final String SAWTOOTH = "sawtooth";
    private static final String MONITOR = "monitor";

    private static final String FACING = "facing";
    private static final String NORTH = "north";
    private static final String EAST = "east";
    private static final String SOUTH = "south";
    private static final String WEST = "west";
    private static final String HALF = "half";
    private static final String BOTTOM = "bottom";
    private static final String TYPE = "type";

    private static final String E_PARAM_RANGE = "E-PARAM-RANGE";
    private static final String E_BLOCK_FORBIDDEN = "E-BLOCK-FORBIDDEN";
    private static final String E_ANCHOR = "E-ANCHOR";
    private static final String MONITOR_WIDTH_ISSUE = "E-PARAM-RANGE:" + ROOF_ID + "#" + P_MONITOR_WIDTH;
    private static final String MATERIAL_ISSUE = "E-PARAM-RANGE:" + ROOF_ID + "#material";

    private static final StyleSpec GLASS_STYLE = new StyleSpec(Map.of(ROLE_GLASS, GLASS), Set.of());

    /** A bare structure (no walls) plus a roof: the roof base is at v = floors * floorHeight = 4. */
    private static Map<LocalPos, BlockSpec> roof(int width, int depth, Map<String, ParamValue> roofParams) {
        return roof(StyleSpec.EMPTY, width, depth, roofParams);
    }

    private static Map<LocalPos, BlockSpec> roof(StyleSpec style, int width, int depth, Map<String, ParamValue> roofParams) {
        CompileResult r = compileRoof(style, width, depth, roofParams);
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    private static CompileResult compileRoof(StyleSpec style, int width, int depth, Map<String, ParamValue> roofParams) {
        return compile(style, List.of(
                node(STRUCTURE_ID, STRUCTURE, null, 0, 0, 0, params(P_WIDTH, width, P_DEPTH, depth)),
                node(ROOF_ID, ROOF, STRUCTURE_ID, 0, 0, 0, roofParams)));
    }

    private static BlockSpec stair(String id, String facing) {
        return BlockSpec.of(id, FACING, facing, HALF, BOTTOM);
    }

    private static Issue onlyIssue(CompileResult r) {
        assertEquals(1, r.issues().size(), r.issues().toString());
        return r.issues().get(0);
    }

    @Test
    void aGableRoofOverASevenBySevenBuilding() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_OVERHANG, 0));
        assertEquals(42, countOf(c, STAIRS), "three layers of two stair columns, seven cells long");
        assertEquals(7, countOf(c, PLANKS), "the ridge row is full blocks of the roof material");
        assertEquals(18, countOf(c, STONE_BRICKS), "gable ends: 0+1+2+3+2+1+0 = 9 per end");
        assertEquals(67, c.size());
        // stairs lean toward the ridge: the low side (u=0) has its back to the east
        assertEquals(stair(STAIRS, EAST), c.get(new LocalPos(0, 4, 3)));
        assertEquals(stair(STAIRS, WEST), c.get(new LocalPos(6, 4, 3)));
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(3, 7, 3)), "the ridge");
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(3, 6, 0)), "gable end fill under the ridge");
        assertNull(c.get(new LocalPos(3, 6, 3)), "the attic is open");
    }

    @Test
    void theRidgeFollowsTheLongerSide() {
        Map<LocalPos, BlockSpec> c = roof(9, 5, params(P_OVERHANG, 0));
        // ridge along u, slopes across w (T=5): two layers of two stair rows, nine cells long, ridge row of nine blocks
        assertEquals(36, countOf(c, STAIRS));
        assertEquals(9, countOf(c, PLANKS));
        assertEquals(NORTH, c.get(new LocalPos(4, 4, 0)).get(FACING), "the w=0 side leans toward +w");
        assertEquals(SOUTH, c.get(new LocalPos(4, 4, 4)).get(FACING));
        Map<LocalPos, BlockSpec> forced = roof(9, 5, params(P_OVERHANG, 0, P_RIDGE, "w"));
        assertTrue(countOf(forced, STAIRS) != 36, "an explicit ridge overrides the automatic one");
    }

    @Test
    void anEvenWidthGableHasTwoStairsAtTheRidgeAndNoBlockRow() {
        Map<LocalPos, BlockSpec> c = roof(6, 7, params(P_OVERHANG, 0, P_GABLE_FILL, false));
        // T=6 across u (ridge along w since depth 7 > width 6): layers k=0,1,2, two columns each, 7 long
        assertEquals(42, countOf(c, STAIRS));
        assertEquals(42, c.size());
        assertEquals(EAST, c.get(new LocalPos(2, 6, 0)).get(FACING));
        assertEquals(WEST, c.get(new LocalPos(3, 6, 0)).get(FACING));
    }

    @Test
    void overhangGrowsTheRoofPastTheWalls() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_OVERHANG, 1, P_GABLE_FILL, false));
        assertTrue(c.containsKey(new LocalPos(-1, 4, -1)));
        assertTrue(c.containsKey(new LocalPos(7, 4, 7)));
        assertNull(c.get(new LocalPos(-2, 4, 0)));
    }

    @Test
    void aHipRoofShrinksRingByRing() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_KIND, HIP, P_OVERHANG, 0));
        assertEquals(48, countOf(c, STAIRS), "rings of 24, 16 and 8 cells");
        assertEquals(1, countOf(c, PLANKS), "the last ring is one block");
        assertEquals(49, c.size());
        assertEquals(EAST, c.get(new LocalPos(0, 4, 0)).get(FACING), "corners prefer the east/west lean");
        assertEquals(NORTH, c.get(new LocalPos(3, 4, 0)).get(FACING));
        assertEquals(SOUTH, c.get(new LocalPos(3, 4, 6)).get(FACING));
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(3, 7, 3)));
    }

    @Test
    void aFlatRoofIsOneLayerOfBottomSlabs() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_KIND, FLAT));
        assertEquals(81, c.size());
        assertEquals(BlockSpec.of(OAK_SLAB, TYPE, BOTTOM), c.get(new LocalPos(-1, 4, -1)));
    }

    @Test
    void aShedRoofRisesTowardItsHighSide() {
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_KIND, SHED, P_OVERHANG, 0, P_HIGH_SIDE, EAST));
        assertEquals(49, countOf(c, STAIRS));
        assertEquals(42, countOf(c, STONE_BRICKS), "end triangles: 0+1+...+6 = 21 per end");
        assertEquals(stair(STAIRS, EAST), c.get(new LocalPos(0, 4, 3)));
        assertEquals(stair(STAIRS, EAST), c.get(new LocalPos(6, 10, 3)));
        Map<LocalPos, BlockSpec> west = roof(7, 7, params(P_KIND, SHED, P_OVERHANG, 0, P_HIGH_SIDE, WEST, P_GABLE_FILL, false));
        assertEquals(WEST, west.get(new LocalPos(0, 10, 3)).get(FACING));
        Map<LocalPos, BlockSpec> north = roof(7, 7, params(P_KIND, SHED, P_OVERHANG, 0, P_HIGH_SIDE, NORTH, P_GABLE_FILL, false));
        assertEquals(NORTH, north.get(new LocalPos(3, 10, 6)).get(FACING));
    }

    @Test
    void aSawtoothRoofHasStairTeethAndGlassSteps() {
        Map<LocalPos, BlockSpec> c = roof(GLASS_STYLE, 7, 7, params(P_KIND, SAWTOOTH, P_OVERHANG, 0, P_TOOTH, 3));
        assertEquals(49, countOf(c, STAIRS));
        assertEquals(21, countOf(c, GLASS), "one glass row above the last stair of each of the three teeth");
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(2, 7, 0)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(6, 5, 0)), "the last, partial tooth is one column wide");
        assertEquals(70, c.size());
    }

    @Test
    void aMonitorRoofHasAClerestoryOnTop() {
        Map<LocalPos, BlockSpec> c = roof(GLASS_STYLE, 7, 7, params(P_KIND, MONITOR, P_OVERHANG, 0));
        assertEquals(42, countOf(c, STAIRS));
        assertEquals(7, countOf(c, GLASS));
        assertEquals(7, countOf(c, OAK_SLAB));
        assertEquals(18, countOf(c, STONE_BRICKS));
        assertEquals(74, c.size());
        assertEquals(BlockSpec.of(OAK_SLAB, TYPE, BOTTOM), c.get(new LocalPos(3, 8, 3)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(3, 7, 3)));
        CompileResult parity = compileRoof(StyleSpec.EMPTY, 7, 7, params(P_KIND, MONITOR, P_MONITOR_WIDTH, 2, P_OVERHANG, 0));
        assertEquals(List.of(E_PARAM_RANGE), codes(parity), "a 7-wide roof cannot have a 2-wide clerestory");
    }

    @Test
    void materialsWithoutStairsAreRefusedWithAHint() {
        StyleSpec terracotta = new StyleSpec(Map.of(ROLE_ROOF, RED_TERRACOTTA), Set.of());
        CompileResult r = compile(terracotta, List.of(node(STRUCTURE_ID, STRUCTURE, null, 0, 0, 0, Map.of()),
                node(ROOF_ID, ROOF, STRUCTURE_ID, 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of(E_PARAM_RANGE), codes(r));
        assertEquals(MATERIAL_ISSUE, r.issues().get(0).id());
        assertTrue(r.issues().get(0).hints().get(0).args().get("ids").contains("red_nether_bricks"));
        // an explicit stairs role makes a terracotta ridge with brick stairs possible
        StyleSpec explicit = new StyleSpec(Map.of(ROLE_ROOF, RED_TERRACOTTA, ROLE_ROOF_STAIRS, RED_NETHER_BRICK_STAIRS), Set.of());
        Map<LocalPos, BlockSpec> c = roof(explicit, 7, 7, params(P_OVERHANG, 0, P_GABLE_FILL, false));
        assertEquals(42, countOf(c, RED_NETHER_BRICK_STAIRS));
        assertEquals(7, countOf(c, RED_TERRACOTTA));
    }

    @Test
    void aRoofNeedsABuildingParent() {
        CompileResult r = compile(List.of(node(ROOF_ID, ROOF, null, 0, 0, 0, Map.of())));
        assertEquals(List.of(E_ANCHOR), codes(r));
    }

    @Test
    void theRoofSitsOnTopOfTheLastFloor() {
        CompileResult r = compile(StyleSpec.EMPTY, List.of(
                node(STRUCTURE_ID, STRUCTURE, null, 0, 0, 0, params(P_WIDTH, 5, P_DEPTH, 5, P_FLOORS, 2, P_FLOOR_HEIGHT, 3)),
                node(ROOF_ID, ROOF, STRUCTURE_ID, 0, 0, 0, params(P_OVERHANG, 0, P_KIND, FLAT))));
        assertEquals(6, cells(r.manifest()).keySet().iterator().next().v(), "2 floors x 3 = base at v=6");
    }

    // ---- Cases derived by hand from design 05 §1.1.1, independent of the cases above. ----

    @Test
    void aThreeWideGableIsOneStairPairUnderOneRidgeColumn() {
        // 3x5, ridge along w (depth 5 >= width 3), T=3: k=0 stairs at u=0 and u=2 (2x5=10), k=1 ridge at u=1 (5 blocks);
        // gable ends w=0 and w=4: column heights min(u-0, 2-u) = 0,1,0 -> one wall block per end at v=4
        Map<LocalPos, BlockSpec> c = roof(3, 5, params(P_OVERHANG, 0));
        assertEquals(10, countOf(c, STAIRS));
        assertEquals(5, countOf(c, PLANKS));
        assertEquals(2, countOf(c, STONE_BRICKS));
        assertEquals(17, c.size());
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(1, 5, 2)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(1, 4, 0)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(1, 4, 4)));
        assertEquals(stair(STAIRS, EAST), c.get(new LocalPos(0, 4, 2)));
        assertEquals(stair(STAIRS, WEST), c.get(new LocalPos(2, 4, 2)));
    }

    @Test
    void anEvenGableNeverLooksUpItsFullBlock() {
        // the roof role names a forbidden full block; only its stairs (an explicit role) are placed by an even-width gable
        StyleSpec style = new StyleSpec(Map.of(ROLE_ROOF, BEDROCK, ROLE_ROOF_STAIRS, STAIRS), Set.of());
        assertEquals(42, countOf(roof(style, 6, 7, params(P_OVERHANG, 0, P_GABLE_FILL, false)), STAIRS));
        CompileResult odd = compileRoof(style, 7, 7, params(P_OVERHANG, 0, P_GABLE_FILL, false));
        assertEquals(List.of(E_BLOCK_FORBIDDEN), codes(odd), "an odd width has a ridge row of the full block");
    }

    @Test
    void aHipOnASquareEndsInOneBlockAndAnEvenSquareInFourStairs() {
        // 5x5: rings of 16 and 8 stairs, then one full block at k=2
        Map<LocalPos, BlockSpec> c = roof(5, 5, params(P_KIND, HIP, P_OVERHANG, 0));
        assertEquals(24, countOf(c, STAIRS));
        assertEquals(1, countOf(c, PLANKS));
        assertEquals(25, c.size());
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(2, 6, 2)));
        // 6x6: rings of 20, 12 and 4 stairs; the last ring does not collapse, so the full block is never looked up
        StyleSpec style = new StyleSpec(Map.of(ROLE_ROOF, BEDROCK, ROLE_ROOF_STAIRS, STAIRS), Set.of());
        Map<LocalPos, BlockSpec> even = roof(style, 6, 6, params(P_KIND, HIP, P_OVERHANG, 0));
        assertEquals(36, countOf(even, STAIRS));
        assertEquals(36, even.size());
        assertEquals(EAST, even.get(new LocalPos(2, 6, 3)).get(FACING));
        assertEquals(WEST, even.get(new LocalPos(3, 6, 2)).get(FACING));
    }

    @Test
    void aHipOnARectangleEndsInARidgeLine() {
        // 7x5: rings of 20 (7x5) and 12 (5x3) stairs; at k=2 the ring is the line u=2..4, w=2: three full blocks
        Map<LocalPos, BlockSpec> c = roof(7, 5, params(P_KIND, HIP, P_OVERHANG, 0));
        assertEquals(32, countOf(c, STAIRS));
        assertEquals(3, countOf(c, PLANKS));
        assertEquals(35, c.size());
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(2, 6, 2)));
        assertEquals(BlockSpec.of(PLANKS), c.get(new LocalPos(4, 6, 2)));
        assertEquals(EAST, c.get(new LocalPos(1, 5, 1)).get(FACING), "a corner of the inner ring leans east/west");
        assertEquals(WEST, c.get(new LocalPos(5, 5, 3)).get(FACING));
        assertEquals(SOUTH, c.get(new LocalPos(3, 5, 3)).get(FACING));
    }

    @Test
    void aShedToTheSouthRisesTowardSmallW() {
        // high side south: a=w, rise = a1 - w, so w=0 is the top row at v=4+6
        Map<LocalPos, BlockSpec> c = roof(7, 7, params(P_KIND, SHED, P_OVERHANG, 0, P_HIGH_SIDE, SOUTH, P_GABLE_FILL, false));
        assertEquals(49, c.size());
        assertEquals(stair(STAIRS, SOUTH), c.get(new LocalPos(3, 10, 0)));
        assertEquals(stair(STAIRS, SOUTH), c.get(new LocalPos(3, 4, 6)));
    }

    @Test
    void aSawtoothAcrossWHasTeethThatClimbNorth() {
        // 9x5: ridge along u, a=w, T=5, tooth 2: idx 0,1,0,1,0 -> glass above w=1 and w=3 (v=6) and above the partial tooth w=4 (v=5)
        Map<LocalPos, BlockSpec> c = roof(GLASS_STYLE, 9, 5, params(P_KIND, SAWTOOTH, P_OVERHANG, 0, P_TOOTH, 2));
        assertEquals(45, countOf(c, STAIRS));
        assertEquals(27, countOf(c, GLASS));
        assertEquals(72, c.size());
        assertEquals(stair(STAIRS, NORTH), c.get(new LocalPos(0, 4, 0)));
        assertEquals(stair(STAIRS, NORTH), c.get(new LocalPos(8, 5, 1)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(4, 6, 1)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(4, 6, 3)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(4, 5, 4)));
    }

    @Test
    void aWideMonitorHasGlassOnBothEdgesAndGlassInItsEnds() {
        // 7x7, monitor 3 wide and 2 high: K=(7-3)/2=2 gable layers (2x7x2=28 stairs); gap a=2..4
        // edge glass: a=2 and a=4, v=6..7, 7 long = 28; cap: a=2..4 at v=8 = 21 slabs
        // ends: wall heights min(a, 6-a, 2) = 0,1,2,2,2,1,0 = 8 per end = 16; inner glass a=3, v=6..7 at w=0 and 6 = 4
        Map<LocalPos, BlockSpec> c = roof(GLASS_STYLE, 7, 7,
                params(P_KIND, MONITOR, P_OVERHANG, 0, P_MONITOR_WIDTH, 3, P_MONITOR_HEIGHT, 2));
        assertEquals(28, countOf(c, STAIRS));
        assertEquals(32, countOf(c, GLASS));
        assertEquals(21, countOf(c, OAK_SLAB));
        assertEquals(16, countOf(c, STONE_BRICKS));
        assertEquals(97, c.size());
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(2, 7, 3)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(4, 6, 3)));
        assertEquals(BlockSpec.of(GLASS), c.get(new LocalPos(3, 6, 0)), "the end of the clerestory is glazed");
        assertNull(c.get(new LocalPos(3, 6, 3)), "the clerestory is open inside");
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(3, 5, 6)));
    }

    @Test
    void aMonitorRefusesAClerestoryItCannotCentre() {
        CompileResult parity = compileRoof(StyleSpec.EMPTY, 7, 7, params(P_KIND, MONITOR, P_MONITOR_WIDTH, 2, P_OVERHANG, 0));
        assertEquals(MONITOR_WIDTH_ISSUE, onlyIssue(parity).id());
        // T=3 and a 3-wide clerestory: nothing is left for the slopes
        CompileResult equal = compileRoof(StyleSpec.EMPTY, 3, 3, params(P_KIND, MONITOR, P_MONITOR_WIDTH, 3, P_OVERHANG, 0));
        assertEquals(MONITOR_WIDTH_ISSUE, onlyIssue(equal).id());
        // T=3 and a 5-wide clerestory: the difference (-2) is even, only the width check refuses it
        CompileResult wider = compileRoof(StyleSpec.EMPTY, 3, 3, params(P_KIND, MONITOR, P_MONITOR_WIDTH, 5, P_OVERHANG, 0));
        assertEquals(MONITOR_WIDTH_ISSUE, onlyIssue(wider).id());
        assertNull(wider.manifest());
    }

    @Test
    void rolesAreLookedUpOnlyForTheBlocksAKindPlaces() {
        StyleSpec style = new StyleSpec(Map.of(ROLE_GLASS, BEDROCK, ROLE_WALL, BEDROCK), Set.of());
        // no glass in a gable, a hip, a flat roof or a shed; no wall without gable_fill
        roof(style, 7, 7, params(P_OVERHANG, 0, P_GABLE_FILL, false));
        roof(style, 7, 7, params(P_KIND, HIP, P_OVERHANG, 0));
        roof(style, 7, 7, params(P_KIND, FLAT));
        roof(style, 7, 7, params(P_KIND, SHED, P_OVERHANG, 0, P_GABLE_FILL, false));
        assertEquals(List.of(E_BLOCK_FORBIDDEN), codes(compileRoof(style, 7, 7, params(P_OVERHANG, 0))), "gable_fill uses the wall role");
        assertEquals(List.of(E_BLOCK_FORBIDDEN), codes(compileRoof(style, 7, 7, params(P_KIND, SAWTOOTH, P_OVERHANG, 0))));
        assertEquals(List.of(E_BLOCK_FORBIDDEN),
                codes(compileRoof(style, 7, 7, params(P_KIND, MONITOR, P_OVERHANG, 0, P_GABLE_FILL, false))));
        // a flat roof places only slabs: the full block and stairs of the roof role are never looked up
        StyleSpec slabOnly = new StyleSpec(Map.of(ROLE_ROOF, BEDROCK, ROLE_ROOF_SLAB, OAK_SLAB), Set.of());
        assertEquals(49, countOf(roof(slabOnly, 7, 7, params(P_KIND, FLAT, P_OVERHANG, 0)), OAK_SLAB));
    }

    @Test
    void aRefusedMaterialLeavesNoPartOfTheRoofBehind() {
        // stairs resolve through the explicit role, the monitor's cap slab does not (terracotta has no slab): the roof
        // must be refused before its first cell, or its stairs would collide with the pillar standing on the eaves
        StyleSpec style = new StyleSpec(Map.of(ROLE_ROOF, RED_TERRACOTTA, ROLE_ROOF_STAIRS, RED_NETHER_BRICK_STAIRS), Set.of());
        PlanNode pillar = node(PILLAR_ID, PILLAR, null, 0, 4, 3, params(P_HEIGHT, 1));
        CompileResult r = compile(style, List.of(
                node(STRUCTURE_ID, STRUCTURE, null, 0, 0, 0, params(P_WIDTH, 7, P_DEPTH, 7)),
                node(ROOF_ID, ROOF, STRUCTURE_ID, 0, 0, 0, params(P_KIND, MONITOR, P_OVERHANG, 0)),
                pillar));
        assertEquals(MATERIAL_ISSUE, onlyIssue(r).id());
    }
}
