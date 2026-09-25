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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FreestandingPartsTest {
    private static final String PILLAR = "micra:pillar";
    private static final String BEAM = "micra:beam";
    private static final String CHIMNEY = "micra:chimney";
    private static final String ROAD = "micra:road";
    private static final String DOCK_PAD = "micra:dock_pad";

    private static final String QUARTZ = "minecraft:quartz_block";
    private static final String SMOOTH_STONE = "minecraft:smooth_stone";
    private static final String OAK_LOG = "minecraft:oak_log";
    private static final String STONE = "minecraft:stone";
    private static final String BRICKS = "minecraft:bricks";
    private static final String BRICK_SLAB = "minecraft:brick_slab";
    private static final String GRAVEL = "minecraft:gravel";
    private static final String YELLOW_CONCRETE = "minecraft:yellow_concrete";
    private static final String BARREL = "minecraft:barrel";
    private static final String BEDROCK = "minecraft:bedrock";

    private static final String AXIS_X = "x";
    private static final String AXIS_Y = "y";
    private static final String AXIS_Z = "z";
    private static final String FACING_UP = "up";

    // Parameter names of the parts under test.
    private static final String P_HEIGHT = "height";
    private static final String P_BASE = "base";
    private static final String P_CAPITAL = "capital";
    private static final String P_AXIS = "axis";
    private static final String P_LENGTH = "length";
    private static final String P_MATERIAL = "material";
    private static final String P_SIZE = "size";
    private static final String P_CAP = "cap";
    private static final String P_DIR = "dir";
    private static final String P_WIDTH = "width";
    private static final String P_DEPTH = "depth";
    private static final String P_CLEARANCE = "clearance";
    private static final String P_CARGO_U = "cargo_u";
    private static final String P_CARGO_W = "cargo_w";
    private static final String P_MARKER = "marker";

    /** The part in a test that builds one part alone. */
    private static final String PART_ID = "p";
    private static final String PAD_ID = "d";
    /** Stands in the pad's air space: it sorts after the pad's id, so an overlap names the pad first. */
    private static final String OBSTACLE_ID = "l";
    private static final String REASON_KEY = "reason";
    private static final String REASON_CLEARANCE = "clearance";
    private static final String COUNT_KEY = "count";
    private static final String OVERLAP_ID_PREFIX = "E-OVERLAP:";
    private static final String BUDGET_ISSUE_ID = "E-OUT-OF-BOUNDS:#cells";

    // Palette roles the style overrides.
    private static final String ROLE_PILLAR = "pillar";
    private static final String ROLE_TRIM = "trim";
    private static final String ROLE_MARKER = "marker";

    private static final StyleSpec STYLE = new StyleSpec(Map.of(ROLE_PILLAR, QUARTZ, ROLE_TRIM, SMOOTH_STONE), Set.of());

    /** The smallest width and depth a dock pad may have. */
    private static final int SMALLEST_PAD = 5;
    /** Cell budgets small enough for the arithmetic in the two work-budget tests (the attempt budget is twice this). */
    private static final int PADS_BUDGET_CELLS = 10_000;
    private static final int CHIMNEYS_BUDGET_CELLS = 16;
    private static final Duration BUDGET_TIMEOUT = Duration.ofSeconds(10);

    private static PlanNode at(String id, String type, int u, int v, int w, Map<String, ParamValue> params) {
        return node(id, type, null, u, v, w, params);
    }

    private static PlanNode single(String type, int u, int v, int w, Map<String, ParamValue> params) {
        return at(PART_ID, type, u, v, w, params);
    }

    private static Map<LocalPos, BlockSpec> build(PlanNode... nodes) {
        CompileResult r = compile(STYLE, List.of(nodes));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        return cells(r.manifest());
    }

    private static List<String> ids(CompileResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    private static BlockSpec bottomSlab(String id) {
        return BlockSpec.of(id, BlockForms.PROP_TYPE, BlockForms.HALF_BOTTOM);
    }

    private static BlockSpec log(String axis) {
        return BlockSpec.of(OAK_LOG, BlockForms.PROP_AXIS, axis);
    }

    private static BlockSpec cargoBarrel() {
        return BlockSpec.of(BARREL, BlockForms.PROP_FACING, FACING_UP);
    }

    /** The cells of a flat rectangle at v = 0, both ends of each range included. */
    private static Set<LocalPos> flatBox(int u0, int u1, int w0, int w1) {
        Set<LocalPos> out = new HashSet<>();
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                out.add(new LocalPos(u, 0, w));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ pillar

    @Test
    void pillarHasABaseAndACapitalOfTrim() {
        Map<LocalPos, BlockSpec> c = build(single(PILLAR, 0, 0, 0, Map.of()));
        assertEquals(4, c.size());
        assertEquals(BlockSpec.of(SMOOTH_STONE), c.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of(QUARTZ), c.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of(QUARTZ), c.get(new LocalPos(0, 2, 0)));
        assertEquals(BlockSpec.of(SMOOTH_STONE), c.get(new LocalPos(0, 3, 0)));
    }

    @Test
    void pillarWithoutBaseOrCapitalAndTheShortCases() {
        Map<LocalPos, BlockSpec> plain = build(single(PILLAR, 0, 0, 0, params(P_BASE, false, P_CAPITAL, false)));
        assertEquals(4, countOf(plain, QUARTZ));
        Map<LocalPos, BlockSpec> one = build(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 1)));
        assertEquals(BlockSpec.of(QUARTZ), one.get(new LocalPos(0, 0, 0)), "a one-block pillar is only shaft");
        Map<LocalPos, BlockSpec> two = build(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 2)));
        assertEquals(2, countOf(two, SMOOTH_STONE));
    }

    @Test
    void pillarBaseAndCapitalAreSeparateSwitches() {
        // height 3 has one row of shaft between the two ends
        Map<LocalPos, BlockSpec> capitalOnly = build(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 3, P_BASE, false)));
        assertEquals(BlockSpec.of(QUARTZ), capitalOnly.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of(QUARTZ), capitalOnly.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of(SMOOTH_STONE), capitalOnly.get(new LocalPos(0, 2, 0)));
        Map<LocalPos, BlockSpec> baseOnly = build(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 3, P_CAPITAL, false)));
        assertEquals(BlockSpec.of(SMOOTH_STONE), baseOnly.get(new LocalPos(0, 0, 0)));
        assertEquals(BlockSpec.of(QUARTZ), baseOnly.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of(QUARTZ), baseOnly.get(new LocalPos(0, 2, 0)));
    }

    // ------------------------------------------------------------------ beam

    @Test
    void beamGetsItsAxisFromTheDirection() {
        Map<LocalPos, BlockSpec> c = build(single(BEAM, 2, 3, 4, params(P_AXIS, "w", P_LENGTH, 3)));
        assertEquals(3, c.size());
        assertEquals(log(AXIS_Z), c.get(new LocalPos(2, 3, 4)));
        assertEquals(log(AXIS_Z), c.get(new LocalPos(2, 3, 6)));
        Map<LocalPos, BlockSpec> up = build(single(BEAM, 0, 0, 0, params(P_AXIS, "v", P_LENGTH, 2)));
        assertEquals(AXIS_Y, up.get(new LocalPos(0, 1, 0)).get(BlockForms.PROP_AXIS));
        Map<LocalPos, BlockSpec> plainBeam = build(single(BEAM, 0, 0, 0, params(P_LENGTH, 2, P_MATERIAL, STONE)));
        assertEquals(BlockSpec.of(STONE), plainBeam.get(new LocalPos(1, 0, 0)));
    }

    // ------------------------------------------------------------------ chimney

    @Test
    void chimneyShaftAndCap() {
        Map<LocalPos, BlockSpec> c = build(single(CHIMNEY, 0, 0, 0, params(P_HEIGHT, 3)));
        assertEquals(3, countOf(c, BRICKS));
        assertEquals(9, countOf(c, BRICK_SLAB));
        assertEquals(bottomSlab(BRICK_SLAB), c.get(new LocalPos(-1, 3, -1)));
        assertEquals(bottomSlab(BRICK_SLAB), c.get(new LocalPos(1, 3, 1)));
        assertEquals(12, c.size());
        Map<LocalPos, BlockSpec> ring = build(single(CHIMNEY, 0, 0, 0, params(P_HEIGHT, 2, P_SIZE, 3, P_CAP, false)));
        assertEquals(16, ring.size(), "a size-3 chimney is a hollow ring");
        assertNull(ring.get(new LocalPos(1, 0, 1)));
    }

    @Test
    void aSizeTwoChimneyIsSolidAndItsCapOverhangsByOneBlock() {
        Map<LocalPos, BlockSpec> c = build(single(CHIMNEY, 0, 0, 0, params(P_HEIGHT, 2, P_SIZE, 2)));
        // shaft: 2 x 2 x 2 rows = 8 (only size 3 has a hollow); cap: (2 + 2) x (2 + 2) = 16 at v = height
        assertEquals(8, countOf(c, BRICKS));
        assertEquals(16, countOf(c, BRICK_SLAB));
        assertEquals(24, c.size());
        assertEquals(BlockSpec.of(BRICKS), c.get(new LocalPos(1, 1, 1)), "no hollow in a size-2 chimney");
        assertEquals(bottomSlab(BRICK_SLAB), c.get(new LocalPos(-1, 2, -1)));
        assertEquals(bottomSlab(BRICK_SLAB), c.get(new LocalPos(2, 2, 2)));
        assertNull(c.get(new LocalPos(3, 2, 3)));
        assertNull(c.get(new LocalPos(-2, 2, -2)));
    }

    // ------------------------------------------------------------------ road

    @Test
    void roadFollowsTheDirectionAndGrowsToTheRight() {
        Map<LocalPos, BlockSpec> c = build(single(ROAD, 0, 0, 0, params(P_LENGTH, 3, P_DIR, "east")));
        assertEquals(6, c.size());
        assertEquals(BlockSpec.of(GRAVEL), c.get(new LocalPos(2, 0, 0)));
        assertEquals(BlockSpec.of(GRAVEL), c.get(new LocalPos(2, 0, -1)), "right of east is south (-w)");
    }

    @Test
    void aRoadGrowsToTheRightHandSideOfWhicheverWayItPoints() {
        // 3 long, 2 wide: the length runs along the heading, the width along the right-hand side seen from above
        assertEquals(flatBox(0, 1, 0, 2), roadCells("north"), "heading +w, right is +u");
        assertEquals(flatBox(0, 2, -1, 0), roadCells("east"), "heading +u, right is -w");
        assertEquals(flatBox(-1, 0, -2, 0), roadCells("south"), "heading -w, right is -u");
        assertEquals(flatBox(-2, 0, 0, 1), roadCells("west"), "heading -u, right is +w");
    }

    private static Set<LocalPos> roadCells(String dir) {
        return new HashSet<>(build(single(ROAD, 0, 0, 0, params(P_LENGTH, 3, P_WIDTH, 2, P_DIR, dir))).keySet());
    }

    // ------------------------------------------------------------------ dock pad

    @Test
    void dockPadHasAMarkerRingAndACargoBarrel() {
        Map<LocalPos, BlockSpec> c = build(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4)));
        assertEquals(26, c.size());
        assertEquals(16, countOf(c, YELLOW_CONCRETE));
        assertEquals(9, countOf(c, SMOOTH_STONE));
        assertEquals(cargoBarrel(), c.get(new LocalPos(1, 1, 1)));
        Map<LocalPos, BlockSpec> plainPad = build(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_MARKER, false)));
        assertEquals(25, countOf(plainPad, SMOOTH_STONE));
    }

    @Test
    void theCargoBarrelStandsWhereTheParametersPutIt() {
        // the two axes are told apart by a cell that is not on the diagonal
        Map<LocalPos, BlockSpec> alongU = build(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CARGO_U, 3, P_CARGO_W, 1)));
        assertEquals(cargoBarrel(), alongU.get(new LocalPos(3, 1, 1)));
        assertEquals(1, countOf(alongU, BARREL));
        Map<LocalPos, BlockSpec> alongW = build(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CARGO_U, 1, P_CARGO_W, 3)));
        assertEquals(cargoBarrel(), alongW.get(new LocalPos(1, 1, 3)));
        assertEquals(1, countOf(alongW, BARREL));
    }

    @Test
    void dockPadKeepsItsAirSpaceClear() {
        CompileResult r = compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4)),
                at(OBSTACLE_ID, PILLAR, 3, 2, 3, params(P_HEIGHT, 1))));
        assertNull(r.manifest());
        assertEquals(List.of("E-OVERLAP"), codes(r));
        assertEquals(List.of(PAD_ID, OBSTACLE_ID), r.issues().get(0).subjects());
        assertEquals(REASON_CLEARANCE, r.issues().get(0).data().get(REASON_KEY));
        // above the clearance it is fine
        assertTrue(compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4)),
                at(OBSTACLE_ID, PILLAR, 3, 5, 3, params(P_HEIGHT, 1)))).issues().isEmpty());
    }

    @Test
    void theAirSpaceIsExactlyTheClearanceRowsAboveThePadsFootprint() {
        // A 5 x 5 pad with clearance 4 owns u, w = 0..4 and v = 1..4. Each obstacle runs through it in one direction and
        // reaches past it at the end(s), so the number of cells named in the issue is the length of the run inside.
        CompileResult r = compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4)),
                // v = 1..6 at (3, 3): rows 1..4 are inside
                at(OBSTACLE_ID, PILLAR, 3, 1, 3, params(P_HEIGHT, 6, P_BASE, false, P_CAPITAL, false)),
                // u = -2..7 at v = 2, w = 1: u = 0..4 is inside
                at("bu", BEAM, -2, 2, 1, params(P_AXIS, "u", P_LENGTH, 10)),
                // w = -2..7 at u = 1, v = 3: w = 0..4 is inside
                at("bw", BEAM, 1, 3, -2, params(P_AXIS, "w", P_LENGTH, 10))));
        assertNull(r.manifest());
        assertEquals(List.of(OVERLAP_ID_PREFIX + "bu," + PAD_ID, OVERLAP_ID_PREFIX + "bw," + PAD_ID,
                OVERLAP_ID_PREFIX + PAD_ID + "," + OBSTACLE_ID), ids(r));
        assertEquals(List.of("5", "5", "4"), r.issues().stream().map(x -> x.data().get(COUNT_KEY)).toList());
        assertTrue(r.issues().stream().allMatch(x -> REASON_CLEARANCE.equals(x.data().get(REASON_KEY))), r.issues().toString());
    }

    @Test
    void theAirSpaceTurnsWithThePad() {
        // A 5 x 7 pad turned once at (10, 0, 20): local (u, w) becomes (10 + w, 20 - u), so it covers x = 10..16, z = 16..20.
        Map<String, ParamValue> padParams = params(P_WIDTH, SMALLEST_PAD, P_DEPTH, 7, P_CLEARANCE, 4);
        PlanNode turnedPad = ruled(PAD_ID, DOCK_PAD, null, new Rot(1, false), 10, 0, 20, padParams);
        // (14, 2, 24) would be inside the air space of the same pad left unturned (u = 4, w = 4), but is outside the turned one
        build(turnedPad, at("m", PILLAR, 14, 2, 24, params(P_HEIGHT, 1)));
        // (16, 2, 16) is the far corner of the turned pad (u = 4, w = 6)
        CompileResult r = compile(STYLE, List.of(turnedPad, at(OBSTACLE_ID, PILLAR, 16, 2, 16, params(P_HEIGHT, 1))));
        assertEquals(List.of(OVERLAP_ID_PREFIX + PAD_ID + "," + OBSTACLE_ID), ids(r));
        assertEquals("1", r.issues().get(0).data().get(COUNT_KEY));
    }

    @Test
    void dockPadCargoMustLieOnThePad() {
        CompileResult r = compile(STYLE, List.of(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CARGO_U, 9))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(r));
    }

    @Test
    void theCargoBarrelMustLieOnThePadOnBothAxes() {
        // a 5 x 7 pad: u runs 0..4 and w runs 0..6, so the two limits differ and a swap of the axes shows
        assertEquals(List.of("E-PARAM-RANGE:d#cargo_u"), padIssues(params(P_WIDTH, 5, P_DEPTH, 7, P_CARGO_U, 5)));
        assertEquals(List.of("E-PARAM-RANGE:d#cargo_w"), padIssues(params(P_WIDTH, 5, P_DEPTH, 7, P_CARGO_W, 7)));
        // cargo_w = 5 is past a 5-deep pad but on this 7-deep one; the last cell of each axis is still on the pad
        assertEquals(List.of(), padIssues(params(P_WIDTH, 5, P_DEPTH, 7, P_CARGO_W, 5)));
        Map<LocalPos, BlockSpec> corner = build(at(PAD_ID, DOCK_PAD, 0, 0, 0,
                params(P_WIDTH, 5, P_DEPTH, 7, P_CARGO_U, 4, P_CARGO_W, 6)));
        assertEquals(cargoBarrel(), corner.get(new LocalPos(4, 1, 6)));
    }

    private static List<String> padIssues(Map<String, ParamValue> padParams) {
        return ids(compile(STYLE, List.of(at(PAD_ID, DOCK_PAD, 0, 0, 0, padParams))));
    }

    @Test
    void aPadOnAWallFaceHasNoPositionAndIsReportedOnce() {
        // A wall face gives a part no position of its own: generating the pad fails, and looking at its air space must not fail again.
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(onWall(PAD_ID, DOCK_PAD, "s", "wall-n", Side.OUTER, 1, 1, Map.of()));
        CompileResult r = compile(STYLE, nodes);
        assertNull(r.manifest());
        assertEquals(List.of("E-ANCHOR:" + PAD_ID + "#anchor"), ids(r));
    }

    // ------------------------------------------------------------------ block policy

    @Test
    void aRoleOrMaterialThatIsNotPlacedIsNotHeldAgainstThePart() {
        StyleSpec bannedRoles = new StyleSpec(Map.of(ROLE_TRIM, BEDROCK, ROLE_MARKER, BEDROCK), Set.of());
        // controls: the trim of a pillar and the marker of a pad are placed by default, and refused
        assertEquals(List.of(refusedBedrock(PART_ID)), ids(compile(bannedRoles, List.of(single(PILLAR, 0, 0, 0, Map.of())))));
        assertEquals(List.of(refusedBedrock(PAD_ID)), ids(compile(bannedRoles, List.of(at(PAD_ID, DOCK_PAD, 0, 0, 0, Map.of())))));
        // switched off, or too short to have ends, the trim is not placed
        assertEquals(List.of(), ids(compile(bannedRoles, List.of(single(PILLAR, 0, 0, 0, params(P_BASE, false, P_CAPITAL, false))))));
        assertEquals(List.of(), ids(compile(bannedRoles, List.of(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 1))))));
        // a two-row pillar with both ends is all trim: its shaft material is not placed
        assertEquals(List.of(), ids(compile(STYLE, List.of(single(PILLAR, 0, 0, 0, params(P_HEIGHT, 2, P_MATERIAL, BEDROCK))))));
        // a pad without its marker does not place it
        assertEquals(List.of(), ids(compile(bannedRoles, List.of(at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_MARKER, false))))));
    }

    private static String refusedBedrock(String partId) {
        return "E-BLOCK-FORBIDDEN:" + partId + "#" + BEDROCK;
    }

    // ------------------------------------------------------------------ rotation

    @Test
    void aRotatedFreestandingPartTurnsItsCellsAndItsBlockStates() {
        PlanNode turned = ruled(PART_ID, BEAM, null, new Rot(1, false), 0, 0, 0, params(P_AXIS, "u", P_LENGTH, 3));
        Map<LocalPos, BlockSpec> c = build(turned);
        // one clockwise turn: +u becomes -w, and the log's axis x becomes z
        assertEquals(log(AXIS_Z), c.get(new LocalPos(0, 0, -2)));
        assertEquals(3, c.size());
        // the axis x is what an unturned beam along u has
        assertEquals(log(AXIS_X), build(single(BEAM, 0, 0, 0, params(P_AXIS, "u", P_LENGTH, 3))).get(new LocalPos(2, 0, 0)));
    }

    // ------------------------------------------------------------------ work budget

    @Test
    void aPadsAirSpaceCountsAsWorkAgainstTheBudget() {
        // 20 separate 5 x 5 pads with 64 rows of air: 20 x 26 = 520 placements, but 20 x 5 x 5 x 64 = 32,000 cells looked at
        // in the air. PlanCompiler(10_000) allows 2 x 10,000 attempts, so the looking alone must be counted to stop it.
        int padsPerRow = 10;
        int spacing = 6;
        List<PlanNode> pads = new ArrayList<>();
        for (int k = 0; k < 2 * padsPerRow; k++) {
            pads.add(at("pad" + k, DOCK_PAD, spacing * (k % padsPerRow), 0, spacing * (k / padsPerRow),
                    params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 64)));
        }
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), STYLE, pads);
        assertTrue(compile(plan).issues().isEmpty(), "with the default budget the same plan compiles");
        CompileResult small = assertTimeoutPreemptively(BUDGET_TIMEOUT, () -> compile(plan, new PlanCompiler(PADS_BUDGET_CELLS)));
        assertNull(small.manifest());
        assertEquals(List.of(BUDGET_ISSUE_ID), ids(small));
    }

    @Test
    void theHollowOfAChimneyCountsAsWork() {
        // Two identical size-3 chimneys of 2 rows: 8 cells placed per row, so 16 each, and 1 centre cell skipped per row.
        // PlanCompiler(16) allows 2 x 16 = 32 attempts: the placements alone use exactly 32, the 2 skipped cells of
        // each chimney tip it over.
        List<PlanNode> two = List.of(
                at("c1", CHIMNEY, 0, 0, 0, params(P_HEIGHT, 2, P_SIZE, 3, P_CAP, false)),
                at("c2", CHIMNEY, 0, 0, 0, params(P_HEIGHT, 2, P_SIZE, 3, P_CAP, false)));
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), STYLE, two),
                new PlanCompiler(CHIMNEYS_BUDGET_CELLS));
        assertNull(r.manifest());
        assertEquals(List.of(BUDGET_ISSUE_ID), ids(r));
    }
}
