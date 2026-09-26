package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
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
