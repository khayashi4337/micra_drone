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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.compile.CompileFixtures;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
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
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.Params;
import io.github.khayashi4337.micradrone.build.parts.PartParams;
import io.github.khayashi4337.micradrone.build.parts.Roles;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.Origins;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String P_HEIGHT = PartParams.HEIGHT;
    private static final String P_BASE = PartParams.BASE;
    private static final String P_CAPITAL = PartParams.CAPITAL;
    private static final String P_AXIS = PartParams.AXIS;
    private static final String P_LENGTH = PartParams.LENGTH;
    private static final String P_MATERIAL = PartParams.MATERIAL;
    private static final String P_SIZE = PartParams.SIZE;
    private static final String P_CAP = PartParams.CAP;
    private static final String P_DIR = PartParams.DIR;
    private static final String P_WIDTH = PartParams.WIDTH;
    private static final String P_DEPTH = PartParams.DEPTH;
    private static final String P_CLEARANCE = PartParams.CLEARANCE;
    private static final String P_CARGO_U = PartParams.CARGO_U;
    private static final String P_CARGO_W = PartParams.CARGO_W;
    private static final String P_MARKER = PartParams.MARKER;

    /** The part in a test that builds one part alone. */
    private static final String PART_ID = "p";
    private static final String PAD_ID = "d";
    /** A second part beside PART_ID, that sorts after it. */
    private static final String SECOND_ID = "q";
    /** A material that names no palette role, and a block that has no slab form. */
    private static final String BAD_ROLE = "no_such_role";
    private static final String RED_TERRACOTTA = "minecraft:red_terracotta";
    /** Stands in the pad's air space: it sorts after the pad's id, so an overlap names the pad first. */
    private static final String OBSTACLE_ID = "l";
    private static final String REASON_KEY = "reason";
    private static final String REASON_CLEARANCE = "clearance";
    private static final String COUNT_KEY = "count";
    private static final String OVERLAP_ID_PREFIX = "E-OVERLAP:";
    private static final String BUDGET_ISSUE_ID = "E-OUT-OF-BOUNDS:#cells";

    // Palette roles the style overrides.
    private static final String ROLE_PILLAR = Roles.PILLAR;
    private static final String ROLE_TRIM = Roles.TRIM;
    private static final String ROLE_MARKER = Roles.MARKER;

    private static final StyleSpec STYLE = new StyleSpec(Map.of(ROLE_PILLAR, QUARTZ, ROLE_TRIM, SMOOTH_STONE), Set.of());

    /** The smallest width and depth a dock pad may have. */
    private static final int SMALLEST_PAD = 5;
    /** Cell budgets small enough for the arithmetic in the two work-budget tests (the attempt budget is twice this). */
    private static final int PADS_BUDGET_CELLS = 10_000;
    private static final int CHIMNEYS_BUDGET_CELLS = 16;
    /** The largest pad the spec allows, and the most clearance. */
    private static final int LARGEST_PAD = 64;
    private static final int LARGEST_CLEARANCE = 64;
    /** Room for the largest pad's cells in a canvas that is used directly. */
    private static final int PAD_CANVAS_CELLS = 10_000;
    private static final int COLUMN_CELLS = 5;
    /** Unrelated cells that make the canvas larger than a small pad's air, so that the air is what gets walked. */
    private static final int FILLER_CELLS = 1_000;
    private static final String FILLER_ID = "x";
    private static final int FILLER_FIRST_U = 100;
    /** Beams that run through and beside a pad's air space: 17 rows of 17 cells at v = 2, from u = 4, w = 14. */
    private static final int BEAM_FIELD_ROWS = 17;
    private static final int BEAM_FIELD_U = 4;
    private static final int BEAM_FIELD_V = 2;
    private static final int BEAM_FIELD_W = 14;
    /** A road of 64 x 8 = 512 cells at v = 0, far from the pad and the beams. */
    private static final int ROAD_U = 80;
    private static final int ROAD_LENGTH = 64;
    private static final int ROAD_WIDTH = 8;
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

    /**
     * The 55 blocks of Minecraft 1.21.1 whose block state has an "axis" of x, y and z. (nether_portal has an axis too, but
     * only x and z, and it can never be placed.) Read from the blockstate files (assets/minecraft/blockstates) of the client
     * resources jar of this build (neoforge-21.1.238) and cross-checked with the block classes that declare the property:
     * RotatedPillarBlock with HayBlock, InfestedRotatedPillarBlock and MuddyMangroveRootsBlock, and ChainBlock.
     */
    private static final String VANILLA_AXIS_BLOCKS = """
            acacia_log acacia_wood bamboo_block basalt birch_log birch_wood bone_block chain cherry_log cherry_wood
            crimson_hyphae crimson_stem dark_oak_log dark_oak_wood deepslate hay_block infested_deepslate jungle_log
            jungle_wood mangrove_log mangrove_wood muddy_mangrove_roots oak_log oak_wood ochre_froglight
            pearlescent_froglight polished_basalt purpur_pillar quartz_pillar spruce_log spruce_wood stripped_acacia_log
            stripped_acacia_wood stripped_bamboo_block stripped_birch_log stripped_birch_wood stripped_cherry_log
            stripped_cherry_wood stripped_crimson_hyphae stripped_crimson_stem stripped_dark_oak_log
            stripped_dark_oak_wood stripped_jungle_log stripped_jungle_wood stripped_mangrove_log stripped_mangrove_wood
            stripped_oak_log stripped_oak_wood stripped_spruce_log stripped_spruce_wood stripped_warped_hyphae
            stripped_warped_stem verdant_froglight warped_hyphae warped_stem
            """;
    /**
     * Blocks from the same game data that have no axis, including the six whose id ends in "_stem" but which are not
     * trunks (a melon, a pumpkin, a mushroom and a dripleaf stem), and near neighbours of axis blocks.
     */
    private static final String VANILLA_NON_AXIS_BLOCKS = """
            attached_melon_stem attached_pumpkin_stem big_dripleaf_stem melon_stem mushroom_stem pumpkin_stem
            smooth_basalt quartz_block chiseled_quartz_block purpur_block deepslate_bricks cobbled_deepslate mangrove_roots
            oak_planks stone
            """;
    private static final String NAMESPACE = "minecraft:";
    private static final int AXIS_BLOCK_COUNT = 55;
    private static final int BEAM_TEST_LENGTH = 2;

    private static List<String> blockIds(String names) {
        return Arrays.stream(names.strip().split("\\s+")).map(name -> NAMESPACE + name).toList();
    }

    /** A beam of {@code blockId} alone, compiled with a policy that accepts it: the built-in list does not name most of these. */
    private static Map<LocalPos, BlockSpec> beamOf(String blockId, String axis) {
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), STYLE, List.of(
                single(BEAM, 0, 0, 0, params(P_AXIS, axis, P_LENGTH, BEAM_TEST_LENGTH, P_MATERIAL, blockId))));
        ExpandResult expanded = new PlanExpander(CompileFixtures.REGISTRY, SlotResolver.NONE).expand(plan, TemplateBundle.EMPTY, Router.NONE);
        assertTrue(expanded.issues().isEmpty(), expanded.issues().toString());
        CompileResult r = new PlanCompiler().compile(expanded.plan(), CompileFixtures.REGISTRY,
                new PlaceableBlockPolicy(Set.of(blockId)), CompileFixtures.survey());
        assertTrue(r.issues().isEmpty(), blockId + " " + axis + ": " + r.issues());
        return cells(r.manifest());
    }

    @Test
    void everyVanillaBlockWithAnAxisGetsItInAllThreeBeamDirections() {
        List<String> ids = blockIds(VANILLA_AXIS_BLOCKS);
        assertEquals(AXIS_BLOCK_COUNT, ids.size());
        assertEquals(AXIS_BLOCK_COUNT, new HashSet<>(ids).size(), "an id is listed twice");
        for (String id : ids) {
            // beam axis u runs along x, v along y, w along z; the two cells are one step apart on that axis
            Map<LocalPos, BlockSpec> alongU = beamOf(id, "u");
            assertEquals(Map.of(new LocalPos(0, 0, 0), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_X),
                    new LocalPos(1, 0, 0), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_X)), alongU, id + " along u");
            Map<LocalPos, BlockSpec> alongV = beamOf(id, "v");
            assertEquals(Map.of(new LocalPos(0, 0, 0), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_Y),
                    new LocalPos(0, 1, 0), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_Y)), alongV, id + " along v");
            Map<LocalPos, BlockSpec> alongW = beamOf(id, "w");
            assertEquals(Map.of(new LocalPos(0, 0, 0), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_Z),
                    new LocalPos(0, 0, 1), BlockSpec.of(id, BlockForms.PROP_AXIS, AXIS_Z)), alongW, id + " along w");
        }
    }

    @Test
    void aBlockWithoutAnAxisGetsNoAxisFromABeam() {
        for (String id : blockIds(VANILLA_NON_AXIS_BLOCKS)) {
            for (String axis : List.of("u", "v", "w")) {
                for (BlockSpec block : beamOf(id, axis).values()) {
                    assertEquals(BlockSpec.of(id), block, id + " along " + axis);
                }
            }
        }
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

    // ------------------------------------------------------------------ build stage

    @Test
    void eachPartRegistersItsPlacementsUnderItsOwnStage() {
        Map<String, BuildPhase> stages = Map.of(
                PILLAR, BuildPhase.STRUCTURE, BEAM, BuildPhase.STRUCTURE, CHIMNEY, BuildPhase.STRUCTURE,
                ROAD, BuildPhase.LOGISTICS, DOCK_PAD, BuildPhase.LOGISTICS);
        for (Map.Entry<String, BuildPhase> e : stages.entrySet()) {
            CompileResult r = compile(STYLE, List.of(at(PART_ID, e.getKey(), 0, 0, 0, Map.of())));
            assertTrue(r.issues().isEmpty(), e.getKey() + ": " + r.issues());
            Set<BuildPhase> seen = new HashSet<>();
            for (Placement p : r.manifest().placements()) {
                seen.add(p.phase());
            }
            assertEquals(Set.of(e.getValue()), seen, e.getKey() + " places only under " + e.getValue());
        }
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
    void manyPadsWithTallAirAreNotStoppedByTheAirTheyOwn() {
        // 20 separate 5 x 5 pads with 64 rows of air: 20 x 26 = 520 placements and 20 x 5 x 5 x 64 = 32,000 cells of air.
        // PlanCompiler(10_000) allows 2 x 10,000 attempts. The air is not counted (it is empty): each pad's check looks
        // at the 520 occupied cells of the canvas (fewer than its 1,600 cells of air), 20 x 520 = 10,400 attempts, and
        // with the 520 placements 10,920 in all. Counted per cell of air the plan would need 32,520 and be stopped.
        int padsPerRow = 10;
        int spacing = 6;
        List<PlanNode> pads = new ArrayList<>();
        for (int k = 0; k < 2 * padsPerRow; k++) {
            pads.add(at("pad" + k, DOCK_PAD, spacing * (k % padsPerRow), 0, spacing * (k / padsPerRow),
                    params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 64)));
        }
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), STYLE, pads);
        CompileResult small = assertTimeoutPreemptively(BUDGET_TIMEOUT, () -> compile(plan, new PlanCompiler(PADS_BUDGET_CELLS)));
        assertTrue(small.issues().isEmpty(), small.issues().toString());
        assertNotNull(small.manifest());
    }

    @Test
    void twoPadsOfTheLargestSizeFitTheDefaultWorkBudget() {
        // 64 x 64 with 64 rows of clearance is the most air the spec allows: 262,144 cells each. Looked at cell by cell the
        // two need 524,288 attempts against the 400,000 the default budget allows, so a plan the spec permits failed. Only
        // the occupied cells are looked at: each pad's check goes through the 2 x 4,097 cells of the canvas (fewer than
        // its air), 8,194 attempts, and with the 8,194 placements 24,582 in all. The second pad stands just above the
        // first one's air (v = 65), inside the test site.
        Map<String, ParamValue> largest = params(P_WIDTH, LARGEST_PAD, P_DEPTH, LARGEST_PAD, P_CLEARANCE, LARGEST_CLEARANCE);
        List<PlanNode> pads = List.of(at("d1", DOCK_PAD, 0, 0, 0, largest),
                at("d2", DOCK_PAD, 0, LARGEST_CLEARANCE + 1, 0, largest));
        CompileResult r = assertTimeoutPreemptively(BUDGET_TIMEOUT, () -> compile(STYLE, pads));
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        // each pad places its 64 x 64 cells and one barrel
        assertEquals(2 * (LARGEST_PAD * LARGEST_PAD + 1), r.manifest().placements().size());
    }

    /**
     * The attempts that one pad's air check adds, in a fresh context: the pad at the origin is generated first, then
     * {@code filler} unrelated cells are put on the canvas, and only the check itself is counted.
     */
    private static long airCheckAttempts(Map<String, ParamValue> padParams, int filler) {
        PlanNode pad = at(PAD_ID, DOCK_PAD, 0, 0, 0, padParams);
        List<Issue> issues = new ArrayList<>();
        Map<String, LocalPos> origins = Origins.resolve(List.of(pad), SlotResolver.NONE, issues);
        GenContext ctx = new GenContext(CompileFixtures.REGISTRY, new Palette(CompileFixtures.REGISTRY.defaultPalette(), Map.of()),
                new Canvas(PAD_CANVAS_CELLS), issues, Map.of(PAD_ID, pad), origins);
        PartGenerator generator = PartGenerators.find(DOCK_PAD).orElseThrow().generator();
        Params resolved = Params.resolve(CompileFixtures.REGISTRY.get(DOCK_PAD), pad.params());
        generator.generate(ctx, pad, resolved);
        for (int k = 0; k < filler; k++) {
            ctx.canvas().put(new Canvas.Cell(new LocalPos(FILLER_FIRST_U + k, 0, 0), BlockSpec.of(STONE), VerifyMode.EXACT,
                    BuildPhase.STRUCTURE, Map.of(), FILLER_ID, null, null));
        }
        long before = ctx.canvas().attempts();
        generator.afterAll(ctx, pad, resolved);
        assertTrue(issues.isEmpty(), issues.toString());
        assertTrue(ctx.canvas().overlapIssues().isEmpty(), "nothing else stands in the air");
        return ctx.canvas().attempts() - before;
    }

    @Test
    void aPadsAirCheckCostsTheOccupiedCellsItLooksAtAndNeverTheAir() {
        // A 5 x 5 pad has 25 cells and a barrel: 26 occupied cells, and 5 x 5 x 16 = 400 cells of air. The canvas is the
        // smaller, so its 26 cells are walked and counted (25 pad cells, one barrel): 26 attempts.
        assertEquals(26, airCheckAttempts(params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 16), 0));
        // twice as much air costs nothing more
        assertEquals(26, airCheckAttempts(params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 32), 0));
        // With 1,000 other cells on the canvas the pad's own 5 x 5 x 4 = 100 cells of air are the smaller: they are asked
        // for one by one, and of them only the barrel stands there (v = 1), so one attempt; the 99 empty ones are free.
        assertEquals(1, airCheckAttempts(params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4), FILLER_CELLS));
        // exactly as many cells of air as cells on the canvas is the air's turn: 5 x 5 x 4 = 100 = 26 + 74
        assertEquals(1, airCheckAttempts(params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4), 74));
        // one cell fewer on the canvas, and the canvas is the smaller: 26 + 73 = 99 cells walked
        assertEquals(99, airCheckAttempts(params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4), 73));
    }

    @Test
    void aPadRefusedWhileBeingMadeHasNoAirSpaceToCheck() {
        // Both pads are refused before anything is placed: the cargo lies off the pad, or the material is no palette role.
        // A pillar stands where the pad's air would be. It is not reported as overlapping a pad that was never made.
        PlanNode inTheAir = at(OBSTACLE_ID, PILLAR, 2, 2, 2, params(P_HEIGHT, 1));
        assertEquals(List.of("E-PARAM-RANGE:d#cargo_u"), ids(compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CARGO_U, 5)), inTheAir))));
        assertEquals(List.of("E-PARAM-RANGE:d#material"), ids(compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_MATERIAL, BAD_ROLE)), inTheAir))));
    }

    // ------------------------------------------------------------------ a refused part leaves nothing behind

    @Test
    void aPillarWhoseShaftMaterialIsRefusedLeavesNoBaseBehind() {
        // p's base is trim (a role that exists) and its shaft is not, so the refusal comes after the first row could have
        // been placed. Nothing of p may stay on the canvas: q stands on the same spot and must not be reported as
        // overlapping a part that was refused.
        CompileResult r = compile(STYLE, List.of(
                at(PART_ID, PILLAR, 0, 0, 0, params(P_MATERIAL, BAD_ROLE)),
                at(SECOND_ID, PILLAR, 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-PARAM-RANGE:" + PART_ID + "#material"), ids(r));
    }

    @Test
    void aChimneyWhoseCapCannotBeMadeLeavesNoShaftBehind() {
        // red terracotta has no slab in vanilla, so the cap is refused only after the shaft's block was found
        CompileResult r = compile(STYLE, List.of(
                at(PART_ID, CHIMNEY, 0, 0, 0, params(P_MATERIAL, RED_TERRACOTTA)),
                at(SECOND_ID, CHIMNEY, 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-PARAM-RANGE:" + PART_ID + "#material"), ids(r));
    }

    @Test
    void anOverlapOfAPairKeepsTheClearanceReasonWhenItsFirstCellIsNotInTheAir() {
        // The pillar stands on the pad's own first cell (v = 0) and in its air (v = 1..3): four cells, one of them a plain
        // overlap and three in the air. The pair is one issue, and it says "clearance" whichever kind was seen first.
        CompileResult r = compile(STYLE, List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 4)),
                at(OBSTACLE_ID, PILLAR, 0, 0, 0, params(P_HEIGHT, 4, P_BASE, false, P_CAPITAL, false))));
        assertNull(r.manifest());
        assertEquals(List.of(OVERLAP_ID_PREFIX + PAD_ID + "," + OBSTACLE_ID), ids(r));
        assertEquals("4", r.issues().get(0).data().get(COUNT_KEY));
        assertEquals(REASON_CLEARANCE, r.issues().get(0).data().get(REASON_KEY));
    }

    @Test
    void aColumnOfTheAirWithSeveralOtherCellsNamesTheLowestOneFirstWhicheverWayTheAirIsChecked() {
        // A 5 x 5 pad with 16 rows of air (400 cells) has 26 cells on the canvas, so the canvas is walked, in hash order.
        // A pillar of several cells stands in one column of the air; the issue names its lowest cell, as the walk over the
        // air cells does. The canvas walk sees a column's cells in hash order, which is not height order; in this
        // column (u 1, w 3) the lowest cell is not the first one it meets.
        LocalPos lower = new LocalPos(1, 1, 3);
        List<PlanNode> nodes = List.of(
                at(PAD_ID, DOCK_PAD, 0, 0, 0, params(P_WIDTH, SMALLEST_PAD, P_DEPTH, SMALLEST_PAD, P_CLEARANCE, 16)),
                at(OBSTACLE_ID, PILLAR, lower.u(), lower.v(), lower.w(), params(P_HEIGHT, COLUMN_CELLS, P_BASE, false, P_CAPITAL, false)));
        CompileResult r = compile(STYLE, nodes);
        assertEquals(List.of(OVERLAP_ID_PREFIX + PAD_ID + "," + OBSTACLE_ID), ids(r));
        assertEquals(String.valueOf(COLUMN_CELLS), r.issues().get(0).data().get(COUNT_KEY));
        assertEquals(Canvas.posText(lower), r.issues().get(0).data().get(Canvas.DATA_FIRST_POS));
    }

    @Test
    void theSameOverlapsAreReportedWhicheverWayTheAirIsChecked() {
        // A 5 x 7 pad with 16 rows of air (560 cells) at (10, 0, 20), in each of the eight turns and mirrors, and 17 beams
        // (17 cells each, 289 in all) at v = 2 that run through and beside where the air is. With the pad's 36 cells the
        // canvas holds 325, fewer than the air, so the canvas is walked. A road of 512 cells far away makes it 837, and the
        // air is walked instead. The issues must be the same, first cell and all.
        List<PlanNode> field = new ArrayList<>();
        for (int k = 0; k < BEAM_FIELD_ROWS; k++) {
            field.add(at("b" + k, BEAM, BEAM_FIELD_U, BEAM_FIELD_V, BEAM_FIELD_W + k, params(P_AXIS, "u", P_LENGTH, BEAM_FIELD_ROWS)));
        }
        PlanNode road = at("zz", ROAD, ROAD_U, 0, 0, params(P_LENGTH, ROAD_LENGTH, P_WIDTH, ROAD_WIDTH, P_DIR, "west"));
        for (int turns = 0; turns < 4; turns++) {
            for (boolean mirror : new boolean[]{false, true}) {
                Map<String, ParamValue> padParams = params(P_WIDTH, SMALLEST_PAD, P_DEPTH, 7, P_CLEARANCE, 16);
                List<PlanNode> nodes = new ArrayList<>(field);
                nodes.add(ruled(PAD_ID, DOCK_PAD, null, new Rot(turns, mirror), 10, 0, 20, padParams));
                List<Issue> byCanvas = compile(STYLE, nodes).issues();
                nodes.add(road);
                List<Issue> byAir = compile(STYLE, nodes).issues();
                String turn = turns + (mirror ? " mirrored" : "");
                assertTrue(byCanvas.size() > 1, turn + ": some beams stand in the air, " + byCanvas);
                assertEquals(byCanvas, byAir, "turned " + turn);
                assertTrue(byCanvas.stream().allMatch(x -> REASON_CLEARANCE.equals(x.data().get(REASON_KEY))), turn);
            }
        }
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
