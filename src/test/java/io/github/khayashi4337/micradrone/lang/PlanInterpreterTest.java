package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        run("h = \"" + "x".repeat(50) + "\"\n"
                + "l = []\nfor i in range(100):\n    l.append(i)\n"
                + "for i in range(1000):\n    t = h + h\n    c = list(l)\n", api, new PlanRunLimits(100_000, 60_000));
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
}
