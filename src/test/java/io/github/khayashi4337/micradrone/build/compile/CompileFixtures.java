package io.github.khayashi4337.micradrone.build.compile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.BlockSpec;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds plans and compiles them with the building parts. Tests use a NORTH frame so world offsets equal local ones. */
public final class CompileFixtures {
    public static final IntPos ORIGIN = new IntPos(100, 64, 200);
    public static final Box BOUNDS = new Box(-30, -30, -30, 90, 90, 90);
    public static final PartTypeRegistry REGISTRY = TestParts.registry();
    public static final String DIMENSION = "minecraft:overworld";

    private static final String PLAN_ID = "plan";
    private static final String PATCH_ID = "p";
    private static final String STAGE_ID = "test";
    private static final String SURVEY_DIGEST = "digest";
    private static final String STRUCTURE = "micra:structure";
    private static final String WALL = "micra:wall";
    private static final String STRUCTURE_ID = "s";
    private static final String WALL_ID_PREFIX = "wall-";

    private CompileFixtures() {
    }

    public static Site site(Facing facing) {
        return new Site(DIMENSION, new BuildFrame(ORIGIN, facing), BOUNDS, "", "");
    }

    public static IntV i(int v) {
        return new IntV(v);
    }

    public static StrV s(String v) {
        return new StrV(v);
    }

    public static BoolV b(boolean v) {
        return new BoolV(v);
    }

    public static PlanNode node(String id, String type, String parent, int u, int v, int w, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE), params, Set.of(), "");
    }

    public static PlanNode ruled(String id, String type, String parent, Rot rot, int u, int v, int w, Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.Absolute(new LocalPos(u, v, w), rot), params, Set.of(), "");
    }

    public static PlanNode onWall(String id, String type, String parent, String wall, Side side, int u, int v,
                                  Map<String, ParamValue> params) {
        return new PlanNode(id, type, parent, new Anchor.OnSurface(wall, side, u, v), params, Set.of(), "");
    }

    /** A building named "s" of the given size plus its four walls named wall-n/e/s/w. */
    public static List<PlanNode> shell(int width, int depth, int floors, int floorHeight) {
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node(STRUCTURE_ID, STRUCTURE, null, 0, 0, 0,
                Map.of("width", i(width), "depth", i(depth), "floors", i(floors), "floor_height", i(floorHeight))));
        for (String side : List.of("north", "east", "south", "west")) {
            nodes.add(node(WALL_ID_PREFIX + side.charAt(0), WALL, STRUCTURE_ID, 0, 0, 0, Map.of("side", s(side))));
        }
        return nodes;
    }

    public static SemanticPlan plan(Site site, StyleSpec style, List<PlanNode> nodes) {
        return plan(site, style, nodes, TemplateBundle.EMPTY);
    }

    public static SemanticPlan plan(Site site, StyleSpec style, List<PlanNode> nodes, TemplateBundle templates) {
        PlanPatcher patcher = new PlanPatcher(REGISTRY, templates);
        List<PlanOp> ops = new ArrayList<>();
        if (site != null) {
            ops.add(new PlanOp.SetSite(site));
        }
        ops.add(new PlanOp.SetStyle(style));
        for (PlanNode n : nodes) {
            ops.add(new PlanOp.AddNode(n));
        }
        PatchResult r = patcher.apply(SemanticPlan.empty(PLAN_ID), new PlanPatch(PATCH_ID, 0, STAGE_ID, ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    public static CompileResult compile(SemanticPlan plan) {
        return compile(plan, new PlanCompiler());
    }

    public static CompileResult compile(SemanticPlan plan, PlanCompiler compiler) {
        return compile(plan, compiler, TemplateBundle.EMPTY);
    }

    public static CompileResult compile(SemanticPlan plan, PlanCompiler compiler, TemplateBundle templates) {
        ExpandResult expanded = new PlanExpander(REGISTRY, SlotResolver.NONE).expand(plan, templates, Router.NONE);
        assertTrue(expanded.issues().isEmpty(), expanded.issues().toString());
        return compiler.compile(expanded.plan(), REGISTRY, PlaceableBlockPolicy.builtin(), survey());
    }

    public static SurveyRef survey() {
        return new SurveyRef(SURVEY_DIGEST, 0L);
    }

    public static CompileResult compile(List<PlanNode> nodes) {
        return compile(plan(site(Facing.NORTH), StyleSpec.EMPTY, nodes));
    }

    public static CompileResult compile(StyleSpec style, List<PlanNode> nodes) {
        return compile(plan(site(Facing.NORTH), style, nodes));
    }

    /** Local position to block, read back from a NORTH-frame manifest (properties are unrotated in that frame). */
    public static Map<LocalPos, BlockSpec> cells(PlacementManifest m) {
        BuildFrame frame = m.frame();
        Map<LocalPos, BlockSpec> out = new TreeMap<>(Comparator
                .comparingInt(LocalPos::v).thenComparingInt(LocalPos::w).thenComparingInt(LocalPos::u));
        for (Placement p : m.placements()) {
            out.put(frame.toLocal(p.pos()), p.block());
        }
        return out;
    }

    public static List<String> codes(CompileResult r) {
        return r.issues().stream().map(x -> x.code().label()).toList();
    }

    public static Map<String, ParamValue> params(Object... keyValues) {
        Map<String, ParamValue> m = new TreeMap<>();
        for (int k = 0; k < keyValues.length; k += 2) {
            Object v = keyValues[k + 1];
            ParamValue pv = v instanceof ParamValue p ? p : v instanceof Integer n ? new IntV(n)
                    : v instanceof Boolean flag ? new BoolV(flag) : new StrV(String.valueOf(v));
            m.put((String) keyValues[k], pv);
        }
        return m;
    }

    public static long countOf(Map<LocalPos, BlockSpec> cells, String blockId) {
        return cells.values().stream().filter(b -> b.blockId().equals(blockId)).count();
    }
}
