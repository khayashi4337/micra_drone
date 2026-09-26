package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.RandomParts;
import io.github.khayashi4337.micradrone.build.model.Anchor;
import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.BuildFrame;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Connection;
import io.github.khayashi4337.micradrone.build.model.Constraints;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.LocalPos;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.ParamValue.BoolV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.IntV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.ListV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.NumV;
import io.github.khayashi4337.micradrone.build.model.ParamValue.StrV;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.PortRef;
import io.github.khayashi4337.micradrone.build.model.Provenance;
import io.github.khayashi4337.micradrone.build.model.Rot;
import io.github.khayashi4337.micradrone.build.model.Routing;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.model.Side;
import io.github.khayashi4337.micradrone.build.model.Site;
import io.github.khayashi4337.micradrone.build.model.StyleSpec;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.ParamType;
import io.github.khayashi4337.micradrone.build.parts.PartCategory;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanScriptRoundTripTest {
    /** U+0001, written through (char) so no invisible byte lands in this file. */
    private static final char CTRL_A = (char) 0x01;
    /** U+2028 LINE SEPARATOR, written through (char) so no invisible byte lands in this file. */
    private static final char LINE_SEPARATOR = (char) 0x2028;

    private final PlanPatcher patcher = new PlanPatcher(TestParts.registryWithDial(), TestParts.bundle());

    private SemanticPlan build(List<PlanOp> ops) {
        PatchResult r = patcher.apply(SemanticPlan.empty("rt-plan"), new PlanPatch("p", 0, "test", ops));
        assertTrue(r.ok(), r.issues().toString());
        return r.plan();
    }

    /** The plan with the revision fields of {@code like}: a hand-made plan starts at revision 0, a replayed one at 1. */
    private static SemanticPlan atRevisionOf(SemanticPlan plan, SemanticPlan like) {
        return new SemanticPlan(plan.schemaVersion(), plan.planId(), like.revision(), like.parentRevision(), plan.site(),
                plan.style(), plan.nodes(), plan.connections(), plan.logistics(), plan.provenance());
    }

    /** plan -> scripts -> recorder -> plan again: the content hash and the plan itself must not move. Returns the scripts. */
    private List<String> assertRoundTrip(SemanticPlan plan) {
        List<String> scripts = PlanScriptWriter.write(plan);
        for (String s : scripts) {
            assertTrue(s.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS, "script length " + s.length());
        }
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "test", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString() + "\n" + String.join("\n---\n", scripts));
        PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
        assertTrue(applied.ok(), applied.issues().toString());
        assertEquals(plan.contentHash(), applied.plan().contentHash(), String.join("\n---\n", scripts));
        // the hash ignores what the plan model could still get wrong (the sign of a zero); equality does not
        assertEquals(atRevisionOf(plan, applied.plan()), applied.plan(), String.join("\n---\n", scripts));
        assertEquals(plan.nodes().size(), applied.plan().nodes().size());
        assertEquals(plan.connections().size(), applied.plan().connections().size());
        return scripts;
    }

    private static PlanNode node(String id, String type, String parent, Anchor anchor, Map<String, ParamValue> params, Set<String> tags, String label) {
        return new PlanNode(id, type, parent, anchor, params, tags, label);
    }

    private static Anchor abs(int u, int v, int w) {
        return new Anchor.Absolute(new LocalPos(u, v, w), Rot.NONE);
    }

    private List<PlanOp> richOps() {
        Site site = new Site("minecraft:overworld", new BuildFrame(new IntPos(-100, 64, 200), Facing.SOUTH), new Box(-5, -5, -5, 30, 30, 30), "digest-1", "claim-1");
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.WEST,
                        List.of(new PortRef("press-1", "item_out")), List.of("conn-1", "conn-2"))),
                List.of(new LogisticsPlan.Route("route-1", "dock-1", "dock-1", List.of(new LocalPos(0, 5, 0), new LocalPos(-9, 5, 9)), "mod:airship_a"),
                        new LogisticsPlan.Route("route-2", "dock-1", "dock-1", List.of(), null)),
                List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", 12.5, "dock-1", "dock-1")));
        Connection autoConn = new Connection("c-auto", new PortRef("m", "out"), new PortRef("press-1", "power_in"), ConnKind.ROTATION,
                Routing.AUTO, Constraints.NONE);
        Connection directConn = new Connection("c-direct", new PortRef("m", "out"), new PortRef("s1", "in"), ConnKind.ROTATION,
                new Routing.Explicit(List.of()), Constraints.NONE);
        Connection viaConn = new Connection("c-via", new PortRef("s1", "out"), new PortRef("press-1", "power_in"), ConnKind.ROTATION,
                new Routing.Explicit(List.of("s1", "s2")), new Constraints(20, Set.of("wall-n", "hut"), 3, Set.of(Dir6.UP, Dir6.NORTH)));
        return List.of(
                new PlanOp.SetSite(site),
                new PlanOp.SetStyle(new StyleSpec(Map.of("roof", "minecraft:red_nether_bricks", "wall", "minecraft:stone_bricks"), Set.of("cozy", "warm"))),
                new PlanOp.AddNode(node("hut", "micra:structure", null, abs(0, 0, 0), Map.of("width", new IntV(7), "depth", new NumV(9.0)),
                        Set.of("main", "b"), "小屋 \"one\"\n\ttab \\ back 🏠")),
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", abs(0, 0, 0), Map.of("side", new StrV("north"), "material", new StrV("wall")), Set.of(), "")),
                new PlanOp.AddNode(node("door-1", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.INNER, 3, 0),
                        Map.of("kind", new StrV("double"), "hinge", new StrV("right")), Set.of(), "")),
                new PlanOp.AddNode(node("win-1", "micra:window", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 1, 1),
                        Map.of("kind", new StrV("arch"), "lattice", new BoolV(true)), Set.of(), "")),
                new PlanOp.AddNode(node("sign-1", "micra:sign", "hut", new Anchor.OnSurface("wall-n", Side.OUTER, 5, 1),
                        Map.of("text", new StrV("say \"hi\"|back\\slash|日本語")), Set.of(), "")),
                new PlanOp.AddNode(node("floor-1", "micra:floor", "hut", abs(0, 0, 0),
                        Map.of("holes", new ListV(List.of(new IntV(1), new IntV(1), new IntV(2), new IntV(2)))), Set.of(), "")),
                new PlanOp.AddNode(node("m", "test:motor", null, abs(-3, 1, 2), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("s1", "test:shaft", null, new Anchor.Absolute(new LocalPos(-2, 1, 2), new Rot(3, true)), Map.of(), Set.of("a", "z"), "")),
                new PlanOp.AddNode(node("s2", "test:shaft", null, abs(-1, 1, 2), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("press-1", "test:press", null, abs(0, 1, 2), Map.of(), Set.of(), "the press")),
                new PlanOp.AddNode(node("dial", "test:dial", null, abs(4, 0, 0), Map.of("speed", new NumV(12.5)), Set.of(), "")),
                new PlanOp.AddNode(node("dial-2", "test:dial", null, abs(5, 0, 0), Map.of("speed", new NumV(2.0)), Set.of(), "")),
                new PlanOp.AddNode(node("line-1", "mod:test_line", null, new Anchor.InSlot("slot-a", new Rot(2, false)), Map.of(), Set.of(), "")),
                new PlanOp.AddConnection(autoConn),
                new PlanOp.AddConnection(directConn),
                new PlanOp.AddConnection(viaConn),
                new PlanOp.SetLogistics(logistics));
    }

    @Test
    void aRichPlanRoundTripsWithTheSameContentHash() {
        SemanticPlan plan = build(richOps());
        List<String> scripts = assertRoundTrip(plan);
        assertEquals(1, scripts.size());
        String text = scripts.get(0);
        assertTrue(text.startsWith("# 建設スクリプト 1/1(計画 rt-plan)\n"), text);
        assertTrue(text.contains("structure(\"hut\", None, [0, 0, 0], {"), "micra parts are written as their own command");
        assertTrue(text.contains("part(\"m\", \"test:motor\", None, [-3, 1, 2], {})"), "other parts use part()");
        assertTrue(text.contains("connect(\"c-direct\", \"m.out\", \"s1.in\", \"rotation\", [])"), "an empty via list means explicit, not auto");
        assertTrue(text.contains("connect(\"c-auto\", \"m.out\", \"press-1.power_in\", \"rotation\")"), "auto has no via");
        assertTrue(text.contains("[\"surface\", \"wall-n\", \"inner\", 3, 0]"));
        assertTrue(text.contains("[\"slot\", \"slot-a\", 2, False]"));
        assertTrue(text.contains("\\\"one\\\"\\n\\ttab"), "special characters are escaped");
    }

    @Test
    void writingTwiceGivesTheSameText() {
        SemanticPlan plan = build(richOps());
        assertEquals(PlanScriptWriter.write(plan), PlanScriptWriter.write(plan));
    }

    @Test
    void anEmptyPlanAndASiteOnlyPlanRoundTrip() {
        assertRoundTrip(SemanticPlan.empty("rt-plan"));
        assertRoundTrip(build(List.of(richOps().get(0))));
        assertEquals(1, PlanScriptWriter.write(SemanticPlan.empty("rt-plan")).size());
    }

    @Test
    void bigPlansSplitIntoSeveralScriptsThatEachFit() {
        List<PlanOp> ops = new ArrayList<>();
        ops.add(richOps().get(0));
        for (int i = 0; i < 400; i++) {
            ops.add(new PlanOp.AddNode(node("post-" + i, "micra:pillar", null, abs(i * 3, 0, 0), Map.of("height", new IntV(3 + i % 5)),
                    Set.of("row-" + i % 7), "a fairly long label to make each statement heavy #" + i)));
        }
        SemanticPlan plan = build(ops);
        List<String> scripts = assertRoundTrip(plan);
        assertTrue(scripts.size() >= 3, "expected several scripts but got " + scripts.size());
        assertTrue(scripts.get(0).startsWith("# 建設スクリプト 1/" + scripts.size()));
        assertTrue(scripts.get(0).contains("site("), "the site comes first");
    }

    @Test
    void aSmallLimitSplitsEverythingButKeepsTheOrder() {
        SemanticPlan plan = build(richOps());
        List<String> scripts = PlanScriptWriter.write(plan, 700);
        assertTrue(scripts.size() > 3);
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(plan.contentHash(), patcher.apply(SemanticPlan.empty("rt-plan"), r.patch()).plan().contentHash());
    }

    @Test
    void aStatementLongerThanAScriptIsRefusedNotTruncated() {
        SemanticPlan plan = build(List.of(new PlanOp.AddNode(node("a", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of(),
                "x".repeat(PlanScriptWriter.MAX_SCRIPT_CHARS)))));
        assertThrows(IllegalStateException.class, () -> PlanScriptWriter.write(plan));
    }

    @Test
    void editingTheScriptChangesThePlan() {
        SemanticPlan plan = build(richOps());
        String edited = PlanScriptWriter.write(plan).get(0).replace("\"width\": 7", "\"width\": 9");
        PlanScriptRunner.Result r = PlanScriptRunner.run(List.of(edited), "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        SemanticPlan changed = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch()).plan();
        assertNotEquals(plan.contentHash(), changed.contentHash());
        assertEquals(new IntV(9), changed.node("hut").orElseThrow().params().get("width"));
    }

    private static String trickyText(Random rnd) {
        // CR and U+0001 are left out on purpose: the plan itself refuses control characters other
        // than \n and \t in labels and tags (PlanPatcher.checkText), so no plan built through the
        // patcher can carry them. The script layer does carry them - the fixed edge test below
        // proves that. U+2028 is appended through (char) to keep the source byte-clean.
        String alphabet = "abc \"\\\n\t日本🏠'#|_-.0" + LINE_SEPARATOR;
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(12);
        for (int i = 0; i < n; ) {
            int cp = alphabet.codePointAt(rnd.nextInt(alphabet.length()));
            sb.appendCodePoint(cp);
            i++;
        }
        return sb.toString();
    }

    @Test
    void randomPlansWithTrickyLabelsAndTagsRoundTrip() {
        for (long seed = 1; seed <= 100; seed++) {
            Random rnd = new Random(seed);
            List<PlanOp> ops = new ArrayList<>();
            ops.add(richOps().get(0));
            for (PlanNode n : RandomParts.nodes(seed)) {
                Set<String> tags = new java.util.TreeSet<>();
                for (int t = rnd.nextInt(3); t > 0; t--) {
                    tags.add(trickyText(rnd));
                }
                ops.add(new PlanOp.AddNode(node(n.id(), n.type(), null, n.anchor(), n.params(), tags, trickyText(rnd))));
            }
            assertRoundTrip(build(ops));
        }
    }

    @Test
    void theScriptLengthConstantMatchesTheOneTheGameEnforces() throws IOException {
        Path source = Path.of("src/main/java/io/github/khayashi4337/micradrone/drone/DroneControllerBlockEntity.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("MAX_SCRIPT_CHARS = " + PlanScriptWriter.MAX_SCRIPT_CHARS + ";"),
                "keep PlanScriptWriter.MAX_SCRIPT_CHARS equal to DroneControllerBlockEntity.MAX_SCRIPT_CHARS");
        assertFalse(PlanScriptWriter.write(SemanticPlan.empty("p")).isEmpty());
    }

    // ------------------------------------------------------------------ edge tests

    /**
     * A raw newline inside a "..." literal would end the literal in the lexer; the writer must emit
     * the two-character escape \n instead. Counts raw '\n' characters seen inside a literal, honoring
     * backslash escapes exactly the way the lexer does.
     */
    private static int rawNewlinesInsideLiterals(String script) {
        int bad = 0;
        boolean inString = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inString && c == '\\') {
                i++; // the escaped character is data, never a delimiter
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (inString && c == '\n') {
                bad++;
            }
        }
        return bad;
    }

    /**
     * Replays the recorded patch without the patcher's own validation. Only the writer output path
     * produces Set/Add ops, so the replay knows just those; anything else would mean the writer or the
     * recorder emitted an op kind this helper was not meant to see.
     */
    private static SemanticPlan applyLoosely(SemanticPlan base, PlanPatch patch) {
        List<PlanNode> nodes = new ArrayList<>(base.nodes());
        List<Connection> connections = new ArrayList<>(base.connections());
        Site site = base.site();
        StyleSpec style = base.style();
        LogisticsPlan logistics = base.logistics();
        for (PlanOp op : patch.ops()) {
            switch (op) {
                case PlanOp.SetSite o -> site = o.site();
                case PlanOp.SetStyle o -> style = o.style();
                case PlanOp.AddNode o -> nodes.add(o.node());
                case PlanOp.AddConnection o -> connections.add(o.connection());
                case PlanOp.SetLogistics o -> logistics = o.logistics();
                default -> throw new AssertionError("loose replay does not model " + op);
            }
        }
        return new SemanticPlan(base.schemaVersion(), base.planId(), base.revision(), base.parentRevision(),
                site, style, nodes, connections, logistics, base.provenance());
    }

    @Test
    void edgeAlphabetCharactersSurviveTheScriptTextVerbatim() {
        // The plan's own rules refuse control characters other than \n and \t in labels and tags
        // (PlanPatcher.checkText / ParamValidator.hasForbiddenControl), so a plan carrying CR or
        // U+0001 can never come out of a patcher apply - which is exactly why the model forbids
        // them. The script layer itself still carries every one of them unchanged (the lexer reads
        // raw characters inside a literal and only \n has to be an escape), so the round trip here
        // is measured on the recorded patch, not through a second apply the model would refuse.
        String label = "'" + "\r" + CTRL_A + LINE_SEPARATOR + "\n" + "\ud83c\udfe0";
        String tag = "\t" + "'" + "\r" + CTRL_A + LINE_SEPARATOR + "\ud83c\udfe0";
        PlanNode tricky = node("edge-1", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of(tag), label);
        SemanticPlan plan = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "rt-plan", 0, null, null,
                StyleSpec.EMPTY, List.of(tricky), List.of(), null, Provenance.NONE);
        List<String> scripts = PlanScriptWriter.write(plan);
        assertEquals(1, scripts.size());
        String text = scripts.get(0);
        assertTrue(text.contains("\\n"), "the newline in the label must be written as the escape");
        assertEquals(0, rawNewlinesInsideLiterals(text), text);
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString() + "\n" + text);
        SemanticPlan replayed = applyLoosely(SemanticPlan.empty("rt-plan"), r.patch());
        assertEquals(tricky, replayed.nodes().get(0));
        assertEquals(plan.contentHash(), replayed.contentHash());
        // the model-level refusal still holds: this is why the characters above never reach a valid plan
        assertFalse(patcher.apply(SemanticPlan.empty("rt-plan"), r.patch()).ok());
    }

    /** The registry plus one part whose NUM parameter takes any finite value (no range). */
    private static PartTypeRegistry wideRegistry() {
        PartTypeRegistry.Builder b = PartTypeRegistry.builder().defaultPalette(BuildingParts.DEFAULT_PALETTE);
        for (PartType t : TestParts.registry().all()) {
            b.register(t);
        }
        b.register(PartType.builder("test:wide", PartCategory.POWER).displayNameKey("t.wide")
                .params(new ParamSpec("v", ParamType.NUM, "", null, null, new NumV(0), List.of(), 0)).build());
        return b.build();
    }

    /** A single value of the format table: written text, and the value the read-back must produce. */
    private record NumCase(double in, String text, double back) {
    }

    @Test
    void numValuesAreWrittenAsPlainDecimalsAndReadBackUnchanged() {
        // Hand-derived: number() writes BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() and
        // the script language has no exponent form, so 1e15 comes out as fifteen digits. An integral
        // value reads back as IntV and the NUM spec coerces it to NumV, so the value survives.
        List<NumCase> cases = List.of(
                new NumCase(2.0, "2", 2.0),
                new NumCase(12.5, "12.5", 12.5),
                new NumCase(0.1, "0.1", 0.1),
                new NumCase(0.30000000000000004, "0.30000000000000004", 0.30000000000000004),
                new NumCase(0.0000001, "0.0000001", 0.0000001),
                new NumCase(1000000000000000.0, "1000000000000000", 1000000000000000.0),
                new NumCase(123456789012.0, "123456789012", 123456789012.0),
                new NumCase(-3.5, "-3.5", -3.5),
                // "-0.0" is written as "0": the minus sign of zero cannot live in the decimal text the
                // language reads, so the model's own rules flatten it to +0.0 (kept explicit, not hidden)
                new NumCase(-0.0, "0", 0.0));
        PlanPatcher widePatcher = new PlanPatcher(wideRegistry(), TestParts.bundle());
        for (NumCase c : cases) {
            SemanticPlan plan;
            {
                PatchResult built = widePatcher.apply(SemanticPlan.empty("rt-plan"), new PlanPatch("p", 0, "test",
                        List.of(new PlanOp.AddNode(node("w", "test:wide", null, abs(0, 0, 0), Map.of("v", new NumV(c.in())), Set.of(), "")))));
                assertTrue(built.ok(), built.issues().toString());
                plan = built.plan();
            }
            List<String> scripts = PlanScriptWriter.write(plan);
            assertEquals(1, scripts.size());
            assertTrue(scripts.get(0).contains("\"v\": " + c.text() + "}"), scripts.get(0));
            PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
            assertTrue(r.ok(), r.issues().toString());
            PatchResult applied = widePatcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
            assertTrue(applied.ok(), applied.issues().toString());
            ParamValue v = applied.plan().node("w").orElseThrow().params().get("v");
            assertEquals(0, Double.compare(c.back(), ((NumV) v).value()), c.text());
            if (Double.compare(c.in(), c.back()) == 0) {
                assertEquals(plan.contentHash(), applied.plan().contentHash(), c.text());
            }
        }
    }

    @Test
    void aNegativeZeroParameterRoundTripsAsAnEqualPlan() {
        // the patcher stores -0.0 as +0.0, so the plan and its replay are EQUAL, not only equal in hash
        PlanPatcher widePatcher = new PlanPatcher(wideRegistry(), TestParts.bundle());
        PatchResult built = widePatcher.apply(SemanticPlan.empty("rt-plan"), new PlanPatch("p", 0, "test",
                List.of(new PlanOp.AddNode(node("w", "test:wide", null, abs(0, 0, 0), Map.of("v", new NumV(-0.0)), Set.of(), "")))));
        assertTrue(built.ok(), built.issues().toString());
        SemanticPlan plan = built.plan();
        List<String> scripts = PlanScriptWriter.write(plan);
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        PatchResult applied = widePatcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
        assertTrue(applied.ok(), applied.issues().toString());
        assertEquals(plan, applied.plan());
        assertEquals(plan.contentHash(), applied.plan().contentHash());
    }

    @Test
    void aNegativeZeroFlowRateRoundTripsAsAnEqualPlan() {
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.WEST, List.of(), List.of())),
                List.of(), List.of(new LogisticsPlan.CargoFlow("create:iron_sheet", -0.0, "dock-1", "dock-1")));
        SemanticPlan plan = build(List.of(new PlanOp.SetLogistics(logistics)));
        List<String> scripts = assertRoundTrip(plan);
        assertTrue(scripts.get(0).contains("\"per_min\": 0,"), scripts.get(0));
    }

    @Test
    void aDockPortNameWithADotRoundTripsBecauseTheSplitIsAtTheFirstDot() {
        LogisticsPlan logistics = new LogisticsPlan(
                List.of(new LogisticsPlan.Dock("dock-1", new Box(0, 0, 0, 8, 0, 8), new Box(0, 1, 0, 8, 16, 8), Facing.WEST,
                        List.of(new PortRef("press-1", "item.out"), new PortRef("press-1", "a.b.c")), List.of())),
                List.of(), List.of());
        SemanticPlan plan = build(List.of(new PlanOp.SetLogistics(logistics)));
        List<String> scripts = assertRoundTrip(plan);
        assertTrue(scripts.get(0).contains("\"ports\": [\"press-1.item.out\", \"press-1.a.b.c\"]"), scripts.get(0));
    }

    @Test
    void aStatementExactlyAtTheBudgetIsAcceptedAndOneCharMoreIsRefused() {
        // Hand-derived: pillar("a", None, [0, 0, 0], {}, [], "<label>") is 41 + label characters, and
        // the packer counts one newline per statement, so a statement takes 42 + label of the budget.
        int fixedChars = "pillar(\"a\", None, [0, 0, 0], {}, [], \"".length() + "\")".length() + 1;
        int budget = PlanScriptWriter.MAX_SCRIPT_CHARS - PlanScriptWriter.HEADER_RESERVE;
        int labelChars = budget - fixedChars;
        SemanticPlan fits = build(List.of(new PlanOp.AddNode(node("a", "micra:pillar", null, abs(0, 0, 0),
                Map.of(), Set.of(), "x".repeat(labelChars)))));
        List<String> scripts = PlanScriptWriter.write(fits);
        assertEquals(1, scripts.size());
        SemanticPlan tooLong = build(List.of(new PlanOp.AddNode(node("a", "micra:pillar", null, abs(0, 0, 0),
                Map.of(), Set.of(), "x".repeat(labelChars + 1)))));
        assertThrows(IllegalStateException.class, () -> PlanScriptWriter.write(tooLong));
        // the boundary statement still runs and reproduces the plan
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString());
        PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
        assertTrue(applied.ok(), applied.issues().toString());
        assertEquals(fits.contentHash(), applied.plan().contentHash());
    }

    @Test
    void thePackingBoundarySplitsOnTheExactCharacter() {
        // Hand-derived: pillar("a", None, [0, 0, 0], {}) and pillar("b", ...) are 33 characters each;
        // with one newline each the body is 2 * 34 = 68 characters.
        int statementChars = "pillar(\"a\", None, [0, 0, 0], {})".length();
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(node("a", "micra:pillar", null, abs(0, 0, 0), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("b", "micra:pillar", null, abs(1, 0, 0), Map.of(), Set.of(), ""))));
        int body = 2 * (statementChars + 1);
        List<String> one = PlanScriptWriter.write(plan, PlanScriptWriter.HEADER_RESERVE + body);
        assertEquals(1, one.size());
        List<String> two = PlanScriptWriter.write(plan, PlanScriptWriter.HEADER_RESERVE + body - 1);
        assertEquals(2, two.size());
        assertTrue(two.get(0).startsWith("# 建設スクリプト 1/2(計画 rt-plan)"));
        assertTrue(two.get(1).startsWith("# 建設スクリプト 2/2(計画 rt-plan)"));
        for (List<String> scripts : List.of(one, two)) {
            PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "t", PlanRunLimits.DEFAULT);
            assertTrue(r.ok(), r.issues().toString());
            PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
            assertTrue(applied.ok(), applied.issues().toString());
            assertEquals(plan.contentHash(), applied.plan().contentHash());
        }
    }

    @Test
    void shuffledScriptOrderDoesNotReproduceThePlan() {
        SemanticPlan plan = build(richOps());
        List<String> scripts = PlanScriptWriter.write(plan, 700);
        assertTrue(scripts.size() > 3);
        for (int i = 0; i < scripts.size(); i++) {
            assertTrue(scripts.get(i).startsWith("# 建設スクリプト " + (i + 1) + "/" + scripts.size() + "(計画 "),
                    "header numbers must be i/n in order: " + scripts.get(i));
        }
        // reversed, the last chunk runs first: connect and child nodes land before the nodes they use
        List<String> reversed = new ArrayList<>(scripts);
        Collections.reverse(reversed);
        PlanScriptRunner.Result r = PlanScriptRunner.run(reversed, "rt", 0, "t", PlanRunLimits.DEFAULT);
        boolean reproduced = false;
        if (r.ok()) {
            PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
            reproduced = applied.ok() && applied.plan().contentHash().equals(plan.contentHash());
        }
        assertFalse(reproduced, "out-of-order scripts must not reproduce the plan");
    }

    @Test
    void styleAndMoodLinesComeInDictionaryOrder() {
        SemanticPlan plan = build(List.of(new PlanOp.SetStyle(new StyleSpec(
                Map.of("roof", "minecraft:red_nether_bricks", "wall", "minecraft:stone_bricks",
                        "floor", "minecraft:oak_planks"),
                Set.of("warm", "cozy")))));
        String text = PlanScriptWriter.write(plan).get(0);
        assertTrue(text.contains("style(\"floor\", \"minecraft:oak_planks\")\n"
                + "style(\"roof\", \"minecraft:red_nether_bricks\")\n"
                + "style(\"wall\", \"minecraft:stone_bricks\")\n"
                + "mood(\"cozy\")\n"
                + "mood(\"warm\")\n"), text);
        assertRoundTrip(plan);
    }

    @Test
    void everyMicraPartCommandAppearsInTheOutputAndRoundTrips() {
        // hand-listed, pinned equal to CommandNames.PLAN_PART_COMMANDS so a drift between the two fails here
        List<String> expected = List.of("balcony", "beam", "catwalk", "chimney", "dock_pad", "door", "floor",
                "foundation", "ladder", "lamp", "pillar", "planter", "railing", "ramp", "road", "roof", "sign",
                "stairs", "structure", "trim", "wall", "window");
        assertEquals(expected, CommandNames.PLAN_PART_COMMANDS);
        List<PlanOp> ops = new ArrayList<>();
        int i = 0;
        for (String name : CommandNames.PLAN_PART_COMMANDS) {
            // wall.side and sign.text are the only required parameters of the 22 parts
            Map<String, ParamValue> params = switch (name) {
                case "wall" -> Map.of("side", new StrV("north"));
                case "sign" -> Map.of("text", new StrV("hi"));
                default -> Map.of();
            };
            ops.add(new PlanOp.AddNode(node("p" + i, "micra:" + name, null, abs(i * 10, 0, 0), params, Set.of(), "")));
            i++;
        }
        SemanticPlan plan = build(ops);
        List<String> scripts = assertRoundTrip(plan);
        String all = String.join("\n", scripts);
        for (String name : expected) {
            boolean found = false;
            for (String line : all.split("\n")) {
                if (line.startsWith(name + "(")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "no statement line starts with " + name + "(");
        }
    }
}
