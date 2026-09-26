package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PlanInterpreterTest {
    private static void run(String source, RecordingPlanApi api, PlanRunLimits limits) {
        new Interpreter(api, limits).run(new Parser(new Lexer(source).scan()).parseProgram());
    }

    private static void run(String source, RecordingPlanApi api) {
        run(source, api, PlanRunLimits.DEFAULT);
    }

    private static String errorOf(String source) {
        return assertThrows(MicraLangException.class, () -> run(source, new RecordingPlanApi())).getMessage();
    }

    @Test
    void generalCommandsReachThePlanApi() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("site(\"minecraft:overworld\", 100, 64, -20, \"east\", [-2, 0, -2, 9, 9, 9])\n"
                + "style(\"roof\", \"minecraft:bricks\")\n"
                + "mood(\"cozy\")\n"
                + "update_params(\"hut\", {\"width\": 9})\n"
                + "relocate(\"hut\", [1, 0, 2, 1, True])\n"
                + "remove_part(\"hut\")\n"
                + "disconnect(\"c-1\")\n", api);
        assertEquals(List.of(
                "site minecraft:overworld 100,64,-20 east [-2, 0, -2, 9, 9, 9] [|]",
                "style roof=minecraft:bricks",
                "mood cozy",
                "update hut {width=9.0}",
                "relocate hut PlanAnchorArgs[kind=ABSOLUTE, u=1, v=0, w=2, turns=1, mirror=true, target=null, side=null, slot=null]",
                "remove hut",
                "disconnect c-1"), api.calls);
    }

    @Test
    void siteAcceptsTheOptionalDigestAndClaim() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("site(\"minecraft:overworld\", 0, 0, 0, \"north\", [0,0,0,1,1,1], \"abc\", \"claim-1\")", api);
        assertEquals("site minecraft:overworld 0,0,0 north [0, 0, 0, 1, 1, 1] [abc|claim-1]", api.calls.get(0));
    }

    @Test
    void logisticsPassesItsThreeListsThrough() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("logistics([{\"id\": \"d1\"}], [], [1, 2])", api);
        assertEquals(List.of("logistics 1/0/2"), api.calls);
    }

    @Test
    void partCommandsComeFromTheRegistryNamesAndReturnTheirId() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("w = wall(\"wall-n\", \"hut\", [0, 0, 0], {\"side\": \"north\"})\n"
                + "print(w)\n"
                + "part(\"press-1\", \"create:mechanical_press\", None, [3, 1, 2], {}, [\"a\", \"b\"], \"the press\")\n"
                + "door(\"door-1\", \"hut\", [\"surface\", \"wall-n\", \"outer\", 3, 0], {\"kind\": \"double\"})\n"
                + "module = part(\"m\", \"mod:line\", None, [\"slot\", \"slot-a\", 2, False], {})\n"
                + "print(module)\n", api);
        assertEquals(List.of("wall-n", "m"), api.printed);
        assertEquals("part wall-n micra:wall parent=hut PlanAnchorArgs[kind=ABSOLUTE, u=0, v=0, w=0, turns=0, mirror=false, target=null, side=null, slot=null] {side=north} [] ''",
                api.calls.get(0));
        assertEquals("part press-1 create:mechanical_press parent=null PlanAnchorArgs[kind=ABSOLUTE, u=3, v=1, w=2, turns=0, mirror=false, target=null, side=null, slot=null] {} [a, b] 'the press'",
                api.calls.get(1));
        assertEquals("part door-1 micra:door parent=hut PlanAnchorArgs[kind=SURFACE, u=3, v=0, w=0, turns=0, mirror=false, target=wall-n, side=outer, slot=null] {kind=double} [] ''",
                api.calls.get(2));
        assertEquals("part m mod:line parent=null PlanAnchorArgs[kind=SLOT, u=0, v=0, w=0, turns=2, mirror=false, target=null, side=null, slot=slot-a] {} [] ''",
                api.calls.get(3));
    }

    @Test
    void theShortAnchorFormsDefaultTurnsAndMirror() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("pillar(\"p1\", None, [1, 2, 3, 3], None, [\"t\"], \"L\")\n"
                + "pillar(\"p2\", None, [\"slot\", \"s\"], {})\n"
                + "pillar(\"p3\", None, [\"slot\", \"s\", 1, True], {})\n", api);
        assertEquals("part p1 micra:pillar parent=null PlanAnchorArgs[kind=ABSOLUTE, u=1, v=2, w=3, turns=3, mirror=false, target=null, side=null, slot=null] {} [t] 'L'",
                api.calls.get(0));
        assertEquals("part p2 micra:pillar parent=null PlanAnchorArgs[kind=SLOT, u=0, v=0, w=0, turns=0, mirror=false, target=null, side=null, slot=s] {} [] ''",
                api.calls.get(1));
        assertEquals("part p3 micra:pillar parent=null PlanAnchorArgs[kind=SLOT, u=0, v=0, w=0, turns=1, mirror=true, target=null, side=null, slot=s] {} [] ''",
                api.calls.get(2));
    }

    @Test
    void connectMeansAutoWhenViaIsMissingOrNoneAndExplicitWhenAListIsGiven() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("connect(\"c1\", \"a.out\", \"b.in\", \"rotation\")\n"
                + "connect(\"c2\", \"a.out\", \"b.in\", \"item\", None)\n"
                + "connect(\"c3\", \"a.out\", \"b.in\", \"item\", [])\n"
                + "connect(\"c4\", \"a.out\", \"b.in\", \"rotation\", [\"s1\", \"s2\"], {\"max_length\": 12, \"avoid\": [\"x\"]})\n"
                + "connect(\"c5\", \"a.out\", \"b.in\", \"item\", None, {})\n", api);
        assertEquals("connect c1 a.out b.in rotation via=null null", api.calls.get(0));
        assertEquals("connect c2 a.out b.in item via=null null", api.calls.get(1));
        assertEquals("connect c3 a.out b.in item via=[] null", api.calls.get(2));
        assertEquals("connect c4 a.out b.in rotation via=[s1, s2] {avoid=[x], max_length=12.0}", api.calls.get(3));
        assertEquals("connect c5 a.out b.in item via=null {}", api.calls.get(4));
    }

    @Test
    void variablesLoopsAndFunctionsBuildPlans() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("def post(n, x):\n"
                + "    pillar(\"post-\" + str(n), None, [x, 0, 0], {})\n"
                + "for i in range(3):\n"
                + "    post(i, i * 4)\n", api);
        assertEquals(3, api.calls.size());
        assertTrue(api.calls.get(2).startsWith("part post-2 micra:pillar"), api.calls.get(2)); // str(2.0) is "2" in this language
        assertEquals("part post-2 micra:pillar parent=null PlanAnchorArgs[kind=ABSOLUTE, u=8, v=0, w=0, turns=0, mirror=false, target=null, side=null, slot=null] {} [] ''",
                api.calls.get(2));
    }

    @Test
    void aVariableNamedLikeAConstructionCommandDoesNotHideTheCommand() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("wall = 1\nwall(\"w\", None, [0, 0, 0], {})\n", api);
        assertEquals(1, api.calls.size());
        assertTrue(api.calls.get(0).startsWith("part w micra:wall "), api.calls.get(0));
    }

    @Test
    void badArgumentsAreClearLanguageErrors() {
        RecordingPlanApi api = new RecordingPlanApi();
        MicraLangException arity = assertThrows(MicraLangException.class, () -> run("wall(\"w\")", api));
        assertTrue(arity.getMessage().contains("wall()"), arity.getMessage());
        assertTrue(assertThrows(MicraLangException.class, () -> run("site(\"d\", 1.5, 0, 0, \"north\", [0,0,0,1,1,1])", api)).getMessage().contains("整数"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("site(\"d\", 1, 0, 0, \"up\", [0,0,0,1,1,1])", api)).getMessage().contains("site()"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("wall(\"w\", None, [0, 0], {})", api)).getMessage().contains("wall()"));
        assertTrue(assertThrows(MicraLangException.class, () -> run("connect(\"c\", \"nodot\", \"b.in\", \"item\")", api)).getMessage().contains("connect()"));
        assertEquals(List.of(), api.calls, "a rejected call reaches nothing");
    }

    @Test
    void badArgumentMessagesAreExact() {
        assertEquals("line 1: wall() takes 4 to 6 arguments but got 1", errorOf("wall(\"w\")"));
        assertEquals("line 1: site(): 整数が必要です(1.5)", errorOf("site(\"d\", 1.5, 0, 0, \"north\", [0,0,0,1,1,1])"));
        assertEquals("line 1: site(): 向きは \"north\" \"east\" \"south\" \"west\" のどれかです(up)",
                errorOf("site(\"d\", 1, 0, 0, \"up\", [0,0,0,1,1,1])"));
        assertEquals("line 1: site(): 整数が6個並んだリストが必要です", errorOf("site(\"d\", 1, 0, 0, \"north\", [0,0,0,1,1])"));
        assertEquals("line 1: wall(): [u, v, w] か [u, v, w, 回転数, 鏡像] の形にしてください", errorOf("wall(\"w\", None, [0, 0], {})"));
        assertEquals("line 1: connect(): \"ノードID.ポート名\" の形にしてください(nodot)", errorOf("connect(\"c\", \"nodot\", \"b.in\", \"item\")"));
        assertEquals("line 1: connect(): \"ノードID.ポート名\" の形にしてください(a.)", errorOf("connect(\"c\", \"b.in\", \"a.\", \"item\")"));
        assertEquals("line 1: style(): 2番目の引数は文字列が必要です", errorOf("style(\"roof\", 3)"));
        assertEquals("line 1: mood(): 1番目の引数は文字列が必要です", errorOf("mood(None)"));
        assertEquals("line 1: update_params(): 辞書({\"名前\": 値})が必要です", errorOf("update_params(\"x\", [1])"));
        assertEquals("line 1: update_params(): 辞書のキーは文字列にしてください", errorOf("update_params(\"x\", {1: 2})"));
        assertEquals("line 1: part(): 文字列のリストが必要です", errorOf("part(\"p\", \"t\", None, [0,0,0], {}, [1])"));
        assertEquals("line 1: logistics(): リストが必要です", errorOf("logistics([], {}, [])"));
    }

    @Test
    void anchorFormsAreChecked() {
        assertEquals("line 1: door(): 面の側は \"outer\" か \"inner\" です(top)",
                errorOf("door(\"d\", None, [\"surface\", \"w\", \"top\", 0, 0], {})"));
        assertEquals("line 1: door(): [\"surface\", 壁ID, \"outer\"か\"inner\", u, v] の形にしてください",
                errorOf("door(\"d\", None, [\"surface\", \"w\", \"outer\", 0], {})"));
        assertEquals("line 1: door(): [\"slot\", スロットID, 回転数, 鏡像] の形にしてください",
                errorOf("door(\"d\", None, [\"slot\", \"s\", 1, True, 5], {})"));
        assertEquals("line 1: door(): 回転数は0〜3です(4)", errorOf("door(\"d\", None, [\"slot\", \"s\", 4], {})"));
        assertEquals("line 1: relocate(): 回転数は0〜3です(4)", errorOf("relocate(\"d\", [0, 0, 0, 4])"));
        assertEquals("line 1: relocate(): True か False が必要です(1)", errorOf("relocate(\"d\", [0, 0, 0, 0, 1])"));
        assertEquals("line 1: relocate(): 位置指定の種類が不明です: line", errorOf("relocate(\"d\", [\"line\", 0, 0])"));
        assertEquals("line 1: relocate(): [u, v, w] か [u, v, w, 回転数, 鏡像] の形にしてください",
                errorOf("relocate(\"d\", [0, 0, 0, 0, False, 1])"));
    }

    /** Arities are hand-copied from the design (04_foundations.md F-6), not from the implementation. */
    @Test
    void everyCommandChecksItsArgumentCount() {
        Map<String, String> takes = new LinkedHashMap<>();
        takes.put("site", "6 to 8");
        takes.put("style", "2");
        takes.put("mood", "1");
        takes.put("part", "5 to 7");
        takes.put("update_params", "2");
        takes.put("relocate", "2");
        takes.put("remove_part", "1");
        takes.put("connect", "4 to 6");
        takes.put("disconnect", "1");
        takes.put("logistics", "3");
        for (String name : CommandNames.PLAN_PART_COMMANDS) {
            takes.put(name, "4 to 6");
        }
        assertEquals(CommandNames.PLAN.size(), takes.size());
        for (Map.Entry<String, String> e : takes.entrySet()) {
            assertEquals("line 1: " + e.getKey() + "() takes " + e.getValue() + " arguments but got 0", errorOf(e.getKey() + "()"));
        }
        assertEquals("line 1: site() takes 6 to 8 arguments but got 9", errorOf("site(1, 2, 3, 4, 5, 6, 7, 8, 9)"));
        assertEquals("line 1: part() takes 5 to 7 arguments but got 8", errorOf("part(1, 2, 3, 4, 5, 6, 7, 8)"));
        assertEquals("line 1: connect() takes 4 to 6 arguments but got 7", errorOf("connect(1, 2, 3, 4, 5, 6, 7)"));
        assertEquals("line 1: wall() takes 4 to 6 arguments but got 7", errorOf("wall(1, 2, 3, 4, 5, 6, 7)"));
        assertEquals("line 1: style() takes 2 arguments but got 3", errorOf("style(1, 2, 3)"));
    }

    @Test
    void farmCommandsAndNondeterministicBuiltinsAreRefusedEvenWithoutTheStaticCheck() {
        for (String call : List.of("move(\"north\")", "harvest()", "sleep_ticks(1)", "random()", "get_time()", "semaphore()",
                "create_task(\"t\", 1, 1, None)", "attach_isr(\"n\", None)", "raise_interrupt(\"n\")", "get_ground()")) {
            MicraLangException e = assertThrows(MicraLangException.class, () -> run(call, new RecordingPlanApi()), call);
            assertTrue(e.getMessage().contains("construction script"), call + ": " + e.getMessage());
        }
        assertEquals("line 1: 'move' cannot be used in a construction script (only construction commands and pure helpers)",
                errorOf("move(\"north\")"));
    }

    @Test
    void pureHelpersStillWorkInAConstructionScript() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("xs = list([3, 1])\n"
                + "d = dict()\n"
                + "print(str(len(xs)) + \" \" + str(abs(0 - 2)) + \" \" + str(min(xs)) + \" \" + str(max(4, 5)) + \" \" + str(len(set([1, 1]))) + \" \" + str(len(d)))\n",
                api);
        assertEquals(List.of("2 2 1 5 1 0"), api.printed);
    }

    @Test
    void aFunctionMayNotShadowAConstructionCommand() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("def wall():\n    pass\n", new RecordingPlanApi()));
        assertTrue(e.getMessage().contains("built-in"), e.getMessage());
        assertEquals("line 1: 'site' is a built-in command and cannot be redefined", errorOf("def site():\n    pass\n"));
    }

    @Test
    void theStepLimitStopsRunawayScripts() {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("while True:\n    pass\n", new RecordingPlanApi(), new PlanRunLimits(500, 60_000)));
        assertTrue(e.getMessage().contains("500"), e.getMessage());
        // step 1 is the while statement; each iteration then adds the loop check (line 1) and the pass (line 2),
        // so step 501 = 1 + 250 * 2 is a pass on line 2
        assertEquals("line 2: construction script exceeded 500 steps", e.getMessage());
    }

    @Test
    void aScriptExactlyAtTheStepLimitRuns() {
        RecordingPlanApi api = new RecordingPlanApi();
        // 3 statements = 3 steps
        run("mood(\"a\")\nmood(\"b\")\nmood(\"c\")\n", api, new PlanRunLimits(3, 60_000));
        assertEquals(3, api.calls.size());
        assertThrows(PlanLimitException.class, () -> run("mood(\"a\")\nmood(\"b\")\nmood(\"c\")\n", new RecordingPlanApi(), new PlanRunLimits(2, 60_000)));
    }

    @Test
    void theFarmRunawayHeuristicIsNotUsedForConstructionScripts() {
        // two million steps without any drone action: a farm script would have been stopped at one million
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("while True:\n    pass\n", new RecordingPlanApi(), new PlanRunLimits(2_000_000, 60_000)));
        assertEquals("line 2: construction script exceeded 2000000 steps", e.getMessage());
    }

    @Test
    void theTimeLimitStopsSlowScripts() {
        long start = System.nanoTime();
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("while True:\n    pass\n", new RecordingPlanApi(), new PlanRunLimits(Long.MAX_VALUE, 60)));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "it stopped soon after the limit");
        assertTrue(e.getMessage().endsWith(": construction script exceeded 60 ms"), e.getMessage());
    }

    @Test
    void theDefaultLimitsAreTheDocumentedOnes() {
        assertEquals(100_000, PlanRunLimits.DEFAULT.maxSteps());
        assertEquals(5_000, PlanRunLimits.DEFAULT.maxMillis());
        Object limit = new PlanLimitException(3, "x");
        assertFalse(limit instanceof IllegalStateException, "a limit is not an illegal-state error");
        assertTrue(limit instanceof MicraLangException);
    }

    @Test
    void thePlanCommandsDoNotExistForAFarmInterpreter() {
        FakeDroneApi farm = new FakeDroneApi(5);
        MicraLangException e = assertThrows(MicraLangException.class,
                () -> new Interpreter(farm).run(new Parser(new Lexer("wall(\"w\", None, [0,0,0], {})").scan()).parseProgram()));
        assertTrue(e.getMessage().contains("unknown function"), e.getMessage());
        // and a farm script may define functions with those names, as before
        new Interpreter(new FakeDroneApi(5)).run(new Parser(new Lexer("def wall():\n    pass\nwall()\n").scan()).parseProgram());
    }

    @Test
    void theFarmRunawayHeuristicStillStopsAFarmScript() {
        MicraLangException e = assertThrows(MicraLangException.class,
                () -> new Interpreter(new FakeDroneApi(5)).run(new Parser(new Lexer("while True:\n    pass\n").scan()).parseProgram()));
        assertFalse(e instanceof PlanLimitException);
        assertTrue(e.getMessage().endsWith("script ran too long without any drone action (possible infinite loop) - stopped"), e.getMessage());
    }

    // ---- memory limits (construction scripts only; farm scripts are unchanged) ----

    @Test
    void aDoublingStringHitsTheStringSizeLimitLongBeforeTheHeapIsExhausted() {
        long start = System.nanoTime();
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nwhile True:\n    s = s + s\n", new RecordingPlanApi(),
                        new PlanRunLimits(Long.MAX_VALUE, 60_000)));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "it stopped well before an OutOfMemoryError");
        assertEquals("line 3: construction script exceeded the string size limit of 1000000 characters", e.getMessage());
    }

    @Test
    void aGrowingListHitsTheCollectionSizeLimitLongBeforeTheStepLimit() {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("items = []\nwhile True:\n    items.append(1)\n", new RecordingPlanApi(),
                        new PlanRunLimits(Long.MAX_VALUE, 60_000)));
        assertEquals("line 3: construction script exceeded the collection size limit of 100000 elements", e.getMessage());
    }

    @Test
    void dictInsertsAndSetAddsCountAgainstTheCollectionSizeLimit() {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("d = {}\ni = 0\nwhile True:\n    d[i] = i\n    i = i + 1\n", new RecordingPlanApi(),
                        new PlanRunLimits(Long.MAX_VALUE, 60_000)));
        assertTrue(e.getMessage().contains("collection size"), e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run("s = set()\ni = 0\nwhile True:\n    s.add(i)\n    i = i + 1\n", new RecordingPlanApi(),
                        new PlanRunLimits(Long.MAX_VALUE, 60_000)));
        assertTrue(e.getMessage().contains("collection size"), e.getMessage());
    }

    @Test
    void copyingAStringIntoACollectionHitsTheCollectionSizeLimit() {
        // 2^19 = 524,288 characters: a legal string, but list() would split it into 524,288 > 100,000 elements
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nitems = list(s)\n", new RecordingPlanApi(),
                        new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 4: construction script exceeded the collection size limit of 100000 elements", e.getMessage());
    }

    @Test
    void loopingOverAStringHitsTheCollectionSizeLimit() {
        // the interpreter snapshots an iterated string into a list of characters - same cap applies
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nfor c in s:\n    pass\n", new RecordingPlanApi(),
                        new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("collection size"), e.getMessage());
    }

    @Test
    void strAndPrintAreBoundedByTheStringSizeLimit() {
        // two 524,288-character strings render as a list into more than 1,000,000 characters
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nitems = [s, s]\nprint(str(items))\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("string size"), e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nitems = [s, s]\nprint(items)\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("string size"), e.getMessage());
    }

    @Test
    void stringsAndCollectionsJustUnderTheCapsStillRun() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("s = \"x\"\nfor i in range(19):\n    s = s + s\n"                    // 524,288 characters, under 1,000,000
                + "items = []\nfor i in range(100000):\n    items.append(i)\n" // exactly 100,000 elements
                + "print(len(s))\nprint(len(items))\n", api, new PlanRunLimits(1_000_000, 60_000));
        assertEquals(List.of("524288", "100000"), api.printed);
    }

    @Test
    void theStringSizeCapDoesNotApplyToFarmScripts() {
        FakeDroneApi api = new FakeDroneApi(5);
        // one concatenation of two 1,048,576-character strings = 2,097,152 characters: fine for a farm script
        new Interpreter(api).run(new Parser(new Lexer(
                "s = \"x\"\nfor i in range(20):\n    s = s + s\nt = s + s\nprint(len(t))\n").scan()).parseProgram());
        assertEquals(List.of("2097152"), api.printed);
    }

    // ---- run-wide allocation budget (construction scripts only; farm scripts are unchanged) ----

    @Test
    void theAllocationBudgetStopsAPrintLoopOfALegalSizedString() {
        RecordingPlanApi api = new RecordingPlanApi();
        // the doubling charges 2+4+...+524,288 = 1,048,574 units, leaving 10,000,000 - 1,048,574 = 8,951,426;
        // each print charges 524,288, so 17 fit (8,912,896) but the 18th does not (9,437,184) - and a
        // refused print is never recorded, so exactly 17 lines are printed
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nwhile True:\n    print(s)\n", api,
                        new PlanRunLimits(1_000_000, 60_000)));
        assertEquals(17, api.printed.size());
        assertEquals("line 5: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void theAllocationBudgetStopsAStringConcatenationLoop() {
        // script D: every iteration builds another 524,289-character string (s + "!")
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(19):\n    s = s + s\nacc = []\nwhile True:\n    acc.append(s + \"!\")\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("total allocation limit"), e.getMessage());
    }

    @Test
    void theAllocationBudgetStopsRepeatedCopiesOfALegalSizedList() {
        // script E: each list(l) copy charges its 20,000 elements
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("l = []\nfor i in range(20000):\n    l.append(i)\nacc = []\nwhile True:\n    acc.append(list(l))\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("total allocation limit"), e.getMessage());
    }

    @Test
    void aLegitimateLoopStaysWellUnderTheAllocationBudget() {
        RecordingPlanApi api = new RecordingPlanApi();
        // 1,000 iterations x (a 100-character concatenation + a 100-element list copy) = 200,000 units
        assertDoesNotThrow(() -> run("h = \"" + "x".repeat(50) + "\"\n"
                + "l = []\nfor i in range(100):\n    l.append(i)\n"
                + "for i in range(1000):\n    t = h + h\n    c = list(l)\n", api, new PlanRunLimits(100_000, 60_000)));
    }

    @Test
    void aStringExactlyAtTheSizeCapIsAccepted() {
        RecordingPlanApi api = new RecordingPlanApi();
        // s = "x" then six times s = s+s+...+s (ten terms) = exactly 1,000,000 characters; the
        // concatenations charge 54 x (1+10+100+1,000+10,000+100,000) = 5,999,994 units
        run("s = \"x\"\n"
                + "s = s + s + s + s + s + s + s + s + s + s\n".repeat(6)
                + "print(len(s))\n", api, new PlanRunLimits(100_000, 60_000));
        assertEquals(List.of("1000000"), api.printed);
    }

    @Test
    void theFirstConcatPastTheSizeCapStillReportsTheStringSizeLimit() {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\n"
                        + "s = s + s + s + s + s + s + s + s + s + s\n".repeat(6)
                        + "t = s + \"x\"\n", new RecordingPlanApi(), new PlanRunLimits(100_000, 60_000)));
        assertEquals("line 8: construction script exceeded the string size limit of 1000000 characters", e.getMessage());
    }

    @Test
    void theAllocationBudgetDoesNotApplyToFarmScripts() {
        FakeDroneApi api = new FakeDroneApi(5);
        // doubling to 524,288 characters then 30 prints is 15,728,640 units - over the construction
        // budget - but a farm interpreter has no allocation budget at all
        new Interpreter(api).run(new Parser(new Lexer(
                "s = \"x\"\nfor i in range(19):\n    s = s + s\nfor i in range(30):\n    print(s)\n").scan()).parseProgram());
        assertEquals(30, api.printed.size());
        assertEquals(524288, api.printed.get(0).length());
    }

    // ---- every remaining charge site (fix round 2): print, "+" and list() are pinned above ----

    /**
     * Scripts that each hammer exactly one chargePlanAllocation site: a legal-sized seed value
     * (20,000 elements or a 524,288/65,536-character string, both under the per-value caps) is
     * copied/snapshotted/rendered in a loop until the 10,000,000-unit budget gives out. Seeding by
     * append/index-assign/doubling is cheap in steps and the loops die in a few hundred statements,
     * so without the site's charge the same script runs on into the step or time limit instead.
     */
    private static Stream<Arguments> scriptsThatHammerOneAllocationChargeSiteEach() {
        // a legal 20,000-element list/dict: append and d[i]= are not charged, so each later
        // copy/snapshot of the seed charges exactly 20,000 units (500 of them fill the budget) -
        // except the set() copy, which weighs 10 units per element (200,000 per copy, 50 fill it)
        String list = "l = []\nfor i in range(20000):\n    l.append(i)\n";
        String dict = "d = {}\nfor i in range(20000):\n    d[i] = i\n";
        return Stream.of(
                // the doublings charge 2+4+...+524,288 = 1,048,574; each str(s) charges 524,288 more,
                // so the 17th call fits (9,961,470) and the 18th does not
                Arguments.of("str()",
                        "s = \"x\"\nfor i in range(19):\n    s = s + s\nwhile True:\n    t = str(s)\n"),
                Arguments.of("set()", list + "while True:\n    t = set(l)\n"),
                Arguments.of("keys()", dict + "while True:\n    k = d.keys()\n"),
                Arguments.of("values()", dict + "while True:\n    v = d.values()\n"),
                // each for-loop snapshot of the seed charges its 20,000 elements; the body exits at once
                Arguments.of("for over a list", list + "while True:\n    for x in l:\n        break\n"),
                Arguments.of("for over a set", list + "s = set(l)\nwhile True:\n    for x in s:\n        break\n"),
                Arguments.of("for over a dict", dict + "while True:\n    for k in d:\n        break\n"),
                // 16 doublings make a 65,536-character string (charged 131,070); each loop snapshot
                // charges 6 x 65,536 fresh one-character Strings, so the 26th loop tips the total
                // past 10,000,000
                Arguments.of("for over a string",
                        "s = \"x\"\nfor i in range(16):\n    s = s + s\nwhile True:\n    for c in s:\n        break\n"),
                // each min()/max() call copies all 20,000 candidates into its candidate list
                Arguments.of("min()", list + "while True:\n    m = min(l)\n"),
                Arguments.of("max()", list + "while True:\n    m = max(l)\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scriptsThatHammerOneAllocationChargeSiteEach")
    void everyChargeSiteStopsWithTheAllocationMessageNotTheStepMessage(String site, String script) {
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertTrue(e.getMessage().contains("total allocation limit of 10000000"), site + ": " + e.getMessage());
    }

    @Test
    void exactlyTenMillionAllocatedUnitsRunAndOneMoreThrows() {
        RecordingPlanApi api = new RecordingPlanApi();
        // s = "x" then six lines of s = s+s+...+s (ten terms): on line k the nine "+" operations
        // produce results of 2,3,...,10 x 10^(k-1) characters, charging (2+3+...+10) x 10^(k-1)
        // = 54 x 10^(k-1) units, so the six lines charge 54 x 111,111 = 5,999,994 and leave s at
        // exactly the 1,000,000-character cap.
        // Four str(s) calls then charge 4 x 1,000,000 -> 9,999,994, and str("abcdef") adds the
        // final 6: exactly 10,000,000 units, which is still allowed (the check is ">", not ">=").
        String buildToCap = "s = \"x\"\n" + "s = s + s + s + s + s + s + s + s + s + s\n".repeat(6);
        String fourRenders = "t = str(s)\n".repeat(4);
        run(buildToCap + fourRenders + "u = str(\"abcdef\")\nmood(\"ok\")\n", api,
                new PlanRunLimits(1_000_000, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // one more charged unit (a 1-character str) takes the total to 10,000,001: refused on line 13
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(buildToCap + fourRenders + "u = str(\"abcdef\")\nv = str(\"a\")\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 13: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    // ---- literals count against the run-wide allocation budget (fix round 3) ----

    @Test
    void aListLiteralIsChargedForEveryElementOfEveryEvaluation() {
        // each evaluation of the 2,000-element literal charges 2,000 x 3 = 6,000 units (a list
        // literal element retains its ArrayList slot plus a freshly boxed value, about 24 bytes
        // = 3 units), so 1,666 evaluations charge 9,996,000 and run; the 1,667th tips the total
        // to 10,002,000 and throws on the append line
        String literal = "[0" + ",0".repeat(1_999) + "]";
        run("acc = []\nfor i in range(1666):\n    acc.append(" + literal + ")\n", new RecordingPlanApi(),
                new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("acc = []\nfor i in range(1667):\n    acc.append(" + literal + ")\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 3: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void aDictLiteralIsChargedTenUnitsPerEntryPerEvaluation() {
        // a dict entry weighs 10 units (a LinkedHashMap entry plus its table slot plus two boxed
        // values is about 80 bytes), so the 500-entry literal charges 5,000 per evaluation:
        // 2,000 evaluations are exactly 10,000,000 and run, the 2,001st throws
        StringBuilder dict = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                dict.append(", ");
            }
            dict.append("\"k").append(i).append("\": 0");
        }
        dict.append("}");
        run("acc = []\nfor i in range(2000):\n    acc.append(" + dict + ")\n", new RecordingPlanApi(),
                new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("acc = []\nfor i in range(2001):\n    acc.append(" + dict + ")\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 3: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void aSetLiteralIsChargedTenUnitsPerElementPerEvaluation() {
        // a set literal weighs its elements the same 10 units each (LinkedHashSet entries), so
        // the 500-element literal charges 5,000 per evaluation - 2,000 fit, the 2,001st throws
        StringBuilder set = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                set.append(", ");
            }
            set.append(i);
        }
        set.append("}");
        run("acc = []\nfor i in range(2000):\n    acc.append(" + set + ")\n", new RecordingPlanApi(),
                new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("acc = []\nfor i in range(2001):\n    acc.append(" + set + ")\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 3: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void theSetCopyIsChargedTenUnitsPerElement() {
        // set(l) on the 1,000-element seed copies into a LinkedHashSet: 1,000 x 10 = 10,000 units
        // per copy, so 1,000 copies are exactly 10,000,000 and run while the 1,001st throws on
        // the copy line. The seeding appends charge nothing; both scripts stay far under the
        // step limit (1 + 2x1,000 + 2x1,001 = 4,003 steps)
        String seed = "l = []\nfor i in range(1000):\n    l.append(i)\n";
        run(seed + "for i in range(1000):\n    t = set(l)\n", new RecordingPlanApi(),
                new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(seed + "for i in range(1001):\n    t = set(l)\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 5: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void literalChargesDoNotApplyToFarmScripts() {
        FakeDroneApi api = new FakeDroneApi(5);
        // the same 2,000-element list literal evaluated 1,667 times = 10,002,000 units, over the
        // construction budget - but a farm interpreter has no allocation budget at all
        String literal = "[0" + ",0".repeat(1_999) + "]";
        new Interpreter(api).run(new Parser(new Lexer(
                "acc = []\nfor i in range(1667):\n    acc.append(" + literal + ")\nprint(len(acc))\n")
                .scan()).parseProgram());
        assertEquals(List.of("1667"), api.printed);
    }

    @Test
    void theStringSizeCheckRunsBeforeTheAllocationCharge() {
        // the run reaches 9,999,994 units, then s + "x" produces 1,000,001 characters: BOTH the
        // string cap (1,000,000) and the remaining budget (6 units) are exceeded, and the
        // string-size message must win because that check runs before the allocation charge
        String buildToCap = "s = \"x\"\n" + "s = s + s + s + s + s + s + s + s + s + s\n".repeat(6);
        String fourRenders = "t = str(s)\n".repeat(4);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(buildToCap + fourRenders + "u = s + \"x\"\n", new RecordingPlanApi(),
                        new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 12: construction script exceeded the string size limit of 1000000 characters", e.getMessage());
    }

    // ---- a refused print is an expected limit, not an internal error (fix round 4) ----

    @Test
    void aPrintRefusalFromTheApiIsAPlanLimitExceptionWithTheLine() {
        // the recorder's budgets refuse with a PlanBudgetException; in plan mode that is an
        // expected limit, so the interpreter rethrows it as a PlanLimitException carrying the
        // call's line - otherwise the runner reports it as an internal error
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> new Interpreter(budgetRefusingPrintPlanApi(), PlanRunLimits.DEFAULT).run(
                        new Parser(new Lexer("x = 1\nprint(\"hi\")\n").scan()).parseProgram()));
        assertEquals("line 2: printed output refused", e.getMessage());
    }

    @Test
    void aNonBudgetPrintRefusalFromTheApiPropagatesUnchangedInPlanMode() {
        // a plain IllegalArgumentException from api.print is not a budget refusal - it is a
        // recorder bug and must propagate unchanged, not be rethrown as a limit
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Interpreter(refusingPrintPlanApi(), PlanRunLimits.DEFAULT).run(
                        new Parser(new Lexer("print(\"hi\")\n").scan()).parseProgram()));
        assertEquals(IllegalArgumentException.class, e.getClass(), "a bug must not be wrapped as a limit");
        assertEquals("printed output refused", e.getMessage());
    }

    @Test
    void aPrintRefusalPropagatesUnchangedForAFarmInterpreter() {
        // farm mode has no plan budgets: a DroneApi's IllegalArgumentException from print is not
        // wrapped - it propagates exactly as it did before
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Interpreter(refusingPrintDroneApi()).run(
                        new Parser(new Lexer("print(\"hi\")\n").scan()).parseProgram()));
        assertEquals("printed output refused", e.getMessage());
    }

    // ---- a split string weighs its fresh characters, and set() counts its input (fix round 5) ----

    /**
     * {@code s = "x"} doubled 12 times is a 4,096-character string; the concatenations charge
     * 2+4+...+4,096 = 8,190 units, leaving 9,991,810 of the 10,000,000-unit budget.
     */
    private static final String STRING_SEED_4096 = "s = \"x\"\nfor i in range(12):\n    s = s + s\n";

    @Test
    void listOfAStringWeighsSixUnitsPerCharacter() {
        // list(s) splits the string into 4,096 fresh one-character Strings = 6 x 4,096 = 24,576
        // units per call: 406 calls reach 8,190 + 406 x 24,576 = 9,986,046 and run; the 407th
        // reaches 10,010,622 and throws on the append line. Steps stay trivial (~840 of
        // 1,000,000).
        run(STRING_SEED_4096 + "acc = []\nfor i in range(406):\n    acc.append(list(s))\n",
                new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(STRING_SEED_4096 + "acc = []\nfor i in range(407):\n    acc.append(list(s))\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 6: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void setOfAStringWeighsTheCharsPlusTheInputSizedHashTable() {
        // set(s) materialises the 4,096-character list (6 units per character minus the slot
        // unit the copy charge covers) and then a LinkedHashSet whose table is sized from the
        // 4,096-element INPUT at 10 units each, not from the 1-element result: 15 x 4,096 =
        // 61,440 per call, so 162 calls reach 9,961,470 and run while the 163rd reaches
        // 10,022,910 and throws
        run(STRING_SEED_4096 + "acc = []\nfor i in range(162):\n    acc.append(set(s))\n",
                new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(STRING_SEED_4096 + "acc = []\nfor i in range(163):\n    acc.append(set(s))\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 6: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void aForLoopSnapshotOfAStringWeighsSixUnitsPerCharacter() {
        // every for c in s snapshots the string into 4,096 fresh one-character Strings = 24,576
        // units; 406 snapshots reach 9,986,046 and run, the 407th throws on the inner for's line
        run(STRING_SEED_4096 + "for i in range(406):\n    for c in s:\n        break\n",
                new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(STRING_SEED_4096 + "for i in range(407):\n    for c in s:\n        break\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 5: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void setOfAListWithDuplicatesCountsTheInputSizeNotTheResult() {
        // l holds 5,000 copies of the number 7: set(l) returns a 1-element set, but the
        // LinkedHashSet's table is sized from the 5,000-element INPUT, so each copy costs
        // 5,000 x 10 = 50,000 - 200 copies are exactly 10,000,000 and run, the 201st throws.
        // This test fails if the charge goes back to the result size (10 units per copy).
        String seed = "l = []\nfor i in range(5000):\n    l.append(7)\n";
        run(seed + "for i in range(200):\n    t = set(l)\n", new RecordingPlanApi(),
                new PlanRunLimits(1_000_000, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(seed + "for i in range(201):\n    t = set(l)\n",
                        new RecordingPlanApi(), new PlanRunLimits(1_000_000, 60_000)));
        assertEquals("line 5: construction script exceeded the total allocation limit of 10000000 (characters and collection elements created)",
                e.getMessage());
    }

    @Test
    void charSplitChargesDoNotApplyToFarmScripts() {
        // a farm interpreter has no allocation budget: list(s), set(s) and the for-loop
        // snapshot of the same 4,096-character string, 500 times each, just run
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer(STRING_SEED_4096
                + "for i in range(500):\n    a = list(s)\n    b = set(s)\n    for c in s:\n        break\n"
                + "print(len(s))\n").scan()).parseProgram());
        assertEquals(List.of("4096"), api.printed);
    }

    // ---- a single expensive statement pays its worst-case work as steps (H-1a) ----

    /**
     * Seed for the substring-search work charge: 11 doublings of "a" leave s at 2,048
     * characters, p = s + "b" is a 2,049-character needle and t = s + s a 4,096-character
     * text, so one {@code p in t} costs (4,096 - 2,049 + 1) x 2,049 = 2,048 x 2,049 =
     * 4,196,352 work units = exactly 4,098 quanta of 1,024 - an exact multiple, so no
     * carry is left. The five preamble lines cost 1 + (1 + 11 x 2) + 1 + 1 = 26
     * statement steps.
     */
    private static final String SUBSTRING_SEED_2049_IN_4096 =
            "s = \"a\"\nfor i in range(11):\n    s = s + s\np = s + \"b\"\nt = s + s\n";

    @Test
    void aSubstringSearchIsChargedItsWorstCaseWorkAsStepsBeforeItRuns() {
        // each `x = p in t` pays 4,196,352 = exactly 4,098 steps (4,098 x 1,024,
        // nothing left to carry), so on top of the 26-statement preamble the
        // search lines end at 4,125 / 8,224 / 12,323 / 16,422. Two searches fit
        // an 8,224-step budget exactly; at 8,223 the second is refused
        // mid-statement, and at 12,322 the third search's charge ends at 12,323
        // and trips the step limit on that line before the search runs.
        run(SUBSTRING_SEED_2049_IN_4096 + "x = p in t\n".repeat(2), new RecordingPlanApi(),
                new PlanRunLimits(8_224, 60_000));
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(SUBSTRING_SEED_2049_IN_4096 + "x = p in t\n".repeat(2),
                        new RecordingPlanApi(), new PlanRunLimits(8_223, 60_000)));
        assertEquals("line 7: construction script exceeded 8223 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(SUBSTRING_SEED_2049_IN_4096 + "x = p in t\n".repeat(4),
                        new RecordingPlanApi(), new PlanRunLimits(12_322, 60_000)));
        assertEquals("line 8: construction script exceeded 12322 steps", e.getMessage());
    }

    @Test
    void theSubstringReproAtSmallScaleStaysCheapEnoughToComplete() {
        // the reviewer's repro shape at small scale: s = 4,096 ("x" doubled 12 times),
        // p = 4,097, t = 3 x 4,096 = 12,288: work = (12,288 - 4,097 + 1) x 4,097 =
        // 8,192 x 4,097 = 33,562,624 = exactly 32,776 steps (32,776 x 1,024) - well
        // under the default
        // 100,000-step budget, so the statement just runs (and finds nothing: the "b"
        // is never in an all-"x" text)
        RecordingPlanApi api = new RecordingPlanApi();
        run(STRING_SEED_4096 + "p = s + \"b\"\nt = s + s + s\nx = p in t\nprint(x)\n", api);
        assertEquals(List.of("False"), api.printed);
    }

    @Test
    void aHugeSubstringSearchIsRefusedUpFrontByItsWorkCharge() {
        // the reviewer's repro at full scale: s = 131,072 (17 doublings), p = 131,073,
        // t = 393,216: work = (393,216 - 131,073 + 1) x 131,073 = 262,144 x 131,073 =
        // 34,360,000,512 = exactly 33,554,688 steps (33,554,688 x 1,024) - over the
        // default 100,000-step budget
        // on its own, so the charge throws BEFORE String.contains starts (this test would
        // take ~10 s per assertion if the search actually ran)
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("s = \"x\"\nfor i in range(17):\n    s = s + s\n"
                                + "p = s + \"b\"\nt = s + s + s\nx = p in t\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 6: construction script exceeded 100000 steps", e.getMessage());
    }

    @Test
    void aSubstringSearchLoopHitsTheStepLimitBeforeTheTimeLimit() {
        // the reviewer's loop repro: each `p in t` over the 4,097-character needle and
        // 12,288-character text is charged 32,776 steps, so the fourth iteration's
        // charge crosses the step limit long before the 5,000 ms clock could be polled
        // at a boundary
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(STRING_SEED_4096 + "p = s + \"b\"\nt = s + s + s\n"
                                + "while True:\n    x = p in t\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 7: construction script exceeded 100000 steps", e.getMessage());
    }

    @Test
    void aSubstringSearchBelowTheWorkQuantumAddsNoSteps() {
        // a 5-character needle in a 20-character text: work = (20 - 5 + 1) x 5 = 80 units,
        // under the 1,024-unit step quantum, so it charges 0 steps and the 2-statement
        // script still fits exactly a 2-step budget (a 1-step budget still refuses it)
        String script = "x = \"abcde\" in \"" + "x".repeat(20) + "\"\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(2, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(1, 60_000)));
    }

    @Test
    void substringSearchWorkChargesDoNotApplyToFarmScripts() {
        // farm interpreters have no step budget: the 2^15 search below (work =
        // 65,536 x 32,769 = 2,147,549,184 units, i.e. ~2,097,216 steps in plan mode) plus a
        // 200-iteration while loop of short searches just runs, as before
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer(
                "s = \"x\"\nfor i in range(15):\n    s = s + s\n"
                        + "p = s + \"b\"\nt = s + s + s\nx = p in t\n"
                        + "c = 0\nwhile c < 200:\n    y = \"ab\" in \"xxabxx\"\n    c = c + 1\n"
                        + "print(x)\nprint(c)\n").scan()).parseProgram());
        assertEquals(List.of("False", "200"), api.printed);
    }

    // ---- the charge's quantum boundary and up-front timing (H-1a tests) ----

    @Test
    void aSearchCostingExactlyOneQuantumPaysExactlyOneStep() {
        // the PLAN_WORK_PER_STEP = 1,024 boundary: a 32-character needle in a
        // 63-character text costs (63 - 32 + 1) x 32 = 32 x 32 = 1,024 work units =
        // exactly ONE extra step, while the same needle in a 62-character text costs
        // 31 x 32 = 992 = ZERO extra steps - so the two two-statement scripts below
        // differ by exactly one step (3 vs 2)
        String needle = "n".repeat(32);
        String oneStep = "x = \"" + needle + "\" in \"" + "x".repeat(62) + "\"\nmood(\"ok\")\n";
        String twoSteps = "x = \"" + needle + "\" in \"" + "x".repeat(63) + "\"\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(oneStep, api, new PlanRunLimits(2, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // the 63-character text needs the extra step, and the charge lands on the `in`
        // line itself: a one-step budget already refuses it there
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(twoSteps, new RecordingPlanApi(), new PlanRunLimits(1, 60_000)));
        assertEquals("line 1: construction script exceeded 1 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(twoSteps, new RecordingPlanApi(), new PlanRunLimits(2, 60_000)));
        assertEquals("line 2: construction script exceeded 2 steps", e.getMessage());
        run(twoSteps, api, new PlanRunLimits(3, 60_000));
    }

    @Test
    void aHugeSubstringSearchIsRefusedBeforeTheSearchItselfCouldRun() {
        // the same refusal as aHugeSubstringSearchIsRefusedUpFrontByItsWorkCharge, now
        // pinned against a "charge AFTER the search" mutant: with the work paid up
        // front the run takes about a millisecond, while actually searching a
        // 131,073-character needle in a 393,216-character text took ~11 s on the
        // controller's machine - far past this timeout
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run("s = \"x\"\nfor i in range(17):\n    s = s + s\n"
                                        + "p = s + \"b\"\nt = s + s + s\nx = p in t\n",
                                new RecordingPlanApi(), PlanRunLimits.DEFAULT)));
        assertEquals("line 6: construction script exceeded 100000 steps", e.getMessage());
    }

    // ---- bounded equality, membership and hashing (H-1b) ----

    @Test
    void planEqualityAnswersMatchFarmEqualityExactly() {
        // one script printing a table of comparisons, run by BOTH a farm interpreter
        // (Object.equals) and a plan interpreter (planEquals): the printed columns must
        // be identical, pinning "same answers as Object.equals" for every value type
        // the language has. The middle column is hand-derived - a NaN case is missing
        // only because the language cannot produce one (division by zero is refused)
        String script = "print(1 == 1.0)\n"                    // True - all numbers are doubles
                + "print(0.5 == 0.5)\n"                        // True
                + "print(\"a\" == \"a\")\n"                    // True
                + "print(\"a\" == \"b\")\n"                    // False
                + "print(True == False)\n"                     // False
                + "print(None == None)\n"                      // True - the singleton
                + "print(None == 0)\n"                         // False
                + "print([1, [2, 3]] == [1, [2, 3]])\n"        // True
                + "print([1, [2, 3]] == [1, [2, 4]])\n"        // False
                + "print({\"a\": [1]} == {\"a\": [1]})\n"      // True - dict values recurse
                + "print({\"a\": [1]} == {\"a\": [2]})\n"      // False
                + "print({\"a\": 1} == {\"b\": 1})\n"          // False - keys differ
                + "print({1, 2} == {2, 1})\n"                  // True - set order is free
                + "print({1, 2} == {1, 3})\n"                  // False
                + "print([1] == {1})\n"                        // False - a list is never a set
                + "print([] == {})\n"                          // False - a list is never a dict
                + "print(1 == \"1\")\n"                        // False - no coercion
                + "print(-0.0 == 0.0)\n"                       // False - Double.equals tells -0.0 from 0.0
                + "print(1 != 2)\n"                            // True
                + "print([1] != [1])\n";                       // False - equal lists
        List<String> expected = List.of(
                "True", "True", "True", "False", "False", "True", "False", "True", "False", "True",
                "False", "False", "True", "False", "False", "False", "False", "False", "True", "False");
        FakeDroneApi farm = new FakeDroneApi(5);
        new Interpreter(farm).run(new Parser(new Lexer(script).scan()).parseProgram());
        assertEquals(expected, farm.printed, "farm output (the Object.equals oracle)");
        RecordingPlanApi plan = new RecordingPlanApi();
        run(script, plan);
        assertEquals(farm.printed, plan.printed, "plan output must equal the farm's");
        // functions compare through Object.equals too (a record's fields, not identity)
        String functions = "def f():\n    pass\ndef g():\n    pass\nprint(f == f)\nprint(f == g)\n";
        FakeDroneApi farmFns = new FakeDroneApi(5);
        new Interpreter(farmFns).run(new Parser(new Lexer(functions).scan()).parseProgram());
        RecordingPlanApi planFns = new RecordingPlanApi();
        run(functions, planFns);
        assertEquals(farmFns.printed, planFns.printed);
        // a semaphore cannot be created in plan mode, so it has no case here
    }

    @Test
    void aSharedNestEqualityPaysItsNodeVisitsAsSteps() {
        // the measured bomb: two separately built shared-reference nests. k=10 is
        // 2^11 - 1 = 2,047 node visits (one whole extra step, carry 1,023) and answers
        // True quickly;
        // k=30 would need ~2^31 visits, so the 1,000-step budget's 1,024,000 charged
        // work units run out at the == line instead - in milliseconds, not the ~18 s
        // the uncharged Object.equals measured
        RecordingPlanApi api = new RecordingPlanApi();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> run("a = [1]\nb = [1]\nfor i in range(10):\n    a = [a, a]\n    b = [b, b]\n"
                                + "print(a == b)\n",
                        api, new PlanRunLimits(1_000, 60_000)));
        assertEquals(List.of("True"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run("a = [1]\nb = [1]\nfor i in range(30):\n    a = [a, a]\n    b = [b, b]\n"
                                        + "print(a == b)\n",
                                new RecordingPlanApi(), new PlanRunLimits(1_000, 60_000))));
        assertEquals("line 6: construction script exceeded 1000 steps", e.getMessage());
    }

    @Test
    void aCyclicEqualityIsRefusedByTheCompareDepthLimitNotByTheStack() {
        // a = []; a.append(a) makes a list containing itself: Object.equals recursed
        // until a StackOverflowError - an Error whose depth depends on -Xss, so not
        // even deterministic. planEquals refuses at the compare depth limit instead
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("a = []\na.append(a)\nb = []\nb.append(b)\nprint(a == b)\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 5: construction script exceeded the compare depth limit of 64", e.getMessage());
    }

    @Test
    void equalityAtExactlyTheCompareDepthLimitRunsAndDeeperIsRefused() {
        // c = [c] repeated 200 times builds a 200-level linear nest - no sharing, so
        // the node count stays tiny: it is the DEPTH that is refused, not the work.
        // Exactly 64 levels still compares (the limit is "deeper than 64")
        String prefix = "c1 = 1\nfor i in range(%d):\n    c1 = [c1]\nc2 = 1\nfor i in range(%d):\n    c2 = [c2]\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(String.format(prefix, 64, 64) + "print(c1 == c2)\n", api, PlanRunLimits.DEFAULT);
        assertEquals(List.of("True"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(String.format(prefix, 200, 200) + "x = c1 == c2\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 7: construction script exceeded the compare depth limit of 64", e.getMessage());
    }

    private static Stream<Arguments> unhashableUses() {
        return Stream.of(
                Arguments.of("a set literal element", "x = {[1]}\n", "a list"),
                Arguments.of("a set literal element (a set)", "x = {{1}}\n", "a set"),
                Arguments.of("a dict literal key", "x = {[1]: 2}\n", "a list"),
                Arguments.of("a dict literal key (a dict)", "x = {{\"a\": 1}: 2}\n", "a dict"),
                Arguments.of("set() of a list of lists", "x = set([[1]])\n", "a list"),
                Arguments.of("set.add", "s = {1}\ns.add([1])\n", "a list"),
                Arguments.of("set.remove", "s = {1}\ns.remove([1])\n", "a list"),
                Arguments.of("a dict item assignment", "d = {}\nd[[1]] = 2\n", "a list"),
                Arguments.of("a dict index read", "d = {}\nx = d[[1]]\n", "a list"),
                Arguments.of("dict.get", "d = {}\nx = d.get([1])\n", "a list"),
                Arguments.of("dict.remove", "d = {\"a\": 1}\nd.remove([1])\n", "a list"),
                Arguments.of("'in' on a set", "x = [1] in {1}\n", "a list"),
                Arguments.of("'in' on a dict", "x = [1] in {\"a\": 1}\n", "a list"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unhashableUses")
    void aCollectionUsedAsASetElementOrDictKeyIsRefused(String site, String script, String typeName) {
        MicraLangException e = assertThrows(MicraLangException.class,
                () -> run(script, new RecordingPlanApi()));
        assertTrue(e.getMessage().contains(typeName + " cannot be used as a dict key or set element"),
                site + ": " + e.getMessage());
    }

    @Test
    void scalarAndOtherHashableKeysStillWorkInPlanMode() {
        RecordingPlanApi api = new RecordingPlanApi();
        run("x = 1 in {1, 2}\n"                    // a number probe on a set
                + "y = \"a\" in {\"a\": 1}\n"      // a string probe on a dict
                + "d = {}\nd[\"k\"] = [1, 2]\n"    // a list is fine as a VALUE
                + "d[None] = 3\nd[True] = 4\n"     // None and booleans stay legal keys
                + "def f():\n    pass\nd[f] = 5\n" // so does a function
                + "s = {1}\ns.add(2)\ns.remove(2)\n"
                + "print(x)\nprint(y)\nprint(d[\"k\"])\nprint(d[None])\nprint(d[True])\nprint(d[f])\n", api);
        assertEquals(List.of("True", "True", "[1, 2]", "3", "4", "5"), api.printed);
    }

    @Test
    void aListMayStillBeProbedInsideAList() {
        // a list is unhashable but NOT un-comparable: `in` on a list goes through
        // planEquals, so nested list membership and nested list.remove keep working
        RecordingPlanApi api = new RecordingPlanApi();
        run("x = [1] in [[1], [2]]\ny = [1] in [[2], [3]]\nl = [[1], [2]]\nl.remove([1])\n"
                + "print(x)\nprint(y)\nprint(l)\n", api);
        assertEquals(List.of("True", "False", "[[2]]"), api.printed);
    }

    @Test
    void collectionsAsKeysAndSetMembersStillWorkForFarmScripts() {
        // farm mode keeps today's behaviour: Java collections happily hash other
        // collections, so a farm script can still use a list as a set element or a
        // dict key - the plan-mode ban exists for the bounded accounting, not for Java
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer(
                "s = {[1]}\nd = {}\nd[[1]] = 2\nx = [1] in s\nprint(len(s))\nprint(d[[1]])\nprint(x)\n")
                .scan()).parseProgram());
        assertEquals(List.of("1", "2", "True"), api.printed);
    }

    @Test
    void listMembershipChargesEachElementCompareAsWork() {
        // a list of 1,000 strings of 4,096 characters each, probed by an equal-length
        // string that differs from every element ONLY in its last character (so a real
        // String.equals scan is as long as the charged one): each element compare is
        // 1 node visit + a 4,096-character scan = 4,097 work units, and the whole `in`
        // pays 1,000 x 4,097 = 4,097,000 units = 4,000 whole quanta (4,000 x 1,024 =
        // 4,096,000, remainder 1,000 carried) = exactly 4,000 extra steps
        String script = "s = \"x\"\nfor i in range(12):\n    s = s + s\n"       // s = 4,096 'x's
                + "c = list(s)\nc[4095] = \"y\"\np = \"\"\nfor ch in c:\n    p = p + ch\n" // p = s with a 'y' tail
                + "l = []\nfor i in range(1000):\n    l.append(s)\n"
                + "x = p in l\nmood(\"ok\")\n";
        // statements cost 1 + (1 + 12x2) + 1 + 1 + 1 + (1 + 4,096x2) + 1 + (1 + 1,000x2)
        // = 10,224 before the `in`; the `in` statement adds 1 + 4,000 = 4,001 -> 14,225,
        // and mood() one more -> 14,226
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(14_226, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // one step less and the thousandth element compare is refused mid-statement
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(14_224, 60_000)));
        assertEquals("line 12: construction script exceeded 14224 steps", e.getMessage());
        // at exactly 14,225 the search finishes but the mood call needs one more
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(14_225, 60_000)));
        assertEquals("line 13: construction script exceeded 14225 steps", e.getMessage());
    }

    @Test
    void listRemoveFindsTheFirstEqualElementAndKeepsTheOldMissMessage() {
        // plan-mode remove(x) scans for the first index planEquals to x and removes by
        // position; a miss keeps the old "is not in this list" text
        RecordingPlanApi api = new RecordingPlanApi();
        run("l = [1, 2, 1]\nl.remove(1)\nprint(l)\n", api);
        assertEquals(List.of("[2, 1]"), api.printed);
        MicraLangException e = assertThrows(MicraLangException.class,
                () -> run("l = [1, 2]\nl.remove(3)\n", new RecordingPlanApi()));
        assertEquals("line 2: 3 is not in this list", e.getMessage());
    }

    // ---- the six unpinned paths the H-1b mutants survived: !=, deep farm compares, ----
    // ---- set/dict size work, dict value depth, and the list.remove scan          ----

    @Test
    void aSharedNestInequalityPaysItsNodeVisitsAsSteps() {
        // the measured bomb through `!=` (the == case is pinned above): k=10 is
        // 2^11 - 1 = 2,047 node visits and answers False quickly (equal nests);
        // k=30 would need ~2^31 visits, so the 1,000-step budget's 1,024,000
        // charged work units run out at the != line instead
        RecordingPlanApi api = new RecordingPlanApi();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> run("a = [1]\nb = [1]\nfor i in range(10):\n    a = [a, a]\n    b = [b, b]\n"
                                + "print(a != b)\n",
                        api, new PlanRunLimits(1_000, 60_000)));
        assertEquals(List.of("False"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run("a = [1]\nb = [1]\nfor i in range(30):\n    a = [a, a]\n    b = [b, b]\n"
                                        + "print(a != b)\n",
                                new RecordingPlanApi(), new PlanRunLimits(1_000, 60_000))));
        assertEquals("line 6: construction script exceeded 1000 steps", e.getMessage());
    }

    @Test
    void aFarmComparisonDeeperThanThePlanCompareLimitStillRuns() {
        // two equal 100-level linear nests: deeper than the plan compare depth
        // limit of 64, so ANY accidental use of planEquals in farm mode would
        // refuse with the compare-depth message - a farm interpreter keeps plain
        // Object.equals and simply answers True then False
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer(
                "a = [1]\nb = [1]\nfor i in range(100):\n    a = [a]\n    b = [b]\n"
                        + "print(a == b)\nprint(a != b)\n").scan()).parseProgram());
        assertEquals(List.of("True", "False"), api.printed);
    }

    @Test
    void aSetComparisonPaysItsSizeAsWorkSteps() {
        // s1 == s2 over two equal 20,000-element sets spends 1 node visit + the
        // 20,000-element size = 20,001 work units = 19 whole quanta (19 x 1,024 =
        // 19,456, remainder 545 carried) = exactly 19 extra steps - so a removed
        // size charge would leave the script 19 steps cheaper. Statements before
        // the compare: 1 + 1 + (1 + 20,000x2) x 2 = 80,004; the compare line ends
        // at 80,024 and print lands at 80,025
        String seed = "s1 = set()\ns2 = set()\nfor i in range(20000):\n    s1.add(i)\n"
                + "for i in range(20000):\n    s2.add(i)\n";
        String script = seed + "x = s1 == s2\nprint(x)\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(80_025, 60_000));
        assertEquals(List.of("True"), api.printed);
        // one step less and the compare's nineteenth quantum leaves nothing for print
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(80_024, 60_000)));
        assertEquals("line 8: construction script exceeded 80024 steps", e.getMessage());
        // two less and the compare itself is refused mid-statement
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(80_023, 60_000)));
        assertEquals("line 7: construction script exceeded 80023 steps", e.getMessage());
    }

    @Test
    void aDictComparisonPaysItsSizeAndValueVisitsAsWorkSteps() {
        // d1 == d2 over two equal 20,000-entry dicts spends 1 node visit + the
        // 20,000-entry size + one node visit per value pair = 40,001 work units
        // = 39 whole quanta (39 x 1,024 = 39,936, remainder 65 carried) = exactly
        // 39 extra steps. Same statement accounting as the set case: 80,004
        // before the compare, the compare line ends at 80,044, print lands at
        // 80,045
        String seed = "d1 = {}\nd2 = {}\nfor i in range(20000):\n    d1[i] = i\n"
                + "for i in range(20000):\n    d2[i] = i\n";
        String script = seed + "x = d1 == d2\nprint(x)\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(80_045, 60_000));
        assertEquals(List.of("True"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(80_044, 60_000)));
        assertEquals("line 8: construction script exceeded 80044 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(80_043, 60_000)));
        assertEquals("line 7: construction script exceeded 80043 steps", e.getMessage());
    }

    @Test
    void aCyclicDictIsRefusedByTheCompareDepthLimitNotByTheStack() {
        // the dict counterpart of the cyclic-list case above: d1["k"] = d1 walks
        // into its own value forever, and the compare depth limit refuses it
        // deterministically instead of a -Xss-dependent StackOverflowError
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("d1 = {}\nd1[\"k\"] = d1\nd2 = {}\nd2[\"k\"] = d2\nprint(d1 == d2)\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 5: construction script exceeded the compare depth limit of 64", e.getMessage());
    }

    @Test
    void dictNestingAtExactlyTheCompareDepthLimitRunsAndDeeperIsRefused() {
        // x = {} then n times x = {"k": x}: a Map's values compare at depth + 1,
        // so the innermost {} pair sits at depth n - 64 wrappings is exactly the
        // limit and answers True, 65 is refused. A linear chain keeps the node
        // count tiny: it is the DEPTH that is refused, not the work
        String prefix = "x1 = {}\nfor i in range(%d):\n    x1 = {\"k\": x1}\n"
                + "x2 = {}\nfor i in range(%d):\n    x2 = {\"k\": x2}\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(String.format(prefix, 64, 64) + "print(x1 == x2)\n", api, PlanRunLimits.DEFAULT);
        assertEquals(List.of("True"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(String.format(prefix, 65, 65) + "x = x1 == x2\n",
                        new RecordingPlanApi(), PlanRunLimits.DEFAULT));
        assertEquals("line 7: construction script exceeded the compare depth limit of 64", e.getMessage());
    }

    @Test
    void listRemovePaysItsElementScanAsSteps() {
        // l.remove(b) on the measured shared nests: remove scans for the first
        // element planEquals to the argument - the same bounded walk as `in` -
        // so the 30-level pair is refused at the remove line instead of running
        // ~18 s of unbounded Object.equals, and the equal 10-level pair is found
        // and removed (the list ends empty)
        RecordingPlanApi api = new RecordingPlanApi();
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> run("a = [1]\nb = [1]\nfor i in range(10):\n    a = [a, a]\n    b = [b, b]\n"
                                + "l = [a]\nl.remove(b)\nprint(len(l))\n",
                        api, new PlanRunLimits(1_000, 60_000)));
        assertEquals(List.of("0"), api.printed);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run("a = [1]\nb = [1]\nfor i in range(30):\n    a = [a, a]\n    b = [b, b]\n"
                                        + "l = [a]\nl.remove(b)\n",
                                new RecordingPlanApi(), new PlanRunLimits(1_000, 60_000))));
        assertEquals("line 7: construction script exceeded 1000 steps", e.getMessage());
    }

    // ---- the run-wide work carry: sub-quantum charges accumulate (H-1c-1) ----

    @Test
    void twoSubQuantumChargesAddUpToOneStepThroughTheCarry() {
        // a 31-character needle in a 63-character text costs (63 - 31 + 1) x 31 =
        // 33 x 31 = 1,023 work units - one unit under the 1,024 quantum, so ONE
        // search charges zero steps. But the second search's 1,023 units combine
        // with the first's leftover in the run-wide carry: 2,046 units pay 1 step
        // (remainder 1,022), so the two-search script needs one step MORE than its
        // three statements - the dropped-remainder hole the carry closes
        String needle = "n".repeat(31);
        String search = "\"" + needle + "\" in \"" + "x".repeat(63) + "\"";
        RecordingPlanApi api = new RecordingPlanApi();
        // one search alone: 1 statement + 0 work steps = 1 step, mood() a second
        run("x = " + search + "\nmood(\"ok\")\n", api, new PlanRunLimits(2, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // two searches: 1 + (1 + 1 work step) + 1 = 4 steps
        api = new RecordingPlanApi();
        run("x = " + search + "\ny = " + search + "\nmood(\"ok\")\n", api,
                new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // at 3 the carried step leaves nothing for mood(), at 2 the second
        // search's own line trips the limit mid-statement
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run("x = " + search + "\ny = " + search + "\nmood(\"ok\")\n",
                        new RecordingPlanApi(), new PlanRunLimits(3, 60_000)));
        assertEquals("line 3: construction script exceeded 3 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run("x = " + search + "\ny = " + search + "\nmood(\"ok\")\n",
                        new RecordingPlanApi(), new PlanRunLimits(2, 60_000)));
        assertEquals("line 2: construction script exceeded 2 steps", e.getMessage());
    }

    @Test
    void aListMembershipScanPaysItsElementComparesThroughTheCarry() {
        // the reviewer's second repro: 20,000 references to one 2,049-character
        // string, probed by an equal-length string differing in the last
        // character, so every element compare is 1 + 2,049 = 2,050 work units.
        // Under the dropped-remainder design each compare flushed 2,050 / 4,096 =
        // 0 steps and the scan charged nothing at all (measured: ~20 s before
        // the wall clock noticed). Now one `p in l` pays 20,000 x 2,050 =
        // 41,000,000 units = 40,039 steps (40,039 x 1,024 = 40,999,936, the
        // 64-unit remainder carried), so the SECOND probe of the 41-term or-chain
        // already lands the statement at 40,031 + 2 x 40,039 = 120,109 - past
        // the 100,000-step budget mid-statement, and the STEP limit, not the
        // 5,000 ms clock, ends the run at line 10
        String script = "s = \"x\"\nfor i in range(11):\n    s = s + s\n"
                + "base = s + \"c\"\np = s + \"b\"\nl = []\n"
                + "for i in range(20000):\n    l.append(base)\n"
                + "while True:\n    x = " + "(p in l) or ".repeat(40) + "(p in l)\n";
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run(script, new RecordingPlanApi(), PlanRunLimits.DEFAULT)));
        assertEquals("line 10: construction script exceeded 100000 steps", e.getMessage());
    }

    // ---- string keys pay for their hash probes (H-1c-2) ----

    @Test
    void aStringSetProbePaysForScanningTheKey() {
        // `k in s` on a 1-element set probes the hash bucket: a 1,023-character
        // key is charged (1,023 + 1) x (1 + ceilLog2(1 + 1)) = 1,024 x 2 = 2,048
        // work units = exactly 2 steps on top of the statement step - the
        // equals scan of the key plus the colliding-bucket depth. A number key
        // hashes without scanning anything: zero extra steps
        String script = "k = \"" + "x".repeat(1_023) + "\"\ns = {1}\nx = k in s\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(6, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(5, 60_000)));
        assertEquals("line 4: construction script exceeded 5 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(4, 60_000)));
        assertEquals("line 3: construction script exceeded 4 steps", e.getMessage());
        // the same script probing with a number fits its 4-statement budget exactly
        api = new RecordingPlanApi();
        run("k = 7\ns = {1}\nx = k in s\nmood(\"ok\")\n", api, new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    /**
     * Every plan-mode site that probes or inserts a String key, as the tail of a
     * script that starts by binding {@code k} and ends in {@code mood("ok")}.
     * The third column is the exact step count of the NUMBER-key twin (each
     * statement costs 1, number probes cost nothing).
     */
    private static Stream<Arguments> stringKeyProbeSites() {
        return Stream.of(
                Arguments.of("'in' on a set", "s = {1}\nx = k in s\n", 4),
                Arguments.of("'in' on a dict", "d = {1: 2}\nx = k in d\n", 4),
                Arguments.of("a dict index read", "d = {}\nd[k] = 1\nx = d[k]\n", 5),
                Arguments.of("a dict item assignment", "d = {}\nd[k] = 1\n", 4),
                Arguments.of("set.add", "s = set()\ns.add(k)\n", 4),
                Arguments.of("set.remove", "s = set()\ns.add(k)\ns.remove(k)\n", 5),
                Arguments.of("dict.get", "d = {}\nd[k] = 1\nx = d.get(k)\n", 5),
                Arguments.of("dict.remove", "d = {}\nd[k] = 1\nx = d.remove(k)\n", 5),
                Arguments.of("a dict literal key", "d = {k: 1}\n", 3),
                Arguments.of("a set literal element", "s = {k}\n", 3),
                Arguments.of("set() of a list element", "s = set([k])\n", 3),
                Arguments.of("== on two sets", "s1 = {k}\ns2 = {k}\nx = s1 == s2\n", 5),
                Arguments.of("== on two dicts", "d1 = {k: 1}\nd2 = {k: 1}\nx = d1 == d2\n", 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringKeyProbeSites")
    void everyStringKeyProbePaysForScanningTheKey(String site, String uses, int twinSteps) {
        // the number-key twin of each script fits its exact statement budget;
        // the 4,095-character-key version must need at least one step MORE -
        // so the twin's exact budget already refuses it
        String probed = "k = \"" + "x".repeat(4_095) + "\"\n" + uses + "mood(\"ok\")\n";
        String numeric = "k = 7\n" + uses + "mood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(numeric, api, new PlanRunLimits(twinSteps, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(probed, new RecordingPlanApi(), new PlanRunLimits(twinSteps, 60_000)),
                site);
        assertTrue(e.getMessage().endsWith("construction script exceeded " + twinSteps + " steps"),
                site + ": " + e.getMessage());
    }

    @Test
    void aDictInsertAndASetAddPayExactlyOneStepIntoAnEmptyContainer() {
        // probing an EMPTY container costs (key.length() + 1) x (1 + ceilLog2(1))
        // = 1,024 x 1 = 1,024 units = exactly ONE step for the 1,023-character
        // key: the tree depth factor is 1, only the equals scan is paid
        String key = "x".repeat(1_023);
        // k = ... (1), d = {} (1), d[k] = 1 (1 + 1 work step) = 4, mood() = 5
        String script = "k = \"" + key + "\"\nd = {}\nd[k] = 1\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(5, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(4, 60_000)));
        assertEquals("line 4: construction script exceeded 4 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(3, 60_000)));
        assertEquals("line 3: construction script exceeded 3 steps", e.getMessage());
        // s.add(k) into an empty set is the same 1-step probe
        String setScript = "k = \"" + key + "\"\ns = set()\ns.add(k)\nmood(\"ok\")\n";
        api = new RecordingPlanApi();
        run(setScript, api, new PlanRunLimits(5, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        e = assertThrows(PlanLimitException.class,
                () -> run(setScript, new RecordingPlanApi(), new PlanRunLimits(4, 60_000)));
        assertEquals("line 4: construction script exceeded 4 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(setScript, new RecordingPlanApi(), new PlanRunLimits(3, 60_000)));
        assertEquals("line 3: construction script exceeded 3 steps", e.getMessage());
    }

    @Test
    void aSetEqualityOfHashCollidingStringKeysIsRefusedByTheStepLimit() {
        // the reviewer's second bomb: 1,024 keys of 4,116 characters sharing one
        // hashCode - a 4,096-character prefix plus ten blocks of "Aa"/"BB"
        // ('B'-'A' = +1 and 'B'-'a' = -31 cancel in the polynomial hash, so any
        // single-block swap leaves the hashCode unchanged; 10 positions give
        // 2^10 colliding keys and the hash bucket degenerates to a tree).
        // Building the sets probes each add: 2 x 4,117 x sum_{i=0..1023}(1 +
        // ceilLog2(i + 1)) = 2 x 4,117 x 10,241 = 84,324,394 units = 82,348 steps
        // plus a 42-unit carry, on top of 6,187 statement steps = 88,535.
        // Then ONE `a == b` pays 1 + 1,024 probes of (4,116 + 1) x (1 +
        // ceilLog2(1,025)) = 4,117 x 12 = 49,404 units each plus the
        // 1,024-element size: 50,590,721 units + the 42-unit carry = 49,405
        // more steps - almost half the default budget for a single comparison
        String seed = "p = \"" + "x".repeat(4_096) + "\"\nkeys = [\"\"]\n"
                + "for i in range(10):\n    nxt = []\n    for k in keys:\n"
                + "        nxt.append(k + \"Aa\")\n        nxt.append(k + \"BB\")\n"
                + "    keys = nxt\n"
                + "a = set()\nb = set()\nfor k in keys:\n    a.add(p + k)\n    b.add(p + k)\n";
        // a budget just 10,000 steps above the 88,535-step preamble refuses the
        // one comparison on its own line - so it costs more than 10,000 steps
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(seed + "x = a == b\nmood(\"ok\")\n", new RecordingPlanApi(),
                        new PlanRunLimits(98_535, 60_000)));
        assertEquals("line 14: construction script exceeded 98535 steps", e.getMessage());
        // exactly: the comparison ends line 14 at 137,941, so 137,940 refuses it
        // mid-statement, 137,941 leaves nothing for mood(), and 137,942 runs
        RecordingPlanApi api = new RecordingPlanApi();
        run(seed + "x = a == b\nmood(\"ok\")\n", api, new PlanRunLimits(137_942, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        e = assertThrows(PlanLimitException.class,
                () -> run(seed + "x = a == b\nmood(\"ok\")\n", new RecordingPlanApi(),
                        new PlanRunLimits(137_941, 60_000)));
        assertEquals("line 15: construction script exceeded 137941 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(seed + "x = a == b\nmood(\"ok\")\n", new RecordingPlanApi(),
                        new PlanRunLimits(137_940, 60_000)));
        assertEquals("line 14: construction script exceeded 137940 steps", e.getMessage());
        // the reviewer's loop bomb: under the default limits the STEP counter
        // ends it - in the old code a chain of these ran 62.6 s on the clock
        e = assertThrows(PlanLimitException.class,
                () -> assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> run(seed + "while True:\n    x = a == b\n",
                                new RecordingPlanApi(), PlanRunLimits.DEFAULT)));
        assertEquals("line 15: construction script exceeded 100000 steps", e.getMessage());
    }

    // ---- the remaining probe sites pinned exactly (H-1c test supplement) ----
    //
    // Each of these sites calls chargeHashProbe on a 1-element container, so a
    // 1,023-character key pays (1,023 + 1) x (1 + ceilLog2(1 + 1)) = 1,024 x 2
    // = 2,048 work units = exactly 2 steps on top of the statement step. The
    // script that builds the 1-element container pays its own probe into the
    // EMPTY container: 1,024 x (1 + ceilLog2(1)) = 1,024 units = 1 step, so
    // every script below costs 1 (k) + (1 + 1) (build) + (1 + 2) (probe under
    // test) + 1 (mood) = 7 steps. The number-key twin pays no probe at all:
    // 4 statements = 4 steps exactly.

    @Test
    void aSetRemovePaysForProbingTheKey() {
        String script = "k = \"" + "x".repeat(1_023) + "\"\ns = {k}\ns.remove(k)\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(7, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        // at 6 the probe's second step leaves nothing for mood(); at 5 the
        // probe charge on line 3 itself overshoots the budget mid-statement
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(6, 60_000)));
        assertEquals("line 4: construction script exceeded 6 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(5, 60_000)));
        assertEquals("line 3: construction script exceeded 5 steps", e.getMessage());
        api = new RecordingPlanApi();
        run("k = 7\ns = {k}\ns.remove(k)\nmood(\"ok\")\n", api, new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    @Test
    void aDictGetPaysForProbingTheKey() {
        String script = "k = \"" + "x".repeat(1_023) + "\"\nd = {k: 1}\nx = d.get(k)\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(7, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(6, 60_000)));
        assertEquals("line 4: construction script exceeded 6 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(5, 60_000)));
        assertEquals("line 3: construction script exceeded 5 steps", e.getMessage());
        api = new RecordingPlanApi();
        run("k = 7\nd = {k: 1}\nx = d.get(k)\nmood(\"ok\")\n", api, new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    @Test
    void aDictRemovePaysForProbingTheKey() {
        String script = "k = \"" + "x".repeat(1_023) + "\"\nd = {k: 1}\nx = d.remove(k)\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(7, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(6, 60_000)));
        assertEquals("line 4: construction script exceeded 6 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(5, 60_000)));
        assertEquals("line 3: construction script exceeded 5 steps", e.getMessage());
        api = new RecordingPlanApi();
        run("k = 7\nd = {k: 1}\nx = d.remove(k)\nmood(\"ok\")\n", api, new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    @Test
    void aDictIndexReadPaysForProbingTheKey() {
        String script = "k = \"" + "x".repeat(1_023) + "\"\nd = {k: 1}\nx = d[k]\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(7, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(6, 60_000)));
        assertEquals("line 4: construction script exceeded 6 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(5, 60_000)));
        assertEquals("line 3: construction script exceeded 5 steps", e.getMessage());
        api = new RecordingPlanApi();
        run("k = 7\nd = {k: 1}\nx = d[k]\nmood(\"ok\")\n", api, new PlanRunLimits(4, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    @Test
    void aDictEqualityPaysForProbingItsKeys() {
        // d1 == d2 runs planEquals' Map branch: spend(1) for the root visit,
        // chargeHashProbe(key, 1) = 2,048 for the single key, spend(1) for the
        // size, and spend(1) for the entry value's own planEqualsAt visit
        // (1 == 1) - 2,051 units = exactly 2 steps (3 units of carry left
        // over). Lines: 1 + (1 + 1) + (1 + 1) + (1 + 2) + 1 = 9 steps; the
        // number-key twin pays 1 + 1 + 1 + (1 + 0) + 1 = 5 (the == itself is
        // 3 units, under one quantum)
        String script = "k = \"" + "x".repeat(1_023) + "\"\nd1 = {k: 1}\nd2 = {k: 1}\nx = d1 == d2\nmood(\"ok\")\n";
        RecordingPlanApi api = new RecordingPlanApi();
        run(script, api, new PlanRunLimits(9, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
        PlanLimitException e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(8, 60_000)));
        assertEquals("line 5: construction script exceeded 8 steps", e.getMessage());
        e = assertThrows(PlanLimitException.class,
                () -> run(script, new RecordingPlanApi(), new PlanRunLimits(7, 60_000)));
        assertEquals("line 4: construction script exceeded 7 steps", e.getMessage());
        api = new RecordingPlanApi();
        run("k = 7\nd1 = {k: 1}\nd2 = {k: 1}\nx = d1 == d2\nmood(\"ok\")\n", api, new PlanRunLimits(5, 60_000));
        assertEquals(List.of("mood ok"), api.calls);
    }

    @Test
    void stringKeyProbesAreFreeForFarmScripts() {
        // no plan limits: the probe charge lands nowhere, so a farm script
        // probes a 4,095-character key at every site and just runs
        String key = "x".repeat(4_095);
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer(
                "k = \"" + key + "\"\ns = {k}\nx = k in s\nd = {}\nd[k] = 1\ny = d[k]\n"
                        + "z = d.get(k)\ns.remove(k)\nd.remove(k)\ns.add(k)\n"
                        + "print(x)\nprint(y)\nprint(z)\n").scan()).parseProgram());
        assertEquals(List.of("True", "1", "1"), api.printed);
    }

    /** A {@link PlanApi} whose {@code print} refuses with a {@link PlanBudgetException}, the way the recorder's budgets do. */
    private static PlanApi budgetRefusingPrintPlanApi() {
        return printRefusingPlanApi(new PlanBudgetException("printed output refused"));
    }

    /** A {@link PlanApi} whose {@code print} throws an ordinary IllegalArgumentException - a bug, not a budget. */
    private static PlanApi refusingPrintPlanApi() {
        return printRefusingPlanApi(new IllegalArgumentException("printed output refused"));
    }

    private static PlanApi printRefusingPlanApi(RuntimeException refusal) {
        return (PlanApi) Proxy.newProxyInstance(PlanApi.class.getClassLoader(), new Class<?>[]{PlanApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("print")) {
                        throw refusal;
                    }
                    return null;
                });
    }

    // ---- the farm path keeps its unlimited parser (P3 H-2) ----

    @Test
    void theUnlimitedParserStillParsesA5000TermChainForFarmScripts() {
        // construction scripts are parsed by Parser(tokens, PLAN_MAX_PARSE_NESTING) and then
        // refused if the tree is deeper than PLAN_MAX_AST_DEPTH; the farm path deliberately
        // keeps the plain Parser(tokens) with no limit, so a 5,000-term chain that no
        // construction script could ever run still parses byte-identically for farm use
        java.util.List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program =
                new Parser(new Lexer("x = " + "1 + ".repeat(4_999) + "1\n").scan()).parseProgram();
        assertEquals(1, program.size());
        // the chain is real - 5,000 terms measure 5,001 deep - so this is not a shrunken tree
        assertEquals(5_001, AstDepth.of(program));
        // and deep PAREN nesting is still legal for the unlimited parser (a chain alone never
        // nests, so it could not catch a wrongly-limited default constructor)
        assertDoesNotThrow(() -> new Parser(new Lexer(
                "x = " + "(".repeat(200) + "1" + ")".repeat(200)).scan()).parseProgram());
    }

    /** A {@link DroneApi} whose {@code print} refuses the way the recorder's output budget does. */
    private static DroneApi refusingPrintDroneApi() {
        return (DroneApi) Proxy.newProxyInstance(DroneApi.class.getClassLoader(), new Class<?>[]{DroneApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("print")) {
                        throw new IllegalArgumentException("printed output refused");
                    }
                    return null;
                });
    }
}
