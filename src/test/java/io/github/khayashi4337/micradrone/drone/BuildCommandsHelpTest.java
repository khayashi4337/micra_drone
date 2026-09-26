package io.github.khayashi4337.micradrone.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.TestParts;
import io.github.khayashi4337.micradrone.build.compile.CompileResult;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import io.github.khayashi4337.micradrone.build.compile.Placement;
import io.github.khayashi4337.micradrone.build.compile.PlanCompiler;
import io.github.khayashi4337.micradrone.build.compile.SurveyRef;
import io.github.khayashi4337.micradrone.build.model.ConnKind;
import io.github.khayashi4337.micradrone.build.model.Dir6;
import io.github.khayashi4337.micradrone.build.model.Facing;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.LogisticsPlan;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanIds;
import io.github.khayashi4337.micradrone.build.model.PlanNode;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import io.github.khayashi4337.micradrone.build.parts.ParamSpec;
import io.github.khayashi4337.micradrone.build.parts.PartType;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.ExpandResult;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanExpander;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.Router;
import io.github.khayashi4337.micradrone.build.plan.SlotResolver;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.build.script.PlanRecorder;
import io.github.khayashi4337.micradrone.build.script.PlanScriptRunner;
import io.github.khayashi4337.micradrone.build.script.PlanScriptWriter;
import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The construction-script reference is a promise about what the code does TODAY. Every number it states is read back from
 * the code's own constant, every command and error name it shows is checked against the real lists, its example is run
 * through the whole pipeline (script, patch, expand, compile), and each behavior it describes is exercised by a script
 * whose expected issue id is derived from the design rules, so a change in the code that the help no longer describes
 * fails here instead of leaving a stale scroll.
 */
class BuildCommandsHelpTest {
    private static final String HELP = CommandsHelpDoc.BUILD_COMMANDS;

    private static final String PATCH_ID = "help";
    private static final String STAGE_ID = "help";
    private static final String PLAN_ID = "help";
    private static final int BASE_REVISION = 0;
    private static final SurveyRef SURVEY = new SurveyRef("help", 0L);

    /** The mark that starts each section of the help, and the header of the example's section. */
    private static final String SECTION_MARK = "■";
    private static final String EXAMPLE_HEADER = SECTION_MARK + " 例: 赤い屋根の小屋\n";
    /** How the help shows a building part: {@code wall(…): side=… level=…}, on one line plus indented continuations. */
    private static final String PART_ENTRY_MARK = "(…): ";

    // The ids of the issues a script's own run reports (the script is number 1 of the run; see PlanScriptRunner).
    /** A script that does not parse: bad layout, a full-width digit, a nesting or depth past the limit. */
    private static final String SYNTAX_ERROR = "E-SCHEMA:#syntax:1";
    /** A script that parses but stops while running: a bad argument, a call to what is not defined, recursion too deep. */
    private static final String RUN_ERROR = "E-SCHEMA:#run:1";
    /** A script that runs into one of its limits while running. */
    private static final String RUN_LIMIT = "E-SCRIPT-LIMIT:#run:1";

    // A house for the scripts below: a site, one building, and (where a script needs one) its north wall.
    private static final String SITE = "site(\"minecraft:overworld\", 0, 64, 0, \"north\", [-2, -3, -2, 12, 9, 12])\n";
    private static final String HOUSE = SITE + "structure(\"s\", None, [0, 0, 0], {})\n";
    private static final String NORTH_WALL = "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n";
    private static final String HOUSE_WITH_WALL = HOUSE + NORTH_WALL;

    // ------------------------------------------------------------------ the pipeline

    private enum Stage { RUN, PATCH, EXPAND, COMPILE }

    /** One documented behavior: the script, how far to run it, and the error ids the FIRST failing stage reports. */
    private record Claim(String what, String script, PartTypeRegistry registry, Stage upTo, List<String> ids,
                         String messageHas) {
    }

    private static Claim fails(String what, String script, String... ids) {
        return new Claim(what, script, BuildingParts.registry(), Stage.COMPILE, List.of(ids), null);
    }

    private static Claim failsAt(String what, Stage upTo, String script, String... ids) {
        return new Claim(what, script, BuildingParts.registry(), upTo, List.of(ids), null);
    }

    private static Claim failsWith(String what, String script, String messageHas, String... ids) {
        return new Claim(what, script, BuildingParts.registry(), Stage.COMPILE, List.of(ids), messageHas);
    }

    private static Claim works(String what, Stage upTo, String script) {
        return new Claim(what, script, BuildingParts.registry(), upTo, List.of(), null);
    }

    /** A claim on the made-up machine parts of {@link TestParts}, which (unlike the building parts) have ports. */
    private static Claim onMachines(String what, Stage upTo, String script, String... ids) {
        return new Claim(what, script, TestParts.registry(), upTo, List.of(ids), null);
    }

    /** The error issues of the first stage (up to {@code upTo}) that has any; empty when the script gets that far. */
    private static List<Issue> errorsOf(String script, PartTypeRegistry registry, Stage upTo) {
        PlanScriptRunner.Result run = PlanScriptRunner.run(List.of(script), PATCH_ID, BASE_REVISION, STAGE_ID, PlanRunLimits.DEFAULT);
        if (!run.ok()) {
            return errorsIn(run.issues());
        }
        if (upTo == Stage.RUN) {
            return List.of();
        }
        PatchResult patched = new PlanPatcher(registry, TemplateBundle.EMPTY).apply(SemanticPlan.empty(PLAN_ID), run.patch());
        if (!patched.ok()) {
            return errorsIn(patched.issues());
        }
        if (upTo == Stage.PATCH) {
            return List.of();
        }
        ExpandResult expanded = new PlanExpander(registry, SlotResolver.NONE).expand(patched.plan(), TemplateBundle.EMPTY, Router.NONE);
        if (expanded.plan() == null) {
            return errorsIn(expanded.issues());
        }
        if (upTo == Stage.EXPAND) {
            return List.of();
        }
        CompileResult compiled = new PlanCompiler().compile(expanded.plan(), registry, PlaceableBlockPolicy.builtin(), SURVEY);
        return compiled.manifest() == null ? errorsIn(compiled.issues()) : List.of();
    }

    private static List<Issue> errorsIn(List<Issue> issues) {
        List<Issue> out = issues.stream().filter(Issue::isError).toList();
        assertFalse(out.isEmpty(), "a refused stage must carry an error: " + issues);
        return out;
    }

    private static List<String> sortedIds(List<Issue> issues) {
        return issues.stream().map(Issue::id).sorted().toList();
    }

    private static void assertClaim(Claim c) {
        List<Issue> errors = errorsOf(c.script(), c.registry(), c.upTo());
        assertEquals(c.ids().stream().sorted().toList(), sortedIds(errors), c.what() + " -> " + errors);
        if (c.messageHas() != null) {
            assertTrue(errors.stream().anyMatch(i -> i.message().contains(c.messageHas())),
                    c.what() + ": no message has \"" + c.messageHas() + "\": " + errors);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A private constant of the interpreter or the recorder: the help must quote the code's own number, not a copy. */
    private static long constant(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return ((Number) f.get(null)).longValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(owner.getSimpleName() + "." + name + " is gone or renamed: update the help and this test", e);
        }
    }

    private static String partName(PartType t) {
        return t.id().substring(BuildingParts.ID_PREFIX.length());
    }

    /** The bit length of {@code cap}: the smallest k with 2^k above it, so 2^(k-1) is still allowed and 2^k is not. */
    private static int doublingsPastCap(long cap) {
        return 64 - Long.numberOfLeadingZeros(cap);
    }

    /** {@code s = "x"} doubled {@code times} times: a string of 2^times characters. */
    private static String doubled(int times) {
        return "s = \"x\"\nfor i in range(" + times + "):\n    s = s + s\n";
    }

    private static String nestedIfs(int levels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            sb.append(" ".repeat(i)).append("if True:\n");
        }
        return sb.append(" ".repeat(levels)).append("pass\n").toString();
    }

    private static String recursion(int depth) {
        return "def f(n):\n    if n < " + depth + ":\n        return f(n + 1)\n    return n\nx = f(1)\n";
    }

    // ------------------------------------------------------------------ what the scroll is

    @Test
    void itFitsAScrollAndStartsWithADescriptionLine() {
        assertTrue(HELP.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS, "length " + HELP.length());
        assertTrue(HELP.startsWith("# "), HELP.substring(0, 20));
        assertTrue(HELP.indexOf('\n') > 5);
    }

    @Test
    void itFitsTheEditorAndHoldsNoTab() {
        assertEquals(DroneControllerBlockEntity.MAX_SCRIPT_CHARS, PlanScriptWriter.MAX_SCRIPT_CHARS);
        assertTrue(HELP.length() <= DroneControllerBlockEntity.MAX_SCRIPT_CHARS);
        assertFalse(HELP.contains("\t"), "a tab in a scroll shows badly in the editor");
    }

    @Test
    void theFirstLineIsWhatTheScrollListShowsAsItsDescription() {
        String firstLine = HELP.substring(2, HELP.indexOf('\n'));
        assertEquals(firstLine, ScriptFileStore.describeScript(HELP, "fallback"));
        assertTrue(firstLine.startsWith("コマンド一覧(建設)"), firstLine);
    }

    @Test
    void everyConstructionCommandIsDocumented() {
        for (String name : CommandNames.PLAN) {
            assertTrue(HELP.contains(name + "("), name + "( is not documented");
        }
    }

    @Test
    void everyBuildingPartParameterIsNamed() {
        for (PartType t : BuildingParts.registry().all()) {
            for (ParamSpec p : t.params()) {
                assertTrue(HELP.contains(p.name()), t.id() + "." + p.name() + " is not in the help text");
            }
        }
    }

    @Test
    void itExplainsTheDifferencesFromFarmScripts() {
        for (String needle : List.of("畑", "random", "u=右", "w=前", "surface", "outer", "100,000", "5秒", "roof", "style(")) {
            assertTrue(HELP.contains(needle), "missing: " + needle);
        }
    }

    // ------------------------------------------------------------------ commands

    /** A name followed by "(" is a call; a choice listed as {@code kind=block/slab(block)} is not, so "/" and "=" before it excuse it. */
    private static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_/=])([a-z][a-z0-9_]*)\\(");

    @Test
    void everyCallTheHelpShowsIsAConstructionCommandOrAPureHelper() {
        Set<String> usable = new TreeSet<>(CommandNames.PLAN);
        usable.addAll(CommandNames.PLAN_HELPERS);
        Matcher m = CALL.matcher(HELP);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue(usable.contains(m.group(1)), m.group(1) + "( is shown but a construction script cannot call it");
        }
        assertTrue(seen > CommandNames.PLAN.size(), "the scan found too few calls: " + seen);
    }

    @Test
    void theHelpersAreListedExactlyAsTheProfileAllowsThem() {
        assertTrue(HELP.contains(String.join(" ", CommandNames.PLAN_HELPERS)), CommandNames.PLAN_HELPERS.toString());
    }

    @Test
    void theFarmCommandsItNamesAreRealFarmCommandsAndAreRefused() {
        for (String name : List.of("move", "harvest", "till", "plant", "random", "get_time", "create_task")) {
            assertTrue(CommandNames.ALL.contains(name) && !CommandNames.PLAN_HELPERS.contains(name),
                    name + " is no longer a farm-only command: fix the help");
            assertTrue(HELP.contains(name), name + " is not named as refused");
            assertClaim(failsAt("a construction script calling " + name, Stage.RUN, name + "()\n",
                    "E-SCRIPT-FORBIDDEN:#forbidden:1:1:" + name + ":0"));
        }
    }

    // ------------------------------------------------------------------ error codes

    private static final Pattern CODE = Pattern.compile("\\b[EW](?:-[A-Z]+)+\\b");

    @Test
    void everyErrorCodeTheHelpMentionsIsARealIssueCodeAndIsRaisedAsDescribed() {
        Set<String> mentioned = new TreeSet<>();
        Matcher m = CODE.matcher(HELP);
        while (m.find()) {
            mentioned.add(m.group());
        }
        for (String label : mentioned) {
            assertTrue(IssueCode.fromLabel(label).isPresent(), label + " is not an IssueCode");
        }
        Set<String> witnessed = new TreeSet<>();
        for (Claim c : claims()) {
            for (String id : c.ids()) {
                witnessed.add(id.substring(0, id.indexOf(':')));
            }
        }
        assertEquals(witnessed, mentioned, "the help mentions exactly the codes that some checked behavior raises");
    }

    // ------------------------------------------------------------------ numbers

    @Test
    void everyLimitTheHelpStatesIsTheCodesOwnNumber() {
        PlanRunLimits limits = PlanRunLimits.DEFAULT;
        assertEquals(0, limits.maxMillis() % 1000, "the help says whole seconds");
        Map<String, String> expected = new TreeMap<>();
        expected.put("steps", count(limits.maxSteps()) + "文");
        expected.put("seconds", limits.maxMillis() / 1000 + "秒");
        expected.put("script length", "長さは" + count(PlanScriptWriter.MAX_SCRIPT_CHARS) + "字まで");
        expected.put("string", "文字列は" + count(constant(Interpreter.class, "PLAN_MAX_STRING_CHARS")) + "字");
        expected.put("collection", "リスト・辞書・集合は" + count(constant(Interpreter.class, "PLAN_MAX_COLLECTION_ELEMENTS")) + "個");
        expected.put("recorded plan", "要素" + count(constant(PlanRecorder.class, "MAX_RECORDED_ELEMENTS")) + "個・文字"
                + count(constant(PlanRecorder.class, "MAX_RECORDED_CHARS")) + "字");
        expected.put("printed", "合計" + count(constant(PlanRecorder.class, "MAX_PRINTED_CHARS")) + "字");
        expected.put("block nesting", "入れ子が" + PlanScriptRunner.PLAN_MAX_PARSE_NESTING + "段を超える");
        expected.put("syntax depth", "構文の深さが" + PlanScriptRunner.PLAN_MAX_AST_DEPTH + "を超える");
        expected.put("call depth", "関数の呼び出しの入れ子が" + constant(Interpreter.class, "MAX_CALL_DEPTH") + "段を超える");
        expected.put("coordinates", "±" + count(PlanPatcher.MAX_COORD));
        expected.put("id length", PlanIds.MAX_LENGTH + "字まで(E-ID-INVALID)");
        expected.put("sign lines", BuildingParts.SIGN_MAX_LINES + "行まで");
        expected.put("sign line chars", BuildingParts.SIGN_MAX_LINE_CHARS + "字まで");
        expected.forEach((what, text) -> assertTrue(HELP.contains(text), what + ": the help does not say \"" + text + "\""));
    }

    // ------------------------------------------------------------------ the parts

    private static final Pattern PARAM_NAME = Pattern.compile("(?<![A-Za-z0-9_])([a-z][a-z0-9_]*)=");

    /** How the help writes one parameter: its range or choices after "=", and in () the value used when it is left out. */
    private static String describe(ParamSpec p) {
        String dflt = p.required() ? "必須" : String.valueOf(p.defaultValue().toTree());
        return switch (p.type()) {
            case INT -> p.name() + "=" + p.min().toTree() + "〜" + p.max().toTree() + "(" + dflt + ")";
            case BOOL -> p.name() + "=True/False(" + (Boolean.TRUE.equals(p.defaultValue().toTree()) ? "True" : "False") + ")";
            case ENUM -> p.name() + "=" + String.join("/", p.enumValues()) + "(" + dflt + ")";
            case MATERIAL -> p.name() + "=素材(" + dflt + ")";
            case STR -> p.name() + "=文字列(" + dflt + ")";
            case INT_LIST -> p.name() + "=整数のリスト(" + p.min().toTree() + "〜" + p.max().toTree() + "、" + p.maxItems() + "個まで)";
            case NUM -> throw new AssertionError(p.name() + " is a NUM parameter: teach the help and this test how to show it");
        };
    }

    /** The help's entry of a building part: its line and the indented lines after it. */
    private static String entryOf(String part) {
        List<String> lines = HELP.lines().toList();
        String start = part + PART_ENTRY_MARK;
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(start)) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "the help has no entry \"" + start + "\"");
        StringBuilder entry = new StringBuilder(lines.get(at));
        for (int j = at + 1; j < lines.size() && lines.get(j).startsWith(" "); j++) {
            entry.append('\n').append(lines.get(j));
        }
        return entry.toString();
    }

    @Test
    void everyBuildingPartHasOneEntryWithExactlyItsParametersRangesAndDefaults() {
        for (PartType t : BuildingParts.registry().all()) {
            String entry = entryOf(partName(t));
            List<String> shown = new ArrayList<>();
            Matcher m = PARAM_NAME.matcher(entry);
            while (m.find()) {
                shown.add(m.group(1));
            }
            assertEquals(t.params().stream().map(ParamSpec::name).toList(), shown, t.id() + ": the entry shows other parameters");
            for (ParamSpec p : t.params()) {
                assertTrue(entry.contains(describe(p)), t.id() + " must show \"" + describe(p) + "\" but has: " + entry);
            }
        }
    }

    @Test
    void everyBuildingPartIsListedOnceAndNothingElseIsListedAsAPart() {
        List<String> shown = HELP.lines().filter(l -> l.contains(PART_ENTRY_MARK) && PARAM_NAME.matcher(l).find())
                .map(l -> l.substring(0, l.indexOf('('))).toList();
        assertEquals(new TreeSet<>(BuildingParts.registry().all().stream().map(BuildCommandsHelpTest::partName).toList()),
                new TreeSet<>(shown));
        assertEquals(shown.size(), new TreeSet<>(shown).size(), "a part is listed twice: " + shown);
    }

    @Test
    void theRolesListedAreTheDefaultPaletteRoles() {
        assertTrue(HELP.contains("役割: " + String.join(" ", BuildingParts.DEFAULT_PALETTE.keySet())),
                BuildingParts.DEFAULT_PALETTE.keySet().toString());
    }

    @Test
    void thePartsThatCannotBeTurnedAreTheOnesTheCodeRefuses() {
        String names = BuildingParts.ROTATION_UNSUPPORTED.stream().map(id -> id.substring(BuildingParts.ID_PREFIX.length()))
                .collect(Collectors.joining(" "));
        String heading = "回転数・鏡像を付けられない部品(付けると E-ANCHOR):";
        assertTrue(HELP.contains(heading), heading);
        int namesAt = HELP.indexOf(names);
        assertTrue(namesAt > HELP.indexOf(heading) && HELP.indexOf('\n', HELP.indexOf(heading)) < namesAt
                && HELP.indexOf('\n', namesAt) - namesAt == names.length(), "the names must follow the heading on a line of their own: " + names);
    }

    @Test
    void theBuildingPartsHaveNoPortsAndNoMachinePartIsRegisteredYet() {
        for (PartType t : BuildingParts.registry().all()) {
            assertTrue(t.id().startsWith(BuildingParts.ID_PREFIX), t.id() + " is not a building part: the help says create: and mod: are not registered");
            assertTrue(t.ports().isEmpty(), t.id() + " has ports: the help says the building parts have none");
        }
    }

    // ------------------------------------------------------------------ the example

    /** The example script, cut out of the help itself: from its header to the next section. */
    private static String exampleScript() {
        int header = HELP.indexOf(EXAMPLE_HEADER);
        assertTrue(header >= 0, "the help has no example section");
        int from = header + EXAMPLE_HEADER.length();
        int next = HELP.indexOf("\n" + SECTION_MARK, from);
        return (next < 0 ? HELP.substring(from) : HELP.substring(from, next)).strip() + "\n";
    }

    /** The script through every stage with the real building parts; each stage must succeed. */
    private static CompileResult compiled(String script) {
        PartTypeRegistry registry = BuildingParts.registry();
        PlanScriptRunner.Result run = PlanScriptRunner.run(List.of(script), PATCH_ID, BASE_REVISION, STAGE_ID, PlanRunLimits.DEFAULT);
        assertTrue(run.ok(), run.issues().toString());
        PatchResult patched = new PlanPatcher(registry, TemplateBundle.EMPTY).apply(SemanticPlan.empty(PLAN_ID), run.patch());
        assertTrue(patched.ok(), patched.issues().toString());
        ExpandResult expanded = new PlanExpander(registry, SlotResolver.NONE).expand(patched.plan(), TemplateBundle.EMPTY, Router.NONE);
        assertNotNull(expanded.plan(), expanded.issues().toString());
        CompileResult result = new PlanCompiler().compile(expanded.plan(), registry, PlaceableBlockPolicy.builtin(), SURVEY);
        assertNotNull(result.manifest(), result.issues().toString());
        return result;
    }

    @Test
    void theExampleRunsPatchesExpandsAndCompilesToTheHandDerivedHut() {
        String script = exampleScript();
        assertTrue(script.startsWith("site("), script);
        CompileResult compiled = compiled(script);
        assertTrue(compiled.issues().isEmpty(), compiled.issues().toString());

        // Hand-derived (7x7 footprint, 1 floor, floor height 4, roof overhang 0):
        //   floor 49 oak_planks;
        //   walls: 24 cells around x 3 rows = 72 stone_bricks, minus the door hole 2 and two windows 2 x 2 = 66;
        //   door 2 blocks (1 item), 2 panes x 2 = 4 glass_pane;
        //   gable roof over 7 wide: 3 rows x 2 sides x 7 long = 42 red_nether_brick_stairs, a ridge of 7 red_nether_bricks,
        //   and the two gable ends filled with the wall block: 2 x (5 + 3 + 1) = 18 stone_bricks.
        // Blocks: 49 + 66 + 2 + 4 + 42 + 7 + 18 = 188.
        assertEquals(188, compiled.manifest().placements().size());
        assertEquals(Map.of("minecraft:oak_planks", 49, "minecraft:stone_bricks", 84, "minecraft:oak_door", 1,
                "minecraft:glass_pane", 4, "minecraft:red_nether_brick_stairs", 42, "minecraft:red_nether_bricks", 7),
                compiled.manifest().bom());
    }

    // ------------------------------------------------------------------ the behaviors the help describes

    private static List<Claim> claims() {
        int stringDoublings = doublingsPastCap(constant(Interpreter.class, "PLAN_MAX_STRING_CHARS"));
        int listDoublings = doublingsPastCap(constant(Interpreter.class, "PLAN_MAX_COLLECTION_ELEMENTS"));
        // one print of 2^k characters fits under the printed-characters cap, two of them do not
        int printDoublings = doublingsPastCap(constant(PlanRecorder.class, "MAX_PRINTED_CHARS")) - 1;
        int nesting = PlanScriptRunner.PLAN_MAX_PARSE_NESTING;
        int astDepth = PlanScriptRunner.PLAN_MAX_AST_DEPTH;
        int calls = (int) constant(Interpreter.class, "MAX_CALL_DEPTH");
        int maxChars = PlanScriptWriter.MAX_SCRIPT_CHARS;
        String longId = "a".repeat(PlanIds.MAX_LENGTH);
        String tooLongId = longId + "a";
        String twoDocks = "logistics([{\"id\": \"d1\", \"pad\": [0, 0, 0, 4, 0, 4], \"clearance\": [0, 1, 0, 4, 8, 4], \"approach\": \"north\"}, "
                + "{\"id\": \"d2\", \"pad\": [6, 0, 0, 10, 0, 4], \"clearance\": [6, 1, 0, 10, 8, 4], \"approach\": \"east\", "
                + "\"ports\": [\"abc.x.y\"], \"connectors\": []}], [{\"id\": \"r1\", \"from\": \"d1\", \"to\": \"d2\", "
                + "\"waypoints\": [[0, 10, 0]], \"airship\": \"zeppelin\"}], "
                + "[{\"item\": \"minecraft:iron_ingot\", \"per_min\": 12, \"from\": \"d1\", \"to\": \"d2\"}])\n";
        String machines = "part(\"m1\", \"test:motor\", None, [0, 0, 0], {})\npart(\"p1\", \"test:press\", None, [5, 0, 0], {})\n";
        String viaShaft = "part(\"m1\", \"test:motor\", None, [0, 0, 0], {})\npart(\"s1\", \"test:shaft\", None, [2, 0, 0], {})\n"
                + "part(\"p1\", \"test:press\", None, [5, 0, 0], {})\n";
        String link = "connect(\"c1\", \"m1.out\", \"p1.power_in\", \"rotation\"";
        List<Claim> all = new ArrayList<>();

        // --- how a script is written
        all.add(fails("a call may not span lines", "mood(\n\"a\")\n", SYNTAX_ERROR));
        all.add(fails("a list may not span lines", "x = [1,\n 2]\n", SYNTAX_ERROR));
        all.add(fails("a dict may not span lines", "x = {\"a\": 1,\n \"b\": 2}\n", SYNTAX_ERROR));
        all.add(fails("no trailing comma in a list", "x = [1, 2,]\n", SYNTAX_ERROR));
        all.add(fails("no trailing comma in a call", "mood(\"a\",)\n", SYNTAX_ERROR));
        all.add(fails("no trailing comma in a dict", "x = {\"a\": 1,}\n", SYNTAX_ERROR));
        all.add(fails("a tab does not indent", "for i in range(2):\n\tmood(\"a\")\n", SYNTAX_ERROR));
        all.add(failsWith("a full-width digit is refused and the message names the line", "mood(\"a\")\nx = \uFF11\uFF12\n",
                "2行目に半角でない数字があります", SYNTAX_ERROR));
        all.add(works("full-width digits are fine in a string, a comment and a name", Stage.PATCH,
                "mood(\"\uFF11\uFF12\")\n# \uFF11\uFF12\n\u58C1\uFF11 = 3\nmood(str(\u58C1\uFF11))\n"));
        all.add(fails("a full-width space outside a string is refused", "mood(\"a\")\u3000\n", SYNTAX_ERROR));
        all.add(fails("a full-width parenthesis outside a string is refused", "mood\uFF08\"a\"\uFF09\n", SYNTAX_ERROR));
        all.add(works("both quotes and the five escapes work", Stage.PATCH,
                "mood(\"\\\\ \\\" \\' \\n \\t\")\nmood('x')\nmood('\\'')\n"));
        all.add(works("comments, elif/else, while/break/continue, def/return and methods work", Stage.PATCH,
                "# note\nl = []\nl.append(1)\nd = {}\nd[\"a\"] = l.pop()\nk = d.get(\"a\")\ni = 0\nwhile i < 10:\n    i = i + 1\n"
                        + "    if i == 2:\n        continue\n    elif i == 5:\n        break\n    else:\n        pass\n"
                        + "def add(a, b):\n    return a + b\nx = add(1, 2) % 2 + len(l) + abs(-1) + min(1, 2) + max(3, 4)\n"
                        + "mood(str(x))\nprint(x)\ns = set([1])\nq = list(s)\nfor c in \"ab\":\n    pass\nfor j in range(2):\n    pass\n"));
        all.add(fails("range only works in a for loop", "x = range(3)\n", RUN_ERROR));
        all.add(fails("a def may not sit inside a def", "def f():\n    def g():\n        pass\n", SYNTAX_ERROR));
        all.add(fails("a function must be defined before it is called", "f()\ndef f():\n    pass\n", RUN_ERROR));
        all.add(fails("a function may not be named like a command", "def wall():\n    pass\n",
                "E-SCRIPT-FORBIDDEN:#forbidden:1:1:wall:0"));
        all.add(fails("a farm command may not be mixed in", "mood(\"a\")\nharvest()\n", "E-SCRIPT-FORBIDDEN:#forbidden:1:2:harvest:0"));
        all.add(fails("an unknown command is refused like a forbidden one", "foo()\n", "E-SCRIPT-FORBIDDEN:#forbidden:1:1:foo:0"));
        all.add(fails("a number needed as an integer may not be a fraction",
                HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"thickness\": 3 / 2})\n", "E-PARAM-RANGE:w#thickness"));
        all.add(works("a division that comes out whole is an integer", Stage.COMPILE,
                HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"thickness\": 2 / 2})\n"));

        // --- the limits
        all.add(failsWith("a run past the step limit", "while True:\n    pass\n",
                "exceeded " + PlanRunLimits.DEFAULT.maxSteps() + " steps", RUN_LIMIT));
        all.add(works("a script of exactly the maximum length runs", Stage.PATCH, "#" + "x".repeat(maxChars - 2) + "\n"));
        all.add(fails("a script one character too long", "#" + "x".repeat(maxChars - 1) + "\n", "E-SCRIPT-LIMIT:#length:1"));
        all.add(works("a string at the cap is fine", Stage.PATCH, doubled(stringDoublings - 1)));
        all.add(fails("a string past the cap", doubled(stringDoublings), RUN_LIMIT));
        all.add(works("a list at the cap is fine", Stage.PATCH, doubled(listDoublings - 1) + "l = list(s)\n"));
        all.add(fails("a list past the cap", doubled(listDoublings) + "l = list(s)\n", RUN_LIMIT));
        all.add(works("one print of half the cap is fine and builds nothing", Stage.PATCH, doubled(printDoublings) + "print(s)\n"));
        all.add(fails("prints past the total cap", doubled(printDoublings) + "print(s)\nprint(s)\n", RUN_LIMIT));
        all.add(works("nesting at the limit", Stage.PATCH, nestedIfs(nesting)));
        all.add(fails("nesting past the limit", nestedIfs(nesting + 1), SYNTAX_ERROR));
        all.add(works("a chain of terms just under the depth limit", Stage.PATCH, "x = " + "1+".repeat(astDepth - 2) + "1\n"));
        all.add(fails("a chain of terms at the depth limit", "x = " + "1+".repeat(astDepth - 1) + "1\n", SYNTAX_ERROR));
        all.add(works("calls nested at the limit", Stage.PATCH, recursion(calls)));
        all.add(fails("calls nested past the limit", recursion(calls + 1), RUN_ERROR));

        // --- ids, names, labels
        all.add(fails("an id with a capital", HOUSE + "wall(\"Wall\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n", "E-ID-INVALID:Wall"));
        all.add(fails("an id with an underscore", HOUSE + "wall(\"w_1\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n", "E-ID-INVALID:w_1"));
        all.add(works("an id of the maximum length", Stage.COMPILE,
                HOUSE + "wall(\"" + longId + "\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n"));
        all.add(fails("an id one character too long",
                HOUSE + "wall(\"" + tooLongId + "\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n", "E-ID-INVALID:" + tooLongId));
        all.add(fails("the same id twice", HOUSE_WITH_WALL + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"south\"})\n", "E-ID-DUPLICATE:w"));
        all.add(fails("a control character in a tag", HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\"}, [\"a\u0001b\"])\n",
                "E-PARAM-RANGE:w#label"));
        all.add(works("newline and tab are fine in a label and a tag", Stage.COMPILE,
                HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\"}, [\"a\\nb\\tc\"], \"la\\nbel\")\n"));
        all.add(failsAt("a port with an empty half", Stage.RUN, HOUSE + "connect(\"c\", \".a\", \"s.b\", \"item\")\n", RUN_ERROR));
        all.add(failsAt("a port without a dot", Stage.RUN, HOUSE + "connect(\"c\", \"wa\", \"s.b\", \"item\")\n", RUN_ERROR));
        all.add(failsAt("a dock port with an empty half", Stage.RUN, HOUSE
                + "logistics([{\"id\": \"d1\", \"pad\": [0, 0, 0, 4, 0, 4], \"clearance\": [0, 1, 0, 4, 8, 4], \"approach\": \"north\", "
                + "\"ports\": [\".x\"]}], [], [])\n", RUN_ERROR));
        all.add(failsAt("a dock port whose node id is not an id", Stage.PATCH, HOUSE
                + "logistics([{\"id\": \"d1\", \"pad\": [0, 0, 0, 4, 0, 4], \"clearance\": [0, 1, 0, 4, 8, 4], \"approach\": \"north\", "
                + "\"ports\": [\"Abc.x\"]}], [], [])\n", "E-CONN-INVALID:d1#port:0"));

        // --- site, style, mood
        all.add(fails("a site in capitals is refused", "site(\"minecraft:overworld\", 0, 64, 0, \"North\", [0, 0, 0, 1, 1, 1])\n",
                RUN_ERROR));
        all.add(fails("bounds with min above max", "site(\"minecraft:overworld\", 0, 64, 0, \"north\", [5, 0, 0, 1, 1, 1])\n",
                RUN_ERROR));
        all.add(fails("a dimension without a namespace", "site(\"overworld\", 0, 64, 0, \"north\", [0, 0, 0, 1, 1, 1])\n",
                "E-PARAM-RANGE:site#site"));
        all.add(works("a site with the optional digest and claim", Stage.COMPILE,
                "site(\"minecraft:overworld\", 0, 64, 0, \"north\", [0, 0, 0, 1, 1, 1], \"digest\", \"claim\")\n"));
        all.add(fails("no site", "structure(\"s\", None, [0, 0, 0], {})\n", "E-SITE-MISSING:"));
        all.add(fails("a role in capitals", "style(\"Roof\", \"minecraft:stone\")\n", "E-PARAM-RANGE:Roof#style"));
        all.add(fails("a material without a namespace", "style(\"roof\", \"stone\")\n", "E-PARAM-RANGE:roof#style"));
        all.add(fails("a block that must never be placed",
                SITE + "style(\"wall\", \"minecraft:command_block\")\nstructure(\"s\", None, [0, 0, 0], {})\n" + NORTH_WALL,
                "E-BLOCK-FORBIDDEN:w#minecraft:command_block"));
        all.add(fails("a block outside the allow list",
                SITE + "style(\"wall\", \"minecraft:tnt\")\nstructure(\"s\", None, [0, 0, 0], {})\n" + NORTH_WALL,
                "E-BLOCK-FORBIDDEN:w#minecraft:tnt"));
        all.add(fails("a material with no stairs form for a roof", HOUSE + "style(\"roof\", \"minecraft:red_terracotta\")\n"
                + "roof(\"r\", \"s\", [0, 0, 0], {})\n", "E-PARAM-RANGE:r#material"));
        all.add(works("a role's stairs and slab can be set apart", Stage.COMPILE, HOUSE
                + "style(\"roof_stairs\", \"minecraft:brick_stairs\")\nstyle(\"roof_slab\", \"minecraft:brick_slab\")\n"
                + "roof(\"r\", \"s\", [0, 0, 0], {})\n"));
        all.add(fails("a material naming a role nobody defined", HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"material\": \"nonexistent\"})\n",
                "E-PARAM-RANGE:w#material"));
        all.add(works("mood is only recorded", Stage.COMPILE, HOUSE + "mood(\"cozy\")\nmood(\"cozy\")\n"));
        all.add(fails("a part outside the bounds", "site(\"minecraft:overworld\", 0, 64, 0, \"north\", [0, 0, 0, 3, 3, 3])\n"
                + "structure(\"s\", None, [0, 0, 0], {})\n" + NORTH_WALL, "E-OUT-OF-BOUNDS:w"));
        all.add(fails("two parts on one spot", HOUSE + "floor(\"f1\", \"s\", [0, 0, 0], {})\nfloor(\"f2\", \"s\", [0, 0, 0], {})\n",
                "E-OVERLAP:f1,f2"));

        // --- placing parts
        all.add(fails("a parent that is not placed", HOUSE + "wall(\"w1\", \"nope\", [0, 0, 0], {\"side\": \"north\"})\n",
                "E-ANCHOR:w1#parent"));
        all.add(fails("a parent placed later", HOUSE + "wall(\"w1\", \"s2\", [0, 0, 0], {\"side\": \"north\"})\n"
                + "structure(\"s2\", None, [0, 0, 0], {})\n", "E-ANCHOR:w1#parent"));
        all.add(fails("a wall whose parent is not a structure", HOUSE + "pillar(\"p\", \"s\", [0, 0, 0], {})\n"
                + "wall(\"w\", \"p\", [0, 0, 0], {\"side\": \"north\"})\n", "E-ANCHOR:w#parent"));
        all.add(fails("an unknown part type", HOUSE + "part(\"x1\", \"create:mechanical_press\", None, [0, 0, 0], {})\n",
                "E-UNKNOWN-PART:x1"));
        all.add(fails("a machine template that is not registered", HOUSE + "part(\"m\", \"mod:press_station\", None, [0, 0, 0], {})\n",
                "E-UNKNOWN-PART:m"));
        all.add(works("part() with the whole part id", Stage.COMPILE, HOUSE
                + "part(\"w\", \"micra:wall\", \"s\", [0, 0, 0], {\"side\": \"north\"}, [\"tag\"], \"a label\")\n"));
        all.add(failsAt("a dict as a parameter value", Stage.RUN, "structure(\"s\", None, [0, 0, 0], {\"width\": {\"a\": 1}})\n",
                RUN_ERROR));
        all.add(works("a True/False parameter", Stage.COMPILE, HOUSE + "pillar(\"p\", \"s\", [0, 0, 0], {\"base\": False, \"capital\": True})\n"));
        all.add(fails("a parameter that does not exist", HOUSE + "wall(\"w1\", \"s\", [0, 0, 0], {\"side\": \"north\", \"colour\": 1})\n",
                "E-PARAM-RANGE:w1#colour"));
        all.add(fails("a required parameter left out", HOUSE + "wall(\"w1\", \"s\", [0, 0, 0], {})\n", "E-PARAM-RANGE:w1#side"));
        all.add(fails("a parameter out of its range", HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"thickness\": 9})\n",
                "E-PARAM-RANGE:w#thickness"));
        all.add(fails("a level the building does not have", HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"level\": 1})\n",
                "E-PARAM-RANGE:w#level"));
        all.add(fails("a floor on a level the building does not have", HOUSE + "floor(\"f\", \"s\", [0, 0, 0], {\"level\": 1})\n",
                "E-PARAM-RANGE:f#level"));
        all.add(fails("a sign of five lines", HOUSE_WITH_WALL
                + "sign(\"sg\", \"s\", [\"surface\", \"w\", \"outer\", 1, 1], {\"text\": \"a|b|c|d|e\"})\n", "E-PARAM-RANGE:sg#text"));
        all.add(fails("a sign line of sixteen characters", HOUSE_WITH_WALL
                + "sign(\"sg\", \"s\", [\"surface\", \"w\", \"outer\", 1, 1], {\"text\": \"0123456789abcdef\"})\n", "E-PARAM-RANGE:sg#text"));
        all.add(works("a sign of four lines of fifteen characters", Stage.COMPILE, HOUSE_WITH_WALL
                + "sign(\"sg\", \"s\", [\"surface\", \"w\", \"outer\", 1, 1], "
                + "{\"text\": \"0123456789abcde|0123456789abcde|0123456789abcde|0123456789abcde\"})\n"));
        all.add(fails("a sign without its text", HOUSE_WITH_WALL + "sign(\"sg\", \"s\", [\"surface\", \"w\", \"outer\", 1, 1], {})\n",
                "E-PARAM-RANGE:sg#text"));
        all.add(fails("floor holes that are not groups of four", HOUSE + "floor(\"f\", \"s\", [0, 0, 0], {\"holes\": [1, 1, 2]})\n",
                "E-PARAM-RANGE:f#holes"));
        all.add(works("floor holes in groups of four", Stage.COMPILE, HOUSE + "floor(\"f\", \"s\", [0, 0, 0], {\"holes\": [1, 1, 2, 2]})\n"));
        all.add(fails("a coordinate past the limit", HOUSE + "pillar(\"p1\", \"s\", [30000001, 1, 1], {})\n", "E-PARAM-RANGE:p1#anchor"));
        all.add(works("a coordinate at the limit", Stage.PATCH, HOUSE + "pillar(\"p1\", \"s\", [30000000, 1, 1], {})\n"));
        all.add(works("a pillar may be turned", Stage.COMPILE, HOUSE + "pillar(\"p1\", \"s\", [1, 1, 1, 3, True], {})\n"));
        all.add(fails("turns past three", HOUSE + "pillar(\"p1\", \"s\", [1, 1, 1, 4, False], {})\n", RUN_ERROR));
        all.add(fails("a mirror that is not True or False", HOUSE + "pillar(\"p1\", \"s\", [1, 1, 1, 1, 1], {})\n", RUN_ERROR));
        all.add(fails("a wall may not be turned", HOUSE + "wall(\"w1\", \"s\", [0, 0, 0, 1, False], {\"side\": \"north\"})\n", "E-ANCHOR:w1#rot"));
        all.add(fails("a door with a position instead of a wall face", HOUSE_WITH_WALL + "door(\"d\", \"s\", [1, 1, 1], {})\n",
                "E-ANCHOR:d#anchor"));
        all.add(fails("a door beyond the end of its wall", HOUSE_WITH_WALL
                + "door(\"d\", \"s\", [\"surface\", \"w\", \"outer\", 30, 0], {})\n", "E-OPENING-NO-WALL:d#anchor"));
        all.add(fails("a balcony on the inside", HOUSE_WITH_WALL + "balcony(\"b\", \"s\", [\"surface\", \"w\", \"inner\", 1, 1], {})\n",
                "E-ANCHOR:b#anchor"));
        all.add(fails("a planter on the inside", HOUSE_WITH_WALL + "planter(\"p\", \"s\", [\"surface\", \"w\", \"inner\", 1, 1], {})\n",
                "E-ANCHOR:p#anchor"));
        all.add(fails("a door on something that is not a wall", HOUSE + "door(\"d\", \"s\", [\"surface\", \"s\", \"outer\", 1, 0], {})\n",
                "E-ANCHOR:d#anchor"));
        all.add(fails("a part on its own face", HOUSE_WITH_WALL + "relocate(\"w\", [\"surface\", \"w\", \"outer\", 0, 0])\n", "E-ANCHOR:w#anchor"));
        all.add(failsAt("a slot before the building analysis exists", Stage.EXPAND,
                HOUSE + "wall(\"w1\", None, [\"slot\", \"x\"], {\"side\": \"north\"})\n", "E-ANCHOR:w1#slot"));

        // --- changing and removing parts
        all.add(works("a part moved onto a wall placed after it", Stage.COMPILE, HOUSE
                + "sign(\"sg\", \"s\", [0, 0, 0], {\"text\": \"hi\"})\n" + "wall(\"w9\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n"
                + "relocate(\"sg\", [\"surface\", \"w9\", \"outer\", 1, 1])\n"));
        all.add(fails("a move that makes parents and wall faces a loop", HOUSE_WITH_WALL
                + "door(\"d\", \"s\", [\"surface\", \"w\", \"outer\", 1, 0], {})\nwall(\"w2\", \"d\", [0, 0, 0], {\"side\": \"south\"})\n"
                + "relocate(\"w\", [\"surface\", \"w2\", \"outer\", 0, 0])\n", "E-ANCHOR:w#cycle"));
        all.add(fails("removing a building that still has a wall", HOUSE_WITH_WALL + "remove_part(\"s\")\n", "E-ANCHOR:s#remove"));
        all.add(fails("removing a wall that a door rests on", HOUSE_WITH_WALL
                + "door(\"d\", \"s\", [\"surface\", \"w\", \"outer\", 1, 0], {})\nremove_part(\"w\")\n", "E-ANCHOR:w#remove"));
        all.add(onMachines("a part a dock uses cannot be removed", Stage.PATCH, machines
                + "logistics([{\"id\": \"d1\", \"pad\": [0, 0, 0, 4, 0, 4], \"clearance\": [0, 1, 0, 4, 8, 4], \"approach\": \"north\", "
                + "\"ports\": [\"m1.out\"], \"connectors\": []}], [], [])\nremove_part(\"m1\")\n", "E-ANCHOR:m1#remove"));
        all.add(works("removing a part nothing depends on", Stage.COMPILE, HOUSE_WITH_WALL + "remove_part(\"w\")\n"));
        all.add(fails("changing a part that is not there", "update_params(\"zz\", {})\n", "E-ANCHOR:zz#node"));
        all.add(fails("moving a part that is not there", "relocate(\"zz\", [0, 0, 0])\n", "E-ANCHOR:zz#node"));
        all.add(fails("removing a part that is not there", "remove_part(\"zz\")\n", "E-ANCHOR:zz#node"));
        all.add(fails("update_params out of range", HOUSE_WITH_WALL + "update_params(\"w\", {\"thickness\": 9})\n", "E-PARAM-RANGE:w#thickness"));

        // --- connections and logistics
        all.add(failsAt("the building parts have no ports to connect", Stage.PATCH,
                HOUSE_WITH_WALL + "connect(\"c\", \"w.a\", \"s.b\", \"item\")\n", "E-CONN-INVALID:c#w.a", "E-CONN-INVALID:c#s.b"));
        all.add(failsAt("a connection kind that does not exist", Stage.RUN, HOUSE + "connect(\"c\", \"w.a\", \"s.b\", \"belt\")\n",
                RUN_ERROR));
        all.add(onMachines("no via means automatic routing, which has no router yet", Stage.EXPAND,
                machines + link + ")\n", "E-NO-ROUTE:c1"));
        all.add(onMachines("via None is the same as no via", Stage.EXPAND, machines + link + ", None)\n", "E-NO-ROUTE:c1"));
        all.add(onMachines("an empty via connects directly", Stage.EXPAND, machines + link + ", [])\n"));
        all.add(onMachines("a via list goes through those parts", Stage.EXPAND, viaShaft + link + ", [\"s1\"])\n"));
        all.add(onMachines("a via part that is not there", Stage.PATCH, machines + link + ", [\"zz\"])\n", "E-CONN-INVALID:c1#via:zz"));
        all.add(onMachines("constraints with the four keys", Stage.EXPAND, machines + link
                + ", [], {\"max_length\": 20, \"avoid\": [\"m1\"], \"max_turns\": 3, \"entry_dirs\": [\"up\"]})\n"));
        all.add(onMachines("constraints with another key", Stage.RUN, machines + link + ", [], {\"speed\": 1})\n", RUN_ERROR));
        all.add(onMachines("a connection id used twice", Stage.PATCH, machines + link + ", [])\n" + link + ", [])\n", "E-ID-DUPLICATE:c1"));
        all.add(onMachines("a connection id with a capital", Stage.PATCH, machines
                + "connect(\"C1\", \"m1.out\", \"p1.power_in\", \"rotation\", [])\n", "E-ID-INVALID:C1"));
        all.add(onMachines("a port name may hold a dot but must still exist", Stage.PATCH, machines
                + "connect(\"c1\", \"m1.out.x\", \"p1.power_in\", \"rotation\", [])\n", "E-CONN-INVALID:c1#m1.out.x"));
        all.add(onMachines("disconnect removes a connection", Stage.EXPAND, machines + link + ", [])\ndisconnect(\"c1\")\n"));
        all.add(fails("disconnecting one that is not there", "disconnect(\"c9\")\n", "E-CONN-INVALID:c9"));
        all.add(onMachines("a part a connection uses cannot be removed", Stage.PATCH, machines + link + ", [])\nremove_part(\"m1\")\n",
                "E-ANCHOR:m1#remove"));
        all.add(onMachines("a via part cannot be removed either", Stage.PATCH, viaShaft + link + ", [\"s1\"])\nremove_part(\"s1\")\n",
                "E-ANCHOR:s1#remove"));
        all.add(works("logistics with docks, a route and a flow", Stage.COMPILE, HOUSE + twoDocks));
        all.add(fails("a route to a dock that does not exist", HOUSE + "logistics([], [{\"id\": \"r1\", \"from\": \"d1\", \"to\": \"d2\"}], [])\n",
                "E-CONN-INVALID:r1#dock:d1", "E-CONN-INVALID:r1#dock:d2"));
        all.add(failsAt("a flow whose per_min is not a number", Stage.RUN,
                HOUSE + "logistics([], [], [{\"item\": \"minecraft:iron_ingot\", \"per_min\": \"a\", \"from\": \"d1\", \"to\": \"d2\"}])\n",
                RUN_ERROR));
        all.add(failsAt("a flow whose per_min is not finite", Stage.RUN,
                HOUSE + "logistics([], [], [{\"item\": \"minecraft:iron_ingot\", \"per_min\": " + "9".repeat(320)
                        + ", \"from\": \"d1\", \"to\": \"d2\"}])\n", RUN_ERROR));
        all.add(failsAt("a route with a key that does not exist", Stage.RUN,
                HOUSE + "logistics([], [{\"id\": \"r1\", \"from\": \"d1\", \"to\": \"d2\", \"speed\": 3}], [])\n", RUN_ERROR));
        return all;
    }

    @Test
    void everyBehaviorTheHelpDescribesHappensAsDescribed() {
        for (Claim c : claims()) {
            assertClaim(c);
        }
    }

    @Test
    void everyPartThatRestsOnAWallFaceRefusesAPositionInstead() {
        for (String part : List.of("door", "window", "sign", "planter", "trim", "balcony")) {
            String params = part.equals("sign") ? "{\"text\": \"hi\"}" : "{}";
            assertClaim(fails(part + " with a plain position", HOUSE_WITH_WALL + part + "(\"x\", \"s\", [1, 1, 1], " + params + ")\n",
                    "E-ANCHOR:x#anchor"));
        }
    }

    @Test
    void foundationFloorWallAndRoofNeedAStructureAsTheirParent() {
        for (String part : List.of("foundation", "floor", "wall", "roof")) {
            String params = part.equals("wall") ? "{\"side\": \"north\"}" : "{}";
            assertClaim(fails(part + " under a pillar", HOUSE + "pillar(\"p\", \"s\", [0, 0, 0], {})\n" + part + "(\"x\", \"p\", [0, 0, 0], "
                    + params + ")\n", "E-ANCHOR:x#parent"));
        }
    }

    @Test
    void aWallsDefaultsAndItsHalfFollowTheHelp() {
        // A 7x7 building, floor height 4: a wall's default height is 4 - 1 = 3 rows and its default length is its whole side (7).
        String house = HOUSE;
        assertEquals(7 * 3, compiled(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\"})\n").manifest().placements().size());
        assertEquals(7 * 2, compiled(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"height\": 2})\n")
                .manifest().placements().size());
        assertEquals(4 * 3, compiled(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"length\": 4})\n")
                .manifest().placements().size());
        // from = 2 leaves 7 - 2 = 5 cells to the end of the side
        assertEquals(5 * 3, compiled(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"from\": 2})\n")
                .manifest().placements().size());
        // half of a height of 5 is rounded up: 3 rows, not 2
        assertEquals(7 * 3, compiled(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"height\": 5, \"part\": \"half\"})\n")
                .manifest().placements().size());
    }

    @Test
    void aRoofsRidgeRunsAlongTheLongerSideWhenLeftOnAuto() {
        // 7 wide, 9 deep, overhang 0. Along w the gable is 7 across: 3 rows of stairs on both sides, 9 long = 54 stairs.
        // Along u it is 9 across: 4 rows on both sides, 7 long = 56 stairs. The longer side is the depth (w), so auto = w.
        String building = SITE + "structure(\"s\", None, [0, 0, 0], {\"width\": 7, \"depth\": 9})\n";
        Map<String, Integer> auto = compiled(building + "roof(\"r\", \"s\", [0, 0, 0], {\"overhang\": 0})\n").manifest().bom();
        Map<String, Integer> alongW = compiled(building + "roof(\"r\", \"s\", [0, 0, 0], {\"overhang\": 0, \"ridge\": \"w\"})\n")
                .manifest().bom();
        Map<String, Integer> alongU = compiled(building + "roof(\"r\", \"s\", [0, 0, 0], {\"overhang\": 0, \"ridge\": \"u\"})\n")
                .manifest().bom();
        assertEquals(54, alongW.get("minecraft:oak_stairs"));
        assertEquals(56, alongU.get("minecraft:oak_stairs"));
        assertEquals(alongW, auto);
    }

    // A site big enough that nothing below runs into its bounds: origin (0, 64, 0), facing as given.
    private static String wideSite(String facing) {
        return "site(\"minecraft:overworld\", 0, 64, 0, \"" + facing + "\", [-20, -20, -20, 20, 20, 20])\n";
    }

    private static Map<String, String> blocksByWorldPosition(String script) {
        Map<String, String> out = new TreeMap<>();
        for (Placement p : compiled(script).manifest().placements()) {
            out.put(p.pos().x() + "," + p.pos().y() + "," + p.pos().z(), p.block().blockId());
        }
        return out;
    }

    @Test
    void positionsCountFromTheParentOrTheSiteOriginAndTheSitesFacingTurnsTheBuilding() {
        String one = "{\"height\": 1}";
        // u is to the right and w to the front; facing north, front is world north (-z) and right is east (+x).
        assertEquals(Map.of("3,65,-3", "minecraft:stone_bricks", "3,66,-3", "minecraft:stone_bricks"),
                blocksByWorldPosition(wideSite("north") + "pillar(\"p\", None, [3, 1, 3], {\"height\": 2})\n"),
                "no parent: relative to the site origin (0, 64, 0)");
        assertEquals(Map.of("13,65,-3", "minecraft:stone_bricks"),
                blocksByWorldPosition(wideSite("north") + "structure(\"s\", None, [10, 0, 0], {})\n"
                        + "pillar(\"p\", \"s\", [3, 1, 3], " + one + ")\n"),
                "a parent: relative to the parent's origin, which is (10, 0, 0) from the site origin");
        // facing east the front is world east (+x) and the right is world south (+z)
        assertEquals(Map.of("3,64,0", "minecraft:stone_bricks"),
                blocksByWorldPosition(wideSite("east") + "pillar(\"p\", None, [0, 0, 3], " + one + ")\n"));
        assertEquals(Map.of("0,64,2", "minecraft:stone_bricks"),
                blocksByWorldPosition(wideSite("east") + "pillar(\"p\", None, [2, 0, 0], " + one + ")\n"));
    }

    @Test
    void aWallSitsOnTheSideItsNameSaysInAHouseFacingNorth() {
        // 7x7 house at the origin: north is the front edge (w = 6, z = -6), south the back edge (w = 0, z = 0),
        // east the right edge (u = 6, x = 6), west the left edge (u = 0, x = 0).
        String house = wideSite("north") + "structure(\"s\", None, [0, 0, 0], {})\n";
        Map<String, java.util.function.Predicate<String[]>> sides = new TreeMap<>();
        sides.put("north", xyz -> xyz[2].equals("-6"));
        sides.put("south", xyz -> xyz[2].equals("0"));
        sides.put("east", xyz -> xyz[0].equals("6"));
        sides.put("west", xyz -> xyz[0].equals("0"));
        sides.forEach((side, onThatEdge) -> {
            Map<String, String> cells = blocksByWorldPosition(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"" + side + "\"})\n");
            assertEquals(21, cells.size(), side);
            assertTrue(cells.keySet().stream().allMatch(k -> onThatEdge.test(k.split(","))), side + ": " + cells.keySet());
        });
    }

    @Test
    void aWallFacePositionCountsAlongTheWallAndUpFromItsLowestRow() {
        String house = wideSite("north") + "structure(\"s\", None, [0, 0, 0], {})\n";
        // South wall (w = 0, z = 0) runs to the right: u = 2 is x = 2. Its lowest row is one above the floor (y = 65).
        Map<String, String> door = blocksByWorldPosition(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"south\"})\n"
                + "door(\"d\", \"s\", [\"surface\", \"w\", \"outer\", 2, 0], {})\n");
        assertEquals("minecraft:oak_door", door.get("2,65,0"));
        assertEquals("minecraft:oak_door", door.get("2,66,0"));
        assertEquals("minecraft:stone_bricks", door.get("1,65,0"));
        // East wall (x = 6) runs to the front: u = 2 is w = 2, z = -2. v = 1 is the second row: y = 66 and 67.
        Map<String, String> window = blocksByWorldPosition(house + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"east\"})\n"
                + "window(\"n\", \"s\", [\"surface\", \"w\", \"outer\", 2, 1], {})\n");
        assertEquals("minecraft:glass_pane", window.get("6,66,-2"));
        assertEquals("minecraft:glass_pane", window.get("6,67,-2"));
        assertEquals("minecraft:stone_bricks", window.get("6,65,-2"));
    }

    @Test
    void aShedRoofRisesTowardItsHighSide() {
        String house = wideSite("north") + "structure(\"s\", None, [0, 0, 0], {})\n";
        for (String[] side : new String[][] {{"east", "x", "6"}, {"north", "z", "-6"}}) {
            Map<String, String> cells = blocksByWorldPosition(house
                    + "roof(\"r\", \"s\", [0, 0, 0], {\"kind\": \"shed\", \"overhang\": 0, \"high_side\": \"" + side[0] + "\"})\n");
            int top = cells.keySet().stream().mapToInt(k -> Integer.parseInt(k.split(",")[1])).max().orElseThrow();
            int axis = side[1].equals("x") ? 0 : 2;
            assertTrue(cells.keySet().stream().filter(k -> Integer.parseInt(k.split(",")[1]) == top)
                    .allMatch(k -> k.split(",")[axis].equals(side[2])), side[0] + ": the highest cells must be on that side");
        }
    }

    @Test
    void theBoundsIncludeBothEnds() {
        String bounds = "site(\"minecraft:overworld\", 0, 64, 0, \"north\", [0, 0, 0, 3, 3, 3])\n";
        assertClaim(works("a block on the far corner of the bounds", Stage.COMPILE, bounds + "pillar(\"p\", None, [3, 3, 3], {\"height\": 1})\n"));
        assertClaim(works("a block on the near corner of the bounds", Stage.COMPILE, bounds + "pillar(\"p\", None, [0, 0, 0], {\"height\": 1})\n"));
        assertClaim(fails("a block one past the bounds", bounds + "pillar(\"p\", None, [4, 3, 3], {\"height\": 1})\n", "E-OUT-OF-BOUNDS:p"));
    }

    @Test
    void everyConnectionKindAndEntryDirectionTheHelpListsIsAccepted() {
        String kinds = Arrays.stream(ConnKind.values()).map(k -> "\"" + k.lower() + "\"").collect(Collectors.joining(" "));
        assertTrue(HELP.contains("kind は " + kinds + " のどれか"), kinds);
        for (ConnKind k : ConnKind.values()) {
            assertClaim(works("connect kind " + k.lower(), Stage.RUN, "connect(\"c\", \"a.b\", \"d.e\", \"" + k.lower() + "\")\n"));
        }
        String dirs = Arrays.stream(Dir6.values()).map(Dir6::lower).collect(Collectors.joining(" "));
        assertTrue(HELP.contains("entry_dirs は " + dirs + " のどれか"), dirs);
        for (Dir6 d : Dir6.values()) {
            assertClaim(works("entry direction " + d.lower(), Stage.RUN,
                    "connect(\"c\", \"a.b\", \"d.e\", \"item\", [], {\"entry_dirs\": [\"" + d.lower() + "\"]})\n"));
        }
    }

    @Test
    void everySiteFacingTheHelpListsIsAccepted() {
        String facings = Arrays.stream(Facing.values()).map(f -> "\"" + f.lower() + "\"").collect(Collectors.joining(" "));
        assertTrue(HELP.contains("facing は " + facings + "(小文字)"), facings);
        for (Facing f : Facing.values()) {
            assertClaim(works("site facing " + f.lower(), Stage.COMPILE, wideSite(f.lower())));
        }
    }

    @Test
    void aSecondLogisticsCallReplacesTheFirst() {
        String dock = "logistics([{\"id\": \"%s\", \"pad\": [0, 0, 0, 4, 0, 4], \"clearance\": [0, 1, 0, 4, 8, 4], \"approach\": \"north\"}], [], [])\n";
        String script = SITE + String.format(dock, "d1") + String.format(dock, "d2");
        PlanScriptRunner.Result run = PlanScriptRunner.run(List.of(script), PATCH_ID, BASE_REVISION, STAGE_ID, PlanRunLimits.DEFAULT);
        assertTrue(run.ok(), run.issues().toString());
        PatchResult patched = new PlanPatcher(BuildingParts.registry(), TemplateBundle.EMPTY).apply(SemanticPlan.empty(PLAN_ID), run.patch());
        assertTrue(patched.ok(), patched.issues().toString());
        assertEquals(List.of("d2"), patched.plan().logistics().docks().stream().map(LogisticsPlan.Dock::id).toList());
    }

    @Test
    void theMaterialsTheHelpNamesForStairsAndSlabsWorkAndOthersAreRefused() {
        // a role's own stairs and slab are really used: a brick roof under a plain "roof" role
        Map<String, Integer> bom = compiled(HOUSE + "style(\"roof_stairs\", \"minecraft:brick_stairs\")\n"
                + "style(\"roof_slab\", \"minecraft:brick_slab\")\nroof(\"r\", \"s\", [0, 0, 0], {})\n").manifest().bom();
        assertTrue(bom.getOrDefault("minecraft:brick_stairs", 0) > 0, bom.toString());
        assertFalse(bom.containsKey("minecraft:oak_stairs"), bom.toString());
        for (String material : List.of("minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:bricks", "minecraft:red_nether_bricks")) {
            assertClaim(works("a roof of " + material, Stage.COMPILE, HOUSE + "style(\"roof\", \"" + material + "\")\n"
                    + "roof(\"r\", \"s\", [0, 0, 0], {})\n"));
        }
        for (String part : List.of("roof", "stairs", "ramp")) {
            assertClaim(fails(part + " of a material with no stairs and slab", HOUSE + part
                    + "(\"x\", \"s\", [0, 0, 0], {\"material\": \"minecraft:red_terracotta\"})\n", "E-PARAM-RANGE:x#material"));
        }
    }

    @Test
    void updateParamsChangesOnlyTheNamesGiven() {
        String script = HOUSE + "wall(\"w\", \"s\", [0, 0, 0], {\"side\": \"north\", \"thickness\": 2})\nupdate_params(\"w\", {\"level\": 0})\n";
        PlanScriptRunner.Result run = PlanScriptRunner.run(List.of(script), PATCH_ID, BASE_REVISION, STAGE_ID, PlanRunLimits.DEFAULT);
        assertTrue(run.ok(), run.issues().toString());
        PatchResult patched = new PlanPatcher(BuildingParts.registry(), TemplateBundle.EMPTY).apply(SemanticPlan.empty(PLAN_ID), run.patch());
        assertTrue(patched.ok(), patched.issues().toString());
        PlanNode wall = patched.plan().nodes().stream().filter(n -> n.id().equals("w")).findFirst().orElseThrow();
        assertEquals(new ParamValue.IntV(2), wall.params().get("thickness"), "the parameter that was not named stays");
        assertEquals(new ParamValue.IntV(0), wall.params().get("level"));
        assertEquals(new ParamValue.EnumV("north"), wall.params().get("side"));
    }

    @Test
    void printLeavesARecordAndTheBuildingUntouched() {
        PlanScriptRunner.Result run = PlanScriptRunner.run(List.of("print(1)\nprint(\"a\")\nprint([1, 2])\n"), PATCH_ID, BASE_REVISION,
                STAGE_ID, PlanRunLimits.DEFAULT);
        assertTrue(run.ok(), run.issues().toString());
        assertEquals(3, run.printed().size());
        assertTrue(run.patch().ops().isEmpty(), "print must add nothing to the plan: " + run.patch().ops());
    }
}
