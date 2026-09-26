package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanScriptRunnerTest {
    private static PlanScriptRunner.Result run(String... scripts) {
        return PlanScriptRunner.run(List.of(scripts), "patch", 0, "test", PlanRunLimits.DEFAULT);
    }

    private static List<String> codes(PlanScriptRunner.Result r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
    }

    /** The stack the overflow tests run under, so they do not depend on the test JVM's -Xss. */
    private static final long OVERFLOW_TEST_STACK_BYTES = 256L * 1024;

    /**
     * Runs the scripts on a thread with a deliberately small stack: a deeply nested or cyclic
     * script overflows the parser/interpreter deterministically whatever stack size the test
     * JVM itself was started with.
     */
    private static PlanScriptRunner.Result runOnASmallStack(String... scripts) throws InterruptedException {
        PlanScriptRunner.Result[] out = new PlanScriptRunner.Result[1];
        Thread worker = new Thread(null, () -> out[0] = run(scripts), "small-stack", OVERFLOW_TEST_STACK_BYTES);
        worker.start();
        worker.join();
        return out[0];
    }

    @Test
    void aValidScriptBecomesAPatch() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0, 0, 0], {\"width\": 7})\nprint(\"built\")\n");
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(1, r.patch().ops().size());
        assertEquals(List.of("built"), r.printed());
    }

    @Test
    void severalScriptsAreAppendedInOrder() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0, 0, 0], {})", "wall(\"w\", \"hut\", [0, 0, 0], {\"side\": \"north\"})");
        assertEquals(2, r.patch().ops().size());
        assertEquals("hut", ((PlanOp.AddNode) r.patch().ops().get(0)).node().id());
        assertEquals("w", ((PlanOp.AddNode) r.patch().ops().get(1)).node().id());
    }

    @Test
    void forbiddenCommandsAreReportedBeforeAnythingRuns() {
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0,0,0], {})\nharvest()\nx = random()\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-FORBIDDEN", "E-SCRIPT-FORBIDDEN"), codes(r));
        assertEquals("harvest", r.issues().get(0).data().get("name"));
        assertEquals("FARM_COMMAND", r.issues().get(0).data().get("reason"));
        assertEquals("NONDETERMINISTIC", r.issues().get(1).data().get("reason"));
    }

    @Test
    void aMixedScriptIsRefused() {
        PlanScriptRunner.Result r = run("wall(\"w\", None, [0,0,0], {\"side\": \"north\"})\nmove(\"north\")");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-FORBIDDEN"), codes(r));
    }

    @Test
    void syntaxErrorsAreSchemaIssuesWithTheScriptNumberAndLine() {
        // script 3 has a missing ')' on its line 2: the message must pin both
        PlanScriptRunner.Result r = run("mood(\"a\")", "mood(\"b\")", "mood(\"c\")\nwall(\"w\" 5)\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#syntax:3", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト3"), r.issues().get(0).message());
        assertTrue(r.issues().get(0).message().contains("line 2"), r.issues().get(0).message());
    }

    @Test
    void runtimeArgumentErrorsAreSchemaIssues() {
        PlanScriptRunner.Result r = run("wall(\"w\")");
        assertEquals(List.of("E-SCHEMA"), codes(r));
    }

    @Test
    void runawayScriptsHitTheLimit() {
        PlanScriptRunner.Result r = PlanScriptRunner.run(List.of("while True:\n    pass\n"), "p", 0, "t", new PlanRunLimits(1000, 60_000));
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertNull(r.patch());
    }

    @Test
    void anOversizedScriptIsRefused() {
        String big = "# " + "x".repeat(PlanScriptWriter.MAX_SCRIPT_CHARS) + "\n";
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(run(big)));
    }

    @Test
    void theRunnerNeverLeaksAPatchWhenAnyScriptFails() {
        PlanScriptRunner.Result r = run("structure(\"a\", None, [0,0,0], {})", "harvest()");
        assertNull(r.patch());
        assertEquals(IssueCode.E_SCRIPT_FORBIDDEN, r.issues().get(0).code());
    }

    @Test
    void mutatingAScriptListAfterTheCallDoesNotChangeTheRecordedPatch() {
        PlanScriptRunner.Result r = run(
                "docks = [{\"id\": \"dock-1\", \"pad\": [0, 0, 0, 8, 0, 8], \"clearance\": [0, 0, 0, 8, 16, 8], \"approach\": \"north\"}]\n"
                        + "logistics(docks, [], [])\n"
                        + "docks.append({\"id\": \"dock-2\"})\n"
                        + "p = {\"width\": 7}\n"
                        + "wall(\"w\", None, [0, 0, 0], p)\n"
                        + "p[\"depth\"] = 9\n");
        assertTrue(r.ok(), r.issues().toString());
        PlanOp.SetLogistics l = (PlanOp.SetLogistics) r.patch().ops().get(0);
        assertEquals(1, l.logistics().docks().size());
        assertEquals("dock-1", l.logistics().docks().get(0).id());
        PlanOp.AddNode node = (PlanOp.AddNode) r.patch().ops().get(1);
        assertEquals(1, node.node().params().size(), node.node().params().toString());
    }

    @Test
    void nonDataValuesInParamsAreSchemaIssues() {
        // a function, None or a set is not data a plan can hold
        PlanScriptRunner.Result r = run("def f():\n    pass\nwall(\"w\", None, [0,0,0], {\"cb\": f})");
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals(List.of("E-SCHEMA"), codes(run("wall(\"w\", None, [0,0,0], {\"x\": None})")));
        assertEquals(List.of("E-SCHEMA"), codes(run("wall(\"w\", None, [0,0,0], {\"x\": {1, 2}})")));
    }

    @Test
    void aMemoryExhaustingScriptIsALimitIssueNotAnOutOfMemoryError() {
        // doubling a string reaches the string size limit in about twenty iterations
        PlanScriptRunner.Result r = PlanScriptRunner.run(List.of("s = \"x\"\nwhile True:\n    s = s + s\n"),
                "p", 0, "t", PlanRunLimits.DEFAULT);
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertNull(r.patch());
    }

    // ---- deep or huge script values become issues, never Errors (fix round 1) ----

    @Test
    void aCyclicListInParamsIsASchemaIssue() {
        // a.append(a) makes the list contain itself; recording the params must not recurse without end
        PlanScriptRunner.Result r = run("a = []\na.append(a)\nwall(\"w\", None, [0,0,0], {\"x\": a})");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("「x」"), r.issues().get(0).message());
    }

    @Test
    void aSharedReferenceNestInParamsIsASchemaIssue() {
        // each iteration nests the SAME list ten times; walked as a tree this is 10^12 nodes in one call
        PlanScriptRunner.Result r = run("a = [1,2,3,4,5,6,7,8,9,10]\nfor i in range(12):\n    a = [a,a,a,a,a,a,a,a,a,a]\n"
                + "wall(\"w\", None, [0,0,0], {\"x\": a})");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
    }

    @Test
    void anOrdinaryNestedListParamIsStillAccepted() {
        PlanScriptRunner.Result r = run("wall(\"w\", None, [0,0,0], {\"holes\": [[1,2,3,4],[5,6,7,8]]})");
        assertTrue(r.ok(), r.issues().toString());
        PlanOp.AddNode node = (PlanOp.AddNode) r.patch().ops().get(0);
        assertEquals(new ParamValue.ListV(List.of(
                new ParamValue.ListV(List.of(new ParamValue.IntV(1), new ParamValue.IntV(2), new ParamValue.IntV(3),
                        new ParamValue.IntV(4))),
                new ParamValue.ListV(List.of(new ParamValue.IntV(5), new ParamValue.IntV(6), new ParamValue.IntV(7),
                        new ParamValue.IntV(8))))), node.node().params().get("holes"));
    }

    @Test
    void paramsEightListLevelsDeepAreAcceptedAndNineAreRejected() {
        // x = 1 then x = [x] i times builds an i-level nest of one-element lists
        PlanScriptRunner.Result ok = run("x = 1\nfor i in range(8):\n    x = [x]\nwall(\"w\", None, [0,0,0], {\"x\": x})");
        assertTrue(ok.ok(), ok.issues().toString());
        PlanScriptRunner.Result bad = run("x = 1\nfor i in range(9):\n    x = [x]\nwall(\"w\", None, [0,0,0], {\"x\": x})");
        assertNull(bad.patch());
        assertEquals(List.of("E-SCHEMA"), codes(bad));
    }

    @Test
    void paramsOfTenThousandNodesAreAcceptedAndTenThousandAndOneRejected() {
        // a 9,999-element list is 10,000 nodes (the list itself counts one, every scalar counts one)
        PlanScriptRunner.Result ok = run("a = []\nfor i in range(9999):\n    a.append(i)\nwall(\"w\", None, [0,0,0], {\"x\": a})");
        assertTrue(ok.ok(), ok.issues().toString());
        PlanScriptRunner.Result bad = run("a = []\nfor i in range(10000):\n    a.append(i)\nwall(\"w\", None, [0,0,0], {\"x\": a})");
        assertNull(bad.patch());
        assertEquals(List.of("E-SCHEMA"), codes(bad));
    }

    @Test
    void aCyclicListInAConstraintIsASchemaIssue() {
        // the message used to concatenate the value itself; a cyclic list's toString recurses without end
        PlanScriptRunner.Result r = run("a = []\nb = [a]\na.append(b)\n"
                + "connect(\"c\", \"a.p\", \"b.q\", \"item\", None, {\"max_length\": a})");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("list"), r.issues().get(0).message());
    }

    @Test
    void aCyclicListAsAnAnchorComponentIsASchemaIssue() {
        PlanScriptRunner.Result r = run("a = []\na.append(a)\nwall(\"w\", None, [a, 0, 0], {})");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("list"), r.issues().get(0).message());
    }

    @Test
    void aCyclicListEqualityIsADeterministicLimitIssue() {
        // a.append(a) makes each list contain itself; comparing them with == used to
        // recurse into a StackOverflowError (only the runner's catch turned it into an
        // issue, and the overflow depth depended on -Xss - that is why the old version
        // of this test ran on a small-stack thread). Plan-mode equality now refuses a
        // cyclic value deterministically at the compare depth limit, as an ordinary
        // execution-limit issue
        PlanScriptRunner.Result r = run("a = []\na.append(a)\nb = []\nb.append(b)\nprint(a == b)");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("compare"), r.issues().get(0).message());
    }

    // ---- issue keys name the 1-based script number (fix round 1) ----

    @Test
    void issueKeysUseTheOneBasedScriptNumber() {
        String big = "# " + "x".repeat(PlanScriptWriter.MAX_SCRIPT_CHARS) + "\n";
        assertEquals("E-SCRIPT-LIMIT:#length:2", run("mood(\"ok\")", big).issues().get(0).id());
        assertEquals("E-SCHEMA:#run:1", run("wall(\"w\")").issues().get(0).id());
        PlanScriptRunner.Result limit = PlanScriptRunner.run(List.of("while True:\n    pass\n"), "p", 0, "t",
                new PlanRunLimits(1000, 60_000));
        assertEquals("E-SCRIPT-LIMIT:#run:1", limit.issues().get(0).id());
        PlanScriptRunner.Result forbidden = run("x = 1\nharvest()\nx = 2\nharvest()\n");
        assertEquals(List.of("E-SCRIPT-FORBIDDEN", "E-SCRIPT-FORBIDDEN"), codes(forbidden));
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:2:harvest:0", forbidden.issues().get(0).id());
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:4:harvest:1", forbidden.issues().get(1).id());
        assertEquals("2", forbidden.issues().get(0).data().get("line"));
        assertEquals("4", forbidden.issues().get(1).data().get("line"));
    }

    @Test
    void unknownAndReservedNameReasonsComeThroughTheRunner() {
        PlanScriptRunner.Result unknown = run("frobnicate(1)");
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:1:frobnicate:0", unknown.issues().get(0).id());
        assertEquals("UNKNOWN", unknown.issues().get(0).data().get("reason"));
        PlanScriptRunner.Result reserved = run("def random():\n    pass\n");
        assertEquals("RESERVED_NAME", reserved.issues().get(0).data().get("reason"));
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:1:random:0", reserved.issues().get(0).id());
    }

    @Test
    void theStepLimitIsPerScriptNotSharedAcrossThem() {
        // each script runs about sixty thousand steps; a shared hundred-thousand budget would stop the second
        String busy = "for i in range(30000):\n    pass\nmood(\"ok\")\n";
        PlanScriptRunner.Result r = run(busy, busy);
        assertTrue(r.ok(), r.issues().toString());
    }

    @Test
    void aRuntimeFailureInScriptTwoNamesScriptTwo() {
        PlanScriptRunner.Result r = run("mood(\"ok\")", "wall(\"w\")");
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#run:2", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト2"), r.issues().get(0).message());
    }

    @Test
    void aHugeErrorMessageInsideAnIssueIsCutToFourHundredCharacters() {
        // a missing dict key message embeds the key's rendering - here a 524,288-character string
        PlanScriptRunner.Result r = run("s = \"x\"\nfor i in range(19):\n    s = s + s\nd = {}\nx = d[s]\n");
        assertEquals(List.of("E-SCHEMA"), codes(r));
        String message = r.issues().get(0).message();
        assertTrue(message.contains("no key"), message);
        assertTrue(message.endsWith("..."), message);
        assertTrue(message.length() < 500, String.valueOf(message.length()));
    }

    // ---- issue detail cut boundary at exactly 400 characters (fix round 2) ----

    @Test
    void aFourHundredCharacterErrorDetailIsQuotedWholeAndFourHundredOneIsCut() {
        // a missing dict key reads "line 2: no key <key> in this dict" = 15 + key + 13 characters,
        // so 372 x's make the interpreter message exactly 400 characters and 373 make it 401
        String whole = "line 2: no key " + "x".repeat(372) + " in this dict";
        String over = "line 2: no key " + "x".repeat(373) + " in this dict";
        assertEquals(400, whole.length());
        assertEquals(401, over.length());
        PlanScriptRunner.Result exact = run("d = {}\nprint(d[\"" + "x".repeat(372) + "\"])");
        assertEquals(List.of("E-SCHEMA"), codes(exact));
        assertEquals("E-SCHEMA:#run:1", exact.issues().get(0).id());
        String exactMessage = exact.issues().get(0).message();
        assertTrue(exactMessage.endsWith(whole), exactMessage);
        assertFalse(exactMessage.endsWith("..."), exactMessage);
        PlanScriptRunner.Result cut = run("d = {}\nprint(d[\"" + "x".repeat(373) + "\"])");
        assertEquals(List.of("E-SCHEMA"), codes(cut));
        String cutDetail = cut.issues().get(0).message().substring(cut.issues().get(0).message().indexOf("line 2:"));
        assertEquals(over.substring(0, 400) + "...", cutDetail);
        assertEquals(403, cutDetail.length());
    }

    @Test
    void aSyntaxErrorDetailLongerThanFourHundredCharactersIsAlsoCut() {
        // "print(a <500 b's>)": the parser takes "a" as the argument, then reports
        // "expected ')' but found IDENT(bbb...)" - a 538-character interpreter message
        PlanScriptRunner.Result r = run("print(a " + "b".repeat(500) + ")");
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#syntax:1", r.issues().get(0).id());
        String detail = r.issues().get(0).message().substring(r.issues().get(0).message().indexOf("line 1:"));
        assertTrue(detail.startsWith("line 1: expected ')' but found IDENT(bbb"), detail);
        assertTrue(detail.endsWith("..."), detail);
        assertEquals(403, detail.length());
    }

    // ---- run-wide recorder budgets and parser stack overflows are issues, never Errors (fix round 3) ----

    @Test
    void aParamsCopyLoopIsStoppedByTheRecordersRunWideBudget() {
        // R (the controller's measurement): every wall() call records a fresh 9,001-node copy of
        // the same 9,000-element list, so the recorder's cumulative 200,000-element budget
        // refuses the 23rd call (22 x 9,002 = 198,044, +9,002 = 207,046) instead of letting the
        // recorded ops fill the heap. A budget refusal is a limit, not a malformed value, so it
        // is reported as E-SCRIPT-LIMIT
        PlanScriptRunner.Result r = run("a = []\nfor i in range(9000):\n    a.append(i)\n"
                + "while True:\n    wall(\"w\", None, [0,0,0], {\"x\": a})\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("200000"), r.issues().get(0).message());
    }

    @Test
    void aSquaredLogisticsCallIsRefusedQuicklyByTheRecordersBudget() {
        // S (the controller's measurement): docks holds 19,000 references to one dock whose
        // ports holds 19,000 references - reading it the old way materialised 361,000,000
        // PortRefs in a single step. Each dock is charged 1 + 19,000 + 0 = 19,001 before its
        // ports are read, so the 11th dock (190,011 + 19,001 > 200,000) is refused quickly - a
        // budget refusal, reported as E-SCRIPT-LIMIT
        PlanScriptRunner.Result r = run(
                "d = {\"id\": \"x\", \"pad\": [0,0,0,1,1,1], \"clearance\": [0,0,0,1,1,1], \"approach\": \"north\", \"ports\": []}\n"
                        + "for i in range(19000):\n    d[\"ports\"].append(\"n.p\")\n"
                        + "docks = []\nfor i in range(19000):\n    docks.append(d)\n"
                        + "logistics(docks, [], [])\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertTrue(r.issues().get(0).message().contains("200000"), r.issues().get(0).message());
    }

    @Test
    void aNonStringKeyInALogisticsDictIsASchemaIssueNamingTheKey() {
        // the offending key is described safely: the number renders as 1, never a raw object dump
        PlanScriptRunner.Result r = run("logistics([{1: 2}], [], [])");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("「1」"), r.issues().get(0).message());
    }

    @Test
    void aScriptThatNestsTooDeeplyIsALimitIssueNotAnError() throws InterruptedException {
        // T/U/V (the controller's measurements): 4,000 nested parentheses, 4,000 nested brackets
        // and a 9,900-strong unary-minus chain all overflow the recursive-descent parser's stack
        // during parse - before the interpreter ever runs. Each is under 10,000 characters, and
        // each must become an E-SCRIPT-LIMIT issue keyed stack:1, not an escaping Error. The run
        // happens on a small-stack thread so the overflow does not depend on the JVM's -Xss
        List<String> scripts = List.of(
                "x = " + "(".repeat(4_000) + "1" + ")".repeat(4_000),
                "x = " + "[".repeat(4_000) + "]".repeat(4_000),
                "x = " + "-".repeat(9_900) + "1");
        for (String script : scripts) {
            assertTrue(script.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS);
            PlanScriptRunner.Result r = runOnASmallStack(script);
            assertNull(r.patch());
            assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
            assertEquals("E-SCRIPT-LIMIT:#stack:1", r.issues().get(0).id());
            assertTrue(r.issues().get(0).message().contains("スクリプト1"), r.issues().get(0).message());
        }
    }

    @Test
    void aStackOverflowInASecondScriptStillLeavesNoPatch() throws InterruptedException {
        // script 1 is fine, script 2 overflows during parse: script 1's recorded work is
        // discarded (patch null) and the issue names script 2. Same small-stack thread as its
        // sibling overflow tests
        PlanScriptRunner.Result r = runOnASmallStack("mood(\"ok\")",
                "x = " + "(".repeat(4_000) + "1" + ")".repeat(4_000));
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#stack:2", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト2"), r.issues().get(0).message());
    }

    // ---- the fuzz's repeated big list literal is a limit issue, and so is a refused print (fix round 4) ----

    @Test
    void aRepeatedBigListLiteralIsAnAllocationLimitIssueNotAnError() {
        // the controller's fuzz finding: a 9,840-character script that re-evaluates a
        // 4,900-element list literal in a while loop. Each evaluation is charged 4,900 x 3 =
        // 14,700 units (a literal element retains its slot plus a freshly boxed Double, about 24
        // bytes), so the 681st evaluation - 680 x 14,700 = 9,996,000 fits the 10,000,000 budget -
        // is refused by the run-wide counter before the retained lists can exhaust the heap
        PlanScriptRunner.Result r = run("acc = []\nwhile True:\n    acc.append(["
                + "0,".repeat(4_899) + "0])\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertTrue(r.issues().get(0).message().contains("total allocation limit of 10000000"),
                r.issues().get(0).message());
    }

    @Test
    void aPrintPastTheRecordersOutputBudgetIsALimitIssueNotAnInternalError() {
        // s doubles to 524,288 characters; the second print takes the recorder's retained output
        // to 1,048,576 > 1,000,000 characters - an expected limit, so the interpreter wraps the
        // recorder's refusal in a PlanLimitException and the runner reports E-SCRIPT-LIMIT naming
        // the limit and the line, never "想定外"
        PlanScriptRunner.Result r = run("s = \"x\"\n" + "s = s + s\n".repeat(19) + "print(s)\nprint(s)\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        String message = r.issues().get(0).message();
        assertTrue(message.contains("1000000"), message);
        assertTrue(message.contains("line 22"), message);
        assertFalse(message.contains("想定外"), message);
    }

    // ---- the fuzz's split-string escapes are allocation issues, never Errors (fix round 5) ----

    @Test
    void splittingALongStringIntoCharsInALoopIsAnAllocationLimitIssue() {
        // the controller's repro: 16 doublings make a 65,536-character string (charged
        // 2+4+...+65,536 = 131,070); each list(s) materialises 65,536 fresh one-character
        // Strings = 6 x 65,536 = 393,216 units, so the 26th evaluation - 131,070 + 25 x 393,216
        // = 9,961,470 still fits - is refused by the run-wide counter long before the retained
        // lists can exhaust the heap (the fuzz measured an OutOfMemoryError in under a second
        // at -Xmx256m on the old weights). The while loop needs only ~80 statements, so the
        // step limit cannot fire first.
        PlanScriptRunner.Result r = run("s = \"x\"\nfor i in range(16):\n    s = s + s\n"
                + "acc = []\nwhile True:\n    acc.append(list(s))\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertTrue(r.issues().get(0).message().contains("total allocation limit of 10000000"),
                r.issues().get(0).message());
    }

    @Test
    void aSetCopyOfAFewDistinctCharacterStringIsAnAllocationLimitIssue() {
        // the controller's second repro: set(s) deduplicates the string to a 1-element set,
        // but new LinkedHashSet<>(input) sizes its hash table from the 65,536-element input,
        // so each call weighs the split's 5 x 65,536 plus the table's 10 x 65,536 = 983,040
        // units; the 11th call is refused (131,070 + 10 x 983,040 = 9,961,470 fits)
        PlanScriptRunner.Result r = run("s = \"x\"\nfor i in range(16):\n    s = s + s\n"
                + "acc = []\nwhile True:\n    acc.append(set(s))\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertTrue(r.issues().get(0).message().contains("total allocation limit of 10000000"),
                r.issues().get(0).message());
        // the same shape seeded from "ab" doubled 15 times (two distinct characters instead of
        // one) - 15 doublings charge 4+8+...+65,536 = 131,068, each set(s) the same 983,040
        PlanScriptRunner.Result r2 = run("s = \"ab\"\nfor i in range(15):\n    s = s + s\n"
                + "acc = []\nwhile True:\n    acc.append(set(s))\n");
        assertNull(r2.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r2));
        assertTrue(r2.issues().get(0).message().contains("total allocation limit of 10000000"),
                r2.issues().get(0).message());
    }

    // ---- a budget refusal is a limit issue; an ordinary bad value stays a schema issue (fix round 7) ----

    @Test
    void anUnknownLogisticsKeyIsStillASchemaIssueNotALimit() {
        // an unknown dock key is an ordinary IllegalArgumentException from the recorder - a
        // script mistake, not a budget refusal - so it keeps the E-SCHEMA path; only a
        // PlanBudgetException becomes E-SCRIPT-LIMIT
        PlanScriptRunner.Result r = run("logistics([{\"id\": \"d\", \"bogus\": 1}], [], [])");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#run:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("bogus"), r.issues().get(0).message());
    }

    // ---- the anchor's retained ids count against the run-wide character budget (fix round 8) ----

    @Test
    void theControllersAnchorStringReproEndsAsLimitIssues() {
        // the controller's round-8 reproduction verbatim: each script doubles "ā" into a
        // 524,288-character string and hands 16 fresh ~524,289-character copies of it to
        // wall() inside the anchor argument, which the recorder kept in Anchor.OnSurface's
        // nodeId / Anchor.InSlot's slotId without charging them - an OutOfMemoryError at
        // -Xmx256m. Now the first wall call of a script fits (524,289 recorded characters
        // plus a few of id/type/side <= 1,000,000) and its second is refused, so all eight
        // copies end as E-SCRIPT-LIMIT issues and no Error escapes the run
        String preamble = "x = \"ā\"\nfor i in range(19):\n    x = x + x\n";
        List<String> scripts = List.of(
                preamble + "for k in range(16):\n    wall(\"w\", None, [\"surface\", x + str(k), \"outer\", 0, 0], {})\n",
                preamble + "for k in range(16):\n    wall(\"w\", None, [\"slot\", x + str(k)], {})\n");
        for (String script : scripts) {
            PlanScriptRunner.Result r = PlanScriptRunner.run(
                    Collections.nCopies(8, script), "p", 0, "t", PlanRunLimits.DEFAULT);
            assertNull(r.patch());
            assertEquals(8, r.issues().size(), r.issues().toString());
            for (int i = 0; i < r.issues().size(); i++) {
                assertEquals("E-SCRIPT-LIMIT:#run:" + (i + 1), r.issues().get(i).id());
                assertTrue(r.issues().get(i).message().contains("1000000"), r.issues().get(i).message());
            }
        }
    }

    // ---- a single expensive statement pays its worst-case work as steps (P3 H-1a) ----

    @Test
    void aSubstringSearchLoopIsAnExecutionLimitIssue() {
        // the reviewer's loop repro: each `p in t` over a 4,097-character needle in a
        // 12,288-character text is charged (12,288 - 4,097 + 1) x 4,097 = 33,562,624 work
        // units = 8,194 steps up front, so the deterministic step limit - not the wall
        // clock - ends the while loop as an E-SCRIPT-LIMIT issue
        PlanScriptRunner.Result r = run("s = \"x\"\nfor i in range(12):\n    s = s + s\n"
                + "p = s + \"b\"\nt = s + s + s\nwhile True:\n    x = p in t\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("construction script exceeded 100000 steps"),
                r.issues().get(0).message());
    }

    // ---- bounded equality, membership and hashing (P3 H-1b) ----

    @Test
    void aCollectionUsedAsAKeyOrSetMemberIsAnOrdinaryScriptError() {
        // the fuzz's hashing bomb: set.add / d[k] = on a 28-level shared nest used to
        // run ~2^28 hashCode calls inside ONE statement; plan mode refuses collections
        // as keys and set members outright, so the same scripts now die instantly as
        // ordinary E-SCHEMA script errors
        String nest = "a = [1]\nfor i in range(28):\n    a = [a, a]\n";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            PlanScriptRunner.Result viaAdd = run(nest + "s = set()\ns.add(a)\n");
            assertNull(viaAdd.patch());
            assertEquals(List.of("E-SCHEMA"), codes(viaAdd));
            assertEquals("E-SCHEMA:#run:1", viaAdd.issues().get(0).id());
            PlanScriptRunner.Result viaIndex = run(nest + "d = {}\nd[a] = 1\n");
            assertNull(viaIndex.patch());
            assertEquals(List.of("E-SCHEMA"), codes(viaIndex));
            assertEquals("E-SCHEMA:#run:1", viaIndex.issues().get(0).id());
        });
    }

    @Test
    void listMembershipOverSharedNestsIsAnExecutionLimitIssue() {
        // the fuzz's membership bomb: two 28-level shared nests compared by `a in [b]` -
        // planEquals walks the nest node by node and the work charge turns the runaway
        // walk into deterministic steps, so the ~4x10^8-visit step budget ends it as an
        // E-SCRIPT-LIMIT issue inside the default 5-second clock
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            PlanScriptRunner.Result r = run("a = [1]\nb = [1]\nfor i in range(28):\n    a = [a, a]\n    b = [b, b]\n"
                    + "x = a in [b]\n");
            assertNull(r.patch());
            assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
            assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        });
    }
}
