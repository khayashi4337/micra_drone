package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** The scripts written for a plan, and the plan that running them and applying the recorded patch to an empty plan gives. */
    private record Replay(List<String> scripts, SemanticPlan plan) {
    }

    /** plan -> scripts -> recorder -> patch -> an empty plan patched: every step must work; {@code context} names the case. */
    private Replay replay(SemanticPlan plan, String context) {
        List<String> scripts = PlanScriptWriter.write(plan);
        String shown = context + "\n" + String.join("\n---\n", scripts);
        for (String s : scripts) {
            assertTrue(s.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS, "script length " + s.length());
        }
        PlanScriptRunner.Result r = PlanScriptRunner.run(scripts, "rt", 0, "test", PlanRunLimits.DEFAULT);
        assertTrue(r.ok(), r.issues().toString() + "\n" + shown);
        PatchResult applied = patcher.apply(SemanticPlan.empty("rt-plan"), r.patch());
        assertTrue(applied.ok(), applied.issues().toString() + "\n" + shown);
        return new Replay(scripts, applied.plan());
    }

    /**
     * plan -> scripts -> recorder -> plan again, for a plan whose stored nodes already come parents and wall
     * faces first: the content hash and the plan itself, node order included, must not move. Returns the scripts.
     */
    private List<String> assertRoundTrip(SemanticPlan plan) {
        Replay replayed = replay(plan, "");
        assertEquals(plan.contentHash(), replayed.plan().contentHash(), String.join("\n---\n", replayed.scripts()));
        // the hash ignores what the plan model could still get wrong (the sign of a zero); equality does not
        assertEquals(atRevisionOf(plan, replayed.plan()), replayed.plan(), String.join("\n---\n", replayed.scripts()));
        return replayed.scripts();
    }

    /** The plan with its nodes in id order, the order the content hash uses. */
    private static SemanticPlan nodesById(SemanticPlan plan) {
        List<PlanNode> sorted = new ArrayList<>(plan.nodes());
        sorted.sort(Comparator.comparing(PlanNode::id));
        return new SemanticPlan(plan.schemaVersion(), plan.planId(), plan.revision(), plan.parentRevision(), plan.site(),
                plan.style(), sorted, plan.connections(), plan.logistics(), plan.provenance());
    }

    /** True when every node comes after its parent and after the wall it rests on (when those are nodes of the list). */
    private static boolean isDependencyOrder(List<PlanNode> nodes) {
        Set<String> all = new HashSet<>();
        for (PlanNode n : nodes) {
            all.add(n.id());
        }
        Set<String> placed = new HashSet<>();
        for (PlanNode n : nodes) {
            boolean parentReady = n.parent() == null || !all.contains(n.parent()) || placed.contains(n.parent());
            boolean wallReady = !(n.anchor() instanceof Anchor.OnSurface s) || !all.contains(s.nodeId()) || placed.contains(s.nodeId());
            if (!parentReady || !wallReady) {
                return false;
            }
            placed.add(n.id());
        }
        return true;
    }

    /**
     * For a plan whose stored order is NOT a dependency order (a node relocated onto a wall added after it): the
     * replay adds nodes in a valid order, so it is the same plan with the nodes reordered - equal once both are put
     * in id order, and equal in hash. Returns the replay.
     */
    private Replay assertRoundTripInDependencyOrder(SemanticPlan plan, String context) {
        Replay replayed = replay(plan, context);
        String shown = context + "\n" + String.join("\n---\n", replayed.scripts());
        assertEquals(plan.contentHash(), replayed.plan().contentHash(), shown);
        assertEquals(nodesById(atRevisionOf(plan, replayed.plan())), nodesById(replayed.plan()), shown);
        assertTrue(isDependencyOrder(replayed.plan().nodes()), "the replay adds nodes in a valid order: " + shown);
        return replayed;
    }

    private static List<String> nodeIds(SemanticPlan plan) {
        return plan.nodes().stream().map(PlanNode::id).toList();
    }

    private static final Pattern STATEMENT_HEAD = Pattern.compile("^([a-z_]+)\\(\"([^\"]*)\"");
    private static final Set<String> NON_NODE_COMMANDS = Set.of("site", "style", "mood", "connect", "logistics");

    /** The ids of the node statements in the order the scripts hold them. */
    private static List<String> nodeIdsInScripts(List<String> scripts) {
        List<String> ids = new ArrayList<>();
        for (String script : scripts) {
            for (String line : script.split("\n")) {
                Matcher m = STATEMENT_HEAD.matcher(line);
                if (m.find() && !NON_NODE_COMMANDS.contains(m.group(1))) {
                    ids.add(m.group(2));
                }
            }
        }
        return ids;
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

    // ------------------------------------------------------------------ dependency order

    private static PlanNode wall(String id) {
        return node(id, "micra:wall", null, abs(0, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
    }

    private static PlanNode plain(String id, String type, String parent) {
        return node(id, type, parent, abs(0, 0, 0), Map.of(), Set.of(), "");
    }

    private static PlanOp relocateOnto(String id, String wallId) {
        return new PlanOp.MoveNode(id, new Anchor.OnSurface(wallId, Side.OUTER, 1, 0));
    }

    @Test
    void aNodeRelocatedOntoAWallAddedLaterIsWrittenAfterThatWall() {
        // built through the patcher with a real MoveNode: door-1 is stored before wall-1 and then rests on it
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(plain("door-1", "micra:door", null)),
                new PlanOp.AddNode(wall("wall-1")),
                relocateOnto("door-1", "wall-1")));
        assertEquals(List.of("door-1", "wall-1"), nodeIds(plan), "the plan stores the door first");

        List<String> scripts = PlanScriptWriter.write(plan);
        // hand-derived: wall-1 goes first because the door needs it; the header, then one statement per node
        assertEquals(List.of("# 建設スクリプト 1/1(計画 rt-plan)\n"
                + "wall(\"wall-1\", None, [0, 0, 0], {\"side\": \"north\"})\n"
                + "door(\"door-1\", None, [\"surface\", \"wall-1\", \"outer\", 1, 0], {})\n"), scripts);

        Replay replayed = assertRoundTripInDependencyOrder(plan, "door-1 onto wall-1");
        assertEquals(List.of("wall-1", "door-1"), nodeIds(replayed.plan()));
    }

    @Test
    void aChainOfFiveWallsEachRelocatedOntoTheNextOneAddedLaterIsWrittenFromTheEndOfTheChain() {
        // w0 rests on w1, w1 on w2, w2 on w3, w3 on w4: every wall but the last needs one added after it
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(wall("w0")), new PlanOp.AddNode(wall("w1")), new PlanOp.AddNode(wall("w2")),
                new PlanOp.AddNode(wall("w3")), new PlanOp.AddNode(wall("w4")),
                relocateOnto("w0", "w1"), relocateOnto("w1", "w2"), relocateOnto("w2", "w3"), relocateOnto("w3", "w4")));
        assertEquals(List.of("w0", "w1", "w2", "w3", "w4"), nodeIds(plan));

        // hand-derived: only w4 needs nothing, then w3 can go, then w2, w1, w0
        assertEquals(List.of("w4", "w3", "w2", "w1", "w0"), nodeIdsInScripts(PlanScriptWriter.write(plan)));
        Replay replayed = assertRoundTripInDependencyOrder(plan, "chain of five");
        assertEquals(List.of("w4", "w3", "w2", "w1", "w0"), nodeIds(replayed.plan()));
    }

    @Test
    void theNodesThatAreReadyGoInTheirOwnOrderAndNothingElseIsMoved() {
        // stored: door-1, pillar-1, wall-1, pillar-2; the door rests on the wall. Hand-derived: pillar-1 is ready first;
        // then wall-1 (the earliest ready node); that frees door-1, which is earlier than pillar-2, so door-1 goes
        // next and pillar-2 last.
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(plain("door-1", "micra:door", null)), new PlanOp.AddNode(plain("pillar-1", "micra:pillar", null)),
                new PlanOp.AddNode(wall("wall-1")), new PlanOp.AddNode(plain("pillar-2", "micra:pillar", null)),
                relocateOnto("door-1", "wall-1")));
        assertEquals(List.of("pillar-1", "wall-1", "door-1", "pillar-2"), nodeIdsInScripts(PlanScriptWriter.write(plan)));
        assertRoundTripInDependencyOrder(plan, "ready nodes keep their own order");
    }

    @Test
    void theChildrenOfARelocatedNodeFollowIt() {
        // knob-1 hangs below door-1 (parent); the door is then relocated onto wall-1, which was added after both
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(plain("door-1", "micra:door", null)), new PlanOp.AddNode(plain("knob-1", "micra:pillar", "door-1")),
                new PlanOp.AddNode(wall("wall-1")), relocateOnto("door-1", "wall-1")));
        assertEquals(List.of("wall-1", "door-1", "knob-1"), nodeIdsInScripts(PlanScriptWriter.write(plan)));
        assertRoundTripInDependencyOrder(plan, "a child follows its relocated parent");
    }

    @Test
    void aPlanAlreadyInDependencyOrderIsWrittenInItsOwnOrderWithTheSameText() {
        SemanticPlan plan = build(List.of(
                new PlanOp.AddNode(node("hut", "micra:structure", null, abs(0, 0, 0), Map.of("width", new IntV(7)), Set.of(), "")),
                new PlanOp.AddNode(node("wall-n", "micra:wall", "hut", abs(0, 0, 0), Map.of("side", new StrV("north")), Set.of(), "")),
                new PlanOp.AddNode(node("door-1", "micra:door", "hut", new Anchor.OnSurface("wall-n", Side.INNER, 3, 0), Map.of(), Set.of(), "")),
                new PlanOp.AddNode(node("pillar-1", "micra:pillar", null, abs(10, 0, 0), Map.of("height", new IntV(5)), Set.of(), ""))));
        assertTrue(isDependencyOrder(plan.nodes()));
        assertEquals(List.of("# 建設スクリプト 1/1(計画 rt-plan)\n"
                + "structure(\"hut\", None, [0, 0, 0], {\"width\": 7})\n"
                + "wall(\"wall-n\", \"hut\", [0, 0, 0], {\"side\": \"north\"})\n"
                + "door(\"door-1\", \"hut\", [\"surface\", \"wall-n\", \"inner\", 3, 0], {})\n"
                + "pillar(\"pillar-1\", None, [10, 0, 0], {\"height\": 5})\n"), PlanScriptWriter.write(plan));
        assertRoundTrip(plan);
    }

    @Test
    void theRichPlanKeepsItsNodeOrderInTheScripts() {
        SemanticPlan plan = build(richOps());
        assertTrue(isDependencyOrder(plan.nodes()));
        assertEquals(nodeIds(plan), nodeIdsInScripts(PlanScriptWriter.write(plan)));
    }

    /** Deep enough that a recursive ordering would overflow the stack and a quadratic one would take minutes. */
    private static final int LONG_CHAIN_NODES = 50_000;

    @Test
    void aLongDependencyChainIsOrderedWithoutRecursionOrDelay() {
        // w0 rests on w1, w1 on w2, ...: the whole plan has to be written backwards
        List<PlanOp> ops = new ArrayList<>();
        for (int i = 0; i < LONG_CHAIN_NODES; i++) {
            ops.add(new PlanOp.AddNode(wall("w" + i)));
        }
        for (int i = 0; i + 1 < LONG_CHAIN_NODES; i++) {
            ops.add(relocateOnto("w" + i, "w" + (i + 1)));
        }
        SemanticPlan plan = build(ops);
        List<String> scripts = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> PlanScriptWriter.write(plan));
        List<String> expected = new ArrayList<>();
        for (int i = LONG_CHAIN_NODES - 1; i >= 0; i--) {
            expected.add("w" + i);
        }
        assertEquals(expected, nodeIdsInScripts(scripts));
    }

    @Test
    void aPlanThatCannotBeOrderedIsRefusedNamingTheStuckNodes() {
        // hand-built, so the patcher's rules did not keep it out: w1 and w2 hang below each other, w3 rests on w1
        PlanNode w1 = node("w1", "micra:wall", "w2", abs(0, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
        PlanNode w2 = node("w2", "micra:wall", "w1", abs(0, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
        PlanNode w3 = node("w3", "micra:wall", null, new Anchor.OnSurface("w1", Side.OUTER, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
        SemanticPlan stuck = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "rt-plan", 0, null, null, StyleSpec.EMPTY,
                List.of(plain("free-1", "micra:pillar", null), w1, w2, w3), List.of(), null, Provenance.NONE);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> PlanScriptWriter.write(stuck));
        assertTrue(e.getMessage().contains("w1") && e.getMessage().contains("w2") && e.getMessage().contains("w3"), e.getMessage());
        assertFalse(e.getMessage().contains("free-1"), "a node that could be written is not named: " + e.getMessage());

        PlanNode onItself = node("w1", "micra:wall", null, new Anchor.OnSurface("w1", Side.OUTER, 0, 0), Map.of("side", new StrV("north")), Set.of(), "");
        SemanticPlan self = new SemanticPlan(SemanticPlan.SCHEMA_VERSION, "rt-plan", 0, null, null, StyleSpec.EMPTY,
                List.of(onItself), List.of(), null, Provenance.NONE);
        assertThrows(IllegalStateException.class, () -> PlanScriptWriter.write(self));
    }

    /** How many random plans are grown, and how many operations each of them tries. */
    private static final int RANDOM_SEEDS = 300;
    private static final int RANDOM_ATTEMPTS = 80;
    /**
     * Of the random plans at least this many must have a stored order that is NOT a dependency order, or the test
     * would no longer exercise the reordering (the seeded generator yields 125 of the 300 plans).
     */
    private static final int MIN_REORDERED_PLANS = 60;

    @Test
    void randomPlansGrownByEveryKindOfOperationRoundTripAsEqualPlans() {
        // The plans come from operations applied one at a time and kept only when the patcher accepts them, so they
        // are exactly what the patcher lets in: relocations onto walls added later, every anchor and routing form,
        // constraints, logistics, style and mood, and sites with only a digest, only a claim id or neither.
        int reordered = 0;
        Map<String, Integer> acceptedByKind = new TreeMap<>();
        for (long seed = 1; seed <= RANDOM_SEEDS; seed++) {
            RandomPlanOps grower = new RandomPlanOps(seed, patcher, TestParts.registryWithDial());
            SemanticPlan plan = grower.build(RANDOM_ATTEMPTS);
            grower.acceptedByKind().forEach((kind, n) -> acceptedByKind.merge(kind, n, Integer::sum));
            String context = "seed " + seed;
            if (isDependencyOrder(plan.nodes())) {
                Replay replayed = replay(plan, context);
                assertEquals(plan.contentHash(), replayed.plan().contentHash(), context);
                assertEquals(atRevisionOf(plan, replayed.plan()), replayed.plan(), context + ": node order included");
            } else {
                reordered++;
                assertRoundTripInDependencyOrder(plan, context);
            }
        }
        for (String kind : List.of("AddNode", "MoveNode", "UpdateParams", "RemoveNode", "AddConnection", "RemoveConnection",
                "SetStyle", "SetSite", "SetLogistics")) {
            assertTrue(acceptedByKind.getOrDefault(kind, 0) > 0, "no " + kind + " was accepted in any plan: " + acceptedByKind);
        }
        assertTrue(reordered >= MIN_REORDERED_PLANS, "only " + reordered + " plans needed reordering; " + acceptedByKind);
    }
}
