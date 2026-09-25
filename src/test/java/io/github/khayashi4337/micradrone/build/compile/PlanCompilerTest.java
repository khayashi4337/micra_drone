package io.github.khayashi4337.micradrone.build.compile;

import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.cells;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.codes;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.compile;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.countOf;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.i;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.node;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.params;
import static io.github.khayashi4337.micradrone.build.compile.CompileFixtures.shell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.Provenance;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildPhase;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.VerifyMode;
import io.github.khayashi4337.micradrone.build.parts.VersionRange;
import io.github.khayashi4337.micradrone.build.plan.ExpandedPlan;
import io.github.khayashi4337.micradrone.build.plan.ModuleTemplate;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanCompilerTest {
    private static final String STRUCTURE = "micra:structure";
    private static final String FLOOR = "micra:floor";
    private static final String WALL = "micra:wall";
    private static final String OAK_PLANKS = "minecraft:oak_planks";
    private static final String STONE_BRICKS = "minecraft:stone_bricks";
    private static final Duration WALK_TIMEOUT = Duration.ofSeconds(10);

    /** 5x5 building, one floor of height 4: floor slab at v=0, walls at v=1..3. */
    private static List<PlanNode> box5(PlanNode... extra) {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(node("f", FLOOR, "s", 0, 0, 0, Map.of()));
        nodes.addAll(List.of(extra));
        return nodes;
    }

    /** A plan that did not pass the patcher or the expander: the compiler must hold up against it. */
    private static CompileResult compileHandBuilt(Site site, List<PlanNode> nodes) {
        SemanticPlan plan = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "hand", 0, null, site, StyleSpec.EMPTY, nodes, List.of(),
                null, Provenance.NONE);
        return new PlanCompiler().compile(new ExpandedPlan(plan, nodes, List.of(), List.of()), CompileFixtures.REGISTRY,
                PlaceableBlockPolicy.builtin(), CompileFixtures.survey());
    }

    private static List<String> ids(CompileResult r) {
        return r.issues().stream().map(Issue::id).toList();
    }

    @Test
    void aFloorAndFourWallsGiveTheDerivedCellCounts() {
        CompileResult r = compile(box5());
        assertTrue(r.issues().isEmpty(), r.issues().toString());
        PlacementManifest m = r.manifest();
        // floor 5x5 = 25; wall ring 16 cells x 3 rows = 48 (corners are shared)
        assertEquals(25 + 48, m.placements().size());
        assertEquals(List.of(new PhaseRange(BuildPhase.STRUCTURE, 0, 25), new PhaseRange(BuildPhase.ENVELOPE, 25, 73)), m.phases());
        Map<LocalPos, BlockSpec> c = cells(m);
        assertEquals(25, countOf(c, OAK_PLANKS));
        assertEquals(48, countOf(c, STONE_BRICKS));
        assertEquals(Map.of(OAK_PLANKS, 25, STONE_BRICKS, 48), m.bom());
        assertEquals(BlockSpec.of(OAK_PLANKS), c.get(new LocalPos(2, 0, 2)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(0, 1, 0)));
        assertEquals(BlockSpec.of(STONE_BRICKS), c.get(new LocalPos(4, 3, 4)));
        assertNull(c.get(new LocalPos(2, 1, 2)), "the inside is empty");
        assertNull(c.get(new LocalPos(0, 4, 0)), "walls stop one row under the roof base");
    }

    @Test
    void placementsAreOrderedByPhaseThenVWU_andIndexedAndMappedToTheWorld() {
        PlacementManifest m = compile(box5()).manifest();
        for (int k = 0; k < m.placements().size(); k++) {
            assertEquals(k, m.placements().get(k).index());
        }
        Placement first = m.placements().get(0);
        assertEquals(new IntPos(100, 64, 200), first.pos(), "local (0,0,0) is the frame origin");
        assertEquals(new IntPos(101, 64, 200), m.placements().get(1).pos(), "u grows first");
        assertEquals(BuildPhase.STRUCTURE, first.phase());
        assertEquals(new IntPos(100, 65, 200), m.placements().get(25).pos(), "first wall cell: v=1, w=0, u=0");
        assertEquals(VerifyMode.EXACT, first.verify());
        assertEquals(ReplacePolicy.REPLACEABLE, first.replaces());
        assertEquals("f", first.partNodeId());
        assertEquals(CompileFixtures.DIMENSION, m.dimension());
    }

    @Test
    void thePhaseComesBeforeTheHeight() {
        // an upper floor (STRUCTURE, v=4) sits above a ground-floor wall (ENVELOPE, v=1..3) but is built first
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 2, 4));
        nodes.removeIf(n -> !n.id().equals("s") && !n.id().equals("wall-n"));
        nodes.add(node("f1", FLOOR, "s", 0, 0, 0, params("level", 1)));
        PlacementManifest m = compile(nodes).manifest();
        // floor 5x5 = 25, then the north wall 5 long x 3 rows = 15
        assertEquals(List.of(new PhaseRange(BuildPhase.STRUCTURE, 0, 25), new PhaseRange(BuildPhase.ENVELOPE, 25, 40)), m.phases());
        assertEquals(new IntPos(100, 68, 200), m.placements().get(0).pos(), "the first placement is the upper floor's corner (v=4)");
    }

    @Test
    void theHashIsDeterministicAndRecordsTheRegistryVersion() {
        PlacementManifest a = compile(box5()).manifest();
        PlacementManifest b = compile(box5()).manifest();
        assertEquals(a.hash(), b.hash());
        assertEquals(64, a.hash().length());
        assertEquals(CompileFixtures.REGISTRY.version(), a.registryVersion());
        assertEquals(1, a.manifestVersion());
        List<PlanNode> bigger = new ArrayList<>(box5());
        bigger.set(0, node("s", STRUCTURE, null, 0, 0, 0, params("width", 5, "depth", 5, "floors", 1, "floor_height", 5)));
        assertTrue(!compile(bigger).manifest().hash().equals(a.hash()));
    }

    @Test
    void theHashCoversWhatIsBuiltNotTheNodeIds() {
        PlacementManifest a = compile(box5()).manifest();
        List<PlanNode> renamed = new ArrayList<>(shell(5, 5, 1, 4));
        renamed.add(node("g", FLOOR, "s", 0, 0, 0, Map.of()));
        PlacementManifest b = compile(renamed).manifest();
        assertEquals("g", b.placements().get(0).partNodeId());
        assertEquals(a.hash(), b.hash(), "renaming a part does not change what is built");
        assertEquals(a.hash(), ManifestJson.computeHash(a.dimension(), a.registryVersion(), a.worldBounds(), a.placements(),
                a.assemblies(), a.bom()));
        Map<String, Object> tree = ManifestJson.toTree(a);
        assertEquals(a.hash(), tree.get("hash"));
        assertEquals(a.placements().size(), ((List<?>) tree.get("placements")).size());
    }

    @Test
    void theWorldBoundsAreTheSiteBoundsMappedToTheWorld() {
        PlacementManifest m = compile(box5()).manifest();
        // local bounds (-30..90) on a NORTH frame at (100,64,200): x = 100+u, y = 64+v, z = 200-w
        assertEquals(new Box(70, 34, 110, 190, 154, 230), m.worldBounds());
    }

    @Test
    void aMissingSiteIsAnIssueNotACrash() {
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(null, StyleSpec.EMPTY, List.of()));
        assertNull(r.manifest());
        assertEquals(List.of("E-SITE-MISSING"), codes(r));
    }

    @Test
    void foundationFillsBelowTheFloorWithAMargin() {
        List<PlanNode> nodes = box5(node("fd", "micra:foundation", "s", 0, 0, 0, params("margin", 1, "depth", 2)));
        Map<LocalPos, BlockSpec> c = cells(compile(nodes).manifest());
        // u and w run from -1 to 5 (7 each), two layers deep
        assertEquals(7 * 7 * 2, countOf(c, "minecraft:cobblestone"));
        assertEquals(BlockSpec.of("minecraft:cobblestone"), c.get(new LocalPos(-1, -2, -1)));
        assertEquals(BlockSpec.of("minecraft:cobblestone"), c.get(new LocalPos(5, -1, 5)));
        assertNull(c.get(new LocalPos(6, -1, 0)));
    }

    @Test
    void floorHolesAndSlabKind() {
        List<PlanNode> withHole = new ArrayList<>(shell(5, 5, 1, 4));
        withHole.add(node("f", FLOOR, "s", 0, 0, 0, params("holes", new ParamValue.ListV(List.of(i(1), i(1), i(2), i(2))))));
        // 25 cells minus the 2x2 hole
        assertEquals(21, countOf(cells(compile(withHole).manifest()), OAK_PLANKS));

        List<PlanNode> slab = new ArrayList<>(shell(5, 5, 1, 4));
        slab.add(node("f", FLOOR, "s", 0, 0, 0, params("kind", "slab")));
        assertEquals(BlockSpec.of("minecraft:oak_slab", "type", "bottom"), cells(compile(slab).manifest()).get(new LocalPos(1, 0, 1)));

        List<PlanNode> badHoles = new ArrayList<>(shell(5, 5, 1, 4));
        badHoles.add(node("f", FLOOR, "s", 0, 0, 0, params("holes", new ParamValue.ListV(List.of(i(1), i(1), i(2))))));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badHoles)));
    }

    @Test
    void wallOptionsThicknessHalfFromLengthAndLevel() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 2, 4));
        nodes.removeIf(n -> !n.id().equals("s") && !n.id().equals("wall-n"));
        nodes.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "thickness", 2, "part", "half", "from", 1, "length", 3, "level", 1)));
        Map<LocalPos, BlockSpec> c = cells(compile(nodes).manifest());
        // north wall: w=4 and w=3, u=1..3, half of height 3 = 2 rows, level 1 base v = 1*4+1 = 5
        assertEquals(3 * 2 * 2, c.size());
        assertTrue(c.containsKey(new LocalPos(1, 5, 4)));
        assertTrue(c.containsKey(new LocalPos(3, 6, 3)));
        assertTrue(!c.containsKey(new LocalPos(0, 5, 4)));
        assertTrue(!c.containsKey(new LocalPos(1, 7, 4)));
    }

    @Test
    void wallParameterAndAnchorProblemsAreIssues() {
        List<PlanNode> badFrom = new ArrayList<>(shell(5, 5, 1, 4));
        badFrom.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "from", 5)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badFrom)));
        List<PlanNode> tooLong = new ArrayList<>(shell(5, 5, 1, 4));
        tooLong.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "from", 2, "length", 4)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(tooLong)));
        List<PlanNode> badLevel = new ArrayList<>(shell(5, 5, 1, 4));
        badLevel.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "level", 1)));
        assertEquals(List.of("E-PARAM-RANGE"), codes(compile(badLevel)));
        List<PlanNode> noParent = List.of(node("wall-n", WALL, null, 0, 0, 0, params("side", "north")));
        assertEquals(List.of("E-ANCHOR"), codes(compile(noParent)));
        List<PlanNode> wrongParent = List.of(node("s", STRUCTURE, null, 0, 0, 0, Map.of()),
                node("f", FLOOR, "s", 0, 0, 0, Map.of()),
                node("wall-n", WALL, "f", 0, 0, 0, params("side", "north")));
        assertEquals(List.of("E-ANCHOR"), codes(compile(wrongParent)));
    }

    @Test
    void structuresCannotBeRotatedAlone() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.set(0, CompileFixtures.ruled("s", STRUCTURE, null, new Rot(1, false), 0, 0, 0, params("width", 5, "depth", 5)));
        assertTrue(codes(compile(nodes)).contains("E-ANCHOR"));
    }

    @Test
    void aTurnedPartInsideAnUnturnedTemplateIsStillRefused() {
        // The expander only refuses a turned instance; a part that carries its own turn reaches the compiler unchanged.
        PlanNode turnedShell = new PlanNode("shell", STRUCTURE, null, new Anchor.Absolute(new LocalPos(0, 0, 0), new Rot(1, false)),
                Map.of(), Set.of(), "");
        ModuleTemplate hut = new ModuleTemplate(1, "mod:hut", "k", PartCategory.MODULE, VersionRange.ALWAYS, null, List.of(),
                List.of(turnedShell), List.of(), null, null, Set.of());
        TemplateBundle bundle = new TemplateBundle(List.of(hut));
        PlanNode instance = new PlanNode("h", "mod:hut", null, new Anchor.Absolute(new LocalPos(0, 0, 0), Rot.NONE), Map.of(),
                Set.of(), "");
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, List.of(instance), bundle);
        CompileResult r = CompileFixtures.compile(plan, new PlanCompiler(), bundle);
        assertNull(r.manifest());
        assertEquals(List.of("E-ANCHOR:h/shell#rot"), ids(r));
    }

    @Test
    void overlappingWallsWithDifferentBlocksAreReportedOncePerPair_identicalCornersMerge() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "material", "minecraft:stone")));
        nodes.set(2, node("wall-e", WALL, "s", 0, 0, 0, params("side", "east", "material", "minecraft:bricks")));
        nodes.removeIf(n -> n.id().equals("wall-s") || n.id().equals("wall-w")); // only the north-east corner is in dispute
        CompileResult r = compile(nodes);
        assertNull(r.manifest());
        List<Issue> overlaps = r.issues().stream().filter(x -> x.code() == IssueCode.E_OVERLAP).toList();
        assertEquals(1, overlaps.size(), r.issues().toString());
        assertEquals(List.of("wall-e", "wall-n"), overlaps.get(0).subjects());
        // the corner column (4, 1..3, 4): three rows
        assertEquals("3", overlaps.get(0).data().get("count"));
        assertEquals("4,1,4", overlaps.get(0).data().get("firstPos"));
    }

    @Test
    void twoWallsOnTheSameSideAreAnOverlapEvenIfTheyAreIdentical() {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(node("wall-n2", WALL, "s", 0, 0, 0, params("side", "north")));
        CompileResult r = compile(nodes);
        assertNull(r.manifest());
        assertEquals(List.of("E-OVERLAP"), codes(r), "only walls on different sides merge at a corner");
        assertEquals(List.of("wall-n", "wall-n2"), r.issues().get(0).subjects());
    }

    @Test
    void cellsOutsideTheSiteBoundsAreReportedPerPart() {
        Site tight = new Site(CompileFixtures.DIMENSION, new BuildFrame(CompileFixtures.ORIGIN, Facing.NORTH), new Box(0, 0, 0, 4, 2, 4), "", "");
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(tight, StyleSpec.EMPTY, box5()));
        assertNull(r.manifest());
        List<Issue> out = r.issues().stream().filter(x -> x.code() == IssueCode.E_OUT_OF_BOUNDS).toList();
        assertEquals(4, out.size(), "the four walls reach v=3, above the bounds: " + r.issues());
        assertTrue(out.stream().allMatch(x -> x.subjects().size() == 1));
    }

    @Test
    void theCellBudgetStopsHugePlans() {
        CompileResult r = CompileFixtures.compile(CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, box5()),
                new PlanCompiler(50));
        assertNull(r.manifest());
        assertTrue(r.issues().stream().anyMatch(x -> x.code() == IssueCode.E_OUT_OF_BOUNDS && x.data().containsKey("cells")), r.issues().toString());
    }

    @Test
    void forbiddenAndUnlistedMaterialsAreRefusedOnEveryRoute() {
        // 1) a block id given as the material
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(compile(box5node("minecraft:command_block"))));
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(compile(box5node("minecraft:tnt"))));
        // 2) through the style palette (a role)
        CompileResult viaPalette = compile(new StyleSpec(Map.of("floor", "minecraft:bedrock"), Set.of()), box5());
        assertNull(viaPalette.manifest());
        assertEquals(List.of("E-BLOCK-FORBIDDEN"), codes(viaPalette));
        assertEquals(List.of("f"), viaPalette.issues().get(0).subjects());
        // 3) an allowed material passes
        assertNotNull(compile(new StyleSpec(Map.of("wall", "minecraft:red_terracotta"), Set.of()), box5()).manifest());
    }

    @Test
    void anAlwaysForbiddenFinalBlockIsRefusedEvenWhenTheAllowListNamesIt() {
        List<PlanNode> nodes = box5node("minecraft:bedrock");
        SemanticPlan plan = CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, nodes);
        PlaceableBlockPolicy listsBedrock = new PlaceableBlockPolicy(Set.of("minecraft:bedrock", STONE_BRICKS));
        CompileResult r = new PlanCompiler().compile(
                new PlanExpander(CompileFixtures.REGISTRY, SlotResolver.NONE).expand(plan, TemplateBundle.EMPTY, Router.NONE).plan(),
                CompileFixtures.REGISTRY, listsBedrock, CompileFixtures.survey());
        assertEquals(List.of("E-BLOCK-FORBIDDEN:f#minecraft:bedrock"), ids(r));
    }

    private static List<PlanNode> box5node(String material) {
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.add(node("f", FLOOR, "s", 0, 0, 0, params("material", material)));
        return nodes;
    }

    @Test
    void anUnknownPaletteRoleIsAParamRangeIssueWithAHint() {
        CompileResult r = compile(box5node("no_such_role"));
        assertEquals(List.of("E-PARAM-RANGE"), codes(r));
        assertEquals("material", r.issues().get(0).id().substring(r.issues().get(0).id().indexOf('#') + 1));
        assertEquals("USE_ROLE", r.issues().get(0).hints().get(0).kind());
    }

    @Test
    void partsWithoutAGeneratorAreRefused() {
        CompileResult r = compile(List.of(node("m", "test:motor", null, 0, 0, 0, Map.of())));
        assertNull(r.manifest());
        assertEquals(List.of("E-UNKNOWN-PART"), codes(r));
    }

    @Test
    void rotatingTheSiteRotatesTheWorldPositionsAndKeepsTheLocalCells() {
        PlacementManifest north = compile(CompileFixtures.plan(CompileFixtures.site(Facing.NORTH), StyleSpec.EMPTY, box5())).manifest();
        PlacementManifest east = compile(CompileFixtures.plan(CompileFixtures.site(Facing.EAST), StyleSpec.EMPTY, box5())).manifest();
        assertEquals(north.placements().size(), east.placements().size());
        // local (u,w) = (1,0) is world (+1,0) on NORTH but world (0,+1) on EAST (right of east is south)
        assertEquals(new IntPos(101, 64, 200), north.placements().get(1).pos());
        assertEquals(new IntPos(100, 64, 201), east.placements().get(1).pos());
        assertTrue(!north.hash().equals(east.hash()));
    }

    // ------------------------------------------------------------------ plans that skipped the patcher

    @Test
    void aParentLoopInAHandBuiltPlanEndsWithIssues() {
        List<PlanNode> loop = List.of(node("a", FLOOR, "b", 0, 0, 0, Map.of()), node("b", FLOOR, "a", 0, 0, 0, Map.of()));
        CompileResult r = assertTimeoutPreemptively(WALK_TIMEOUT, () -> compileHandBuilt(CompileFixtures.site(Facing.NORTH), loop));
        assertNull(r.manifest());
        assertTrue(codes(r).stream().allMatch("E-ANCHOR"::equals), r.issues().toString());
    }

    @Test
    void aRepeatedNodeIdInAHandBuiltPlanIsRefused() {
        List<PlanNode> twice = new ArrayList<>(box5());
        twice.add(node("f", FLOOR, "s", 0, 0, 0, Map.of()));
        CompileResult r = compileHandBuilt(CompileFixtures.site(Facing.NORTH), twice);
        assertNull(r.manifest());
        assertEquals(List.of("E-ID-DUPLICATE:f"), ids(r));
    }

    @Test
    void aSiteTooLargeToMapToTheWorldIsRefused() {
        Site huge = new Site(CompileFixtures.DIMENSION, new BuildFrame(CompileFixtures.ORIGIN, Facing.NORTH),
                new Box(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 1, 1), "", "");
        CompileResult r = compileHandBuilt(huge, box5());
        assertNull(r.manifest());
        assertEquals(List.of("E-PARAM-RANGE:site#site"), ids(r));
    }

    @Test
    void aGeneratorThatFailsUnexpectedlyBecomesAnIssueOnItsNode() {
        // The patcher would have typed "thickness" as an integer; a hand-built plan can carry any value.
        List<PlanNode> nodes = new ArrayList<>(shell(5, 5, 1, 4));
        nodes.set(1, node("wall-n", WALL, "s", 0, 0, 0, params("side", "north", "thickness", "thick")));
        CompileResult r = compileHandBuilt(CompileFixtures.site(Facing.NORTH), nodes);
        assertNull(r.manifest());
        assertEquals(List.of("E-UNKNOWN-PART:wall-n#generator"), ids(r));
        assertTrue(r.issues().get(0).message().contains("IllegalArgumentException"), r.issues().get(0).message());
    }
}
