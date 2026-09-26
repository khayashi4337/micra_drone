package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.lang.AstDepth;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlanScriptRunnerTest {
    private static PlanScriptRunner.Result run(String... scripts) {
        return PlanScriptRunner.run(List.of(scripts), "patch", 0, "test", PlanRunLimits.DEFAULT);
    }

    private static List<String> codes(PlanScriptRunner.Result r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
    }

    /** A deliberately tiny CALLER stack: whatever happens inside run() cannot be a stack accident. */
    private static final long TINY_CALLER_STACK_BYTES = 256L * 1024;

    /** A generously large CALLER stack: the other end of the determinism sweep. */
    private static final long HUGE_CALLER_STACK_BYTES = 64L * 1024 * 1024;

    /**
     * Runs the batch through {@link PlanScriptRunner#run} from a CALLER thread with the given
     * stack size. All the run's own work happens on the runner's dedicated worker thread, so
     * the caller's stack must not influence the outcome - that independence is what H-2 pins.
     */
    private static PlanScriptRunner.Result runOnCallerStack(long stackBytes, String... scripts)
            throws InterruptedException {
        PlanScriptRunner.Result[] out = new PlanScriptRunner.Result[1];
        Thread caller = new Thread(null, () -> out[0] = run(scripts), "caller-stack-" + stackBytes,
                stackBytes);
        caller.start();
        caller.join();
        return out[0];
    }

    /**
     * Runs the runner's batch body directly (no dedicated worker) on a thread with the given
     * stack size. The stack-size measurement sweeps this: it must know which outcome a given
     * worker stack would produce, not which stack the production runner picked.
     */
    private static PlanScriptRunner.Result runScriptsOnStack(long stackBytes, String... scripts)
            throws InterruptedException {
        PlanScriptRunner.Result[] out = new PlanScriptRunner.Result[1];
        Thread t = new Thread(null,
                () -> out[0] = PlanScriptRunner.runScripts(List.of(scripts), "patch", 0, "test",
                        PlanRunLimits.DEFAULT),
                "measure-" + stackBytes, stackBytes);
        t.start();
        t.join();
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
    void aForbiddenIdentifierAlmostAsLongAsTheScriptIsCutInIdMessageAndData() {
        // a 9,990-character unknown function name fits the 10,000-character script budget
        // whole, so the profile reports it unshortened - but the ISSUE must not carry it
        // raw: id, message and data.name all keep only the same 60-character prefix. A
        // name that fits stays byte-identical - the exact issue-id assertions above and
        // the short-name data check at the end depend on it
        String hugeName = "f".repeat(9_990);
        PlanScriptRunner.Result r = run("x = " + hugeName + "()\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-FORBIDDEN"), codes(r));
        assertEquals(1, r.issues().size());
        Issue issue = r.issues().get(0);
        String reported = issue.data().get("name");
        assertEquals("f".repeat(60) + "...", reported);
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:1:" + reported + ":0", issue.id());
        assertTrue(issue.id().length() < 120, issue.id());
        assertTrue(issue.message().contains(reported + "は使えません"), issue.message());
        assertTrue(issue.message().length() < 200, issue.message());
        // a name inside the limit is quoted whole - here the same report at 60 exactly
        PlanScriptRunner.Result exact = run("x = " + "g".repeat(60) + "()\n");
        assertEquals("E-SCRIPT-FORBIDDEN:#forbidden:1:1:" + "g".repeat(60) + ":0",
                exact.issues().get(0).id());
        assertEquals("g".repeat(60), exact.issues().get(0).data().get("name"));
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

    // ---- enum refusals never quote the raw script text (final review minors) ----

    /** Long enough that leaking it into an issue would prove the raw script text is quoted. */
    private static final String RAW_ENUM_TEXT = "q".repeat(300);

    private static void assertEnumIssue(String field, List<String> allowed, PlanScriptRunner.Result r) {
        assertEquals(List.of("E-SCHEMA"), codes(r));
        String message = r.issues().get(0).message();
        assertTrue(message.contains(field), field + ": " + message);
        for (String name : allowed) {
            assertTrue(message.contains(name), name + " missing from: " + message);
        }
        assertFalse(message.contains(RAW_ENUM_TEXT), field + ": " + message);
    }

    @Test
    void enumRefusalsAreSchemaIssuesThatNameTheFieldAndAllowedNamesButNotTheRawValue() {
        // Each script-facing enum site gets a refusal naming the field and the allowed lowercase
        // names - never Enum.valueOf's verbatim "No enum constant ..." message. Whichever layer
        // refuses first (the dispatcher's own membership check, or the recorder's parseEnum) is
        // bounded: the dispatcher quotes at most PlanValueText's short prefix, the recorder none.
        assertEnumIssue("向き", List.of("\"north\"", "\"east\"", "\"south\"", "\"west\""),
                run("site(\"d\", 0, 0, 0, \"" + RAW_ENUM_TEXT + "\", [0, 0, 0, 1, 1, 1])"));
        assertEnumIssue("kind", List.of("\"rotation\"", "\"item\"", "\"fluid\"", "\"redstone\"", "\"heat\"", "\"dock\""),
                run("connect(\"c\", \"a.p\", \"b.q\", \"" + RAW_ENUM_TEXT + "\")"));
        assertEnumIssue("approach", List.of("\"north\"", "\"east\"", "\"south\"", "\"west\""),
                run("logistics([{\"id\": \"d\", \"pad\": [0, 0, 0, 8, 0, 8], "
                        + "\"clearance\": [0, 0, 0, 8, 16, 8], \"approach\": \"" + RAW_ENUM_TEXT + "\"}], [], [])"));
        assertEnumIssue("面の側", List.of("\"outer\"", "\"inner\""),
                run("relocate(\"d\", [\"surface\", \"w\", \"" + RAW_ENUM_TEXT + "\", 0, 0])"));
        assertEnumIssue("entry_dirs", List.of("\"up\"", "\"down\"", "\"north\"", "\"east\"", "\"south\"", "\"west\""),
                run("connect(\"c\", \"a.p\", \"b.q\", \"item\", None, {\"entry_dirs\": [\"" + RAW_ENUM_TEXT + "\"]})"));
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
    void aScriptThatNestsTooDeeplyIsRefusedDeterministically() throws InterruptedException {
        // T/U/V (the controller's measurements): 4,000 nested parentheses, 4,000 nested brackets
        // and a 9,900-strong unary-minus chain used to overflow the recursive-descent parser's
        // stack during parse - an outcome that depended on -Xss. The parser's nesting limit now
        // refuses all three as ordinary syntax issues, identically on any caller stack (this
        // runs the whole run() from a 256 KiB caller to prove it)
        List<String> scripts = List.of(
                "x = " + "(".repeat(4_000) + "1" + ")".repeat(4_000),
                "x = " + "[".repeat(4_000) + "]".repeat(4_000),
                "x = " + "-".repeat(9_900) + "1");
        for (String script : scripts) {
            assertTrue(script.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS);
            PlanScriptRunner.Result r = runOnCallerStack(TINY_CALLER_STACK_BYTES, script);
            assertNull(r.patch());
            assertEquals(List.of("E-SCHEMA"), codes(r));
            assertEquals("E-SCHEMA:#syntax:1", r.issues().get(0).id());
            assertTrue(r.issues().get(0).message().contains("nested too deeply (limit 100)"),
                    r.issues().get(0).message());
        }
    }

    @Test
    void aTooDeeplyNestedSecondScriptStillLeavesNoPatch() throws InterruptedException {
        // script 1 is fine, script 2 is refused by the parser's nesting limit: script 1's
        // recorded work is discarded (patch null) and the issue names script 2
        PlanScriptRunner.Result r = runOnCallerStack(TINY_CALLER_STACK_BYTES, "mood(\"ok\")",
                "x = " + "(".repeat(4_000) + "1" + ")".repeat(4_000));
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#syntax:2", r.issues().get(0).id());
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
        // units = 32,776 steps up front, so the deterministic step limit - not the wall
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
        // walk into deterministic steps, so the ~10^8-visit step budget ends it as an
        // E-SCRIPT-LIMIT issue inside the default 5-second clock
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            PlanScriptRunner.Result r = run("a = [1]\nb = [1]\nfor i in range(28):\n    a = [a, a]\n    b = [b, b]\n"
                    + "x = a in [b]\n");
            assertNull(r.patch());
            assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
            assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
        });
    }

    // ---- the run-wide work carry: per-element remainders accumulate (P3 H-1c-1) ----

    @Test
    void aListMembershipScanLoopIsAnExecutionLimitIssue() {
        // the reviewer's zero-step membership bomb: 20,000 references to one
        // 2,049-character string, scanned by an or-chain of `p in l` probes whose
        // 2,050-unit element compares used to flush sub-quantum remainders to
        // zero steps per compare - the whole statement ran ~20 s under the wall
        // clock while the step counter never moved. With the run-wide carry each
        // `in` pays ~40,039 steps, so the deterministic step limit ends the while
        // loop inside the 5-second default as an E-SCRIPT-LIMIT issue
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            PlanScriptRunner.Result r = run("s = \"x\"\nfor i in range(11):\n    s = s + s\n"
                    + "base = s + \"c\"\np = s + \"b\"\nl = []\n"
                    + "for i in range(20000):\n    l.append(base)\n"
                    + "while True:\n    x = " + "(p in l) or ".repeat(40) + "(p in l)\n");
            assertNull(r.patch());
            assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
            assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
            assertTrue(r.issues().get(0).message().contains("construction script exceeded 100000 steps"),
                    r.issues().get(0).message());
        });
    }

    // ---- the finer work quantum keeps refusals fast (P3 H-4b) ----

    @Test
    void aRefusedSharedNestComparisonStaysWellUnderTheClockBackstop() {
        // the finer 1,024-unit quantum makes the refusal cheap enough to pin by
        // clock too: the 100,000-step budget is a bound of ~10^8 charged node
        // visits - about a second of work, versus ~2.4 s for the same refusal at
        // 4,096 units per step - so the run ends far inside the 5,000 ms
        // backstop. The 3 s ceiling keeps a 2x margin on a loaded machine
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            PlanScriptRunner.Result r = run("a = [1]\nb = [1]\nfor i in range(28):\n    a = [a, a]\n    b = [b, b]\n"
                    + "x = a in [b]\n");
            assertNull(r.patch());
            assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
            assertEquals("E-SCRIPT-LIMIT:#run:1", r.issues().get(0).id());
            // pin the REASON, not only the speed: the deterministic step limit
            // decided, not the 5,000 ms clock backstop
            assertTrue(r.issues().get(0).message().contains("steps"),
                    r.issues().get(0).message());
        });
    }

    // ---- the result must not depend on the caller's stack (P3 H-2) ----

    /**
     * The recursive shape the worker stack was first sized against: `f(n + 1)` sits at the
     * bottom of a 196-term left-associative chain. The Call node and its `n + 1` argument
     * add 2 levels, so the chain measures 198 deep, the function body 200 - exactly
     * PLAN_MAX_AST_DEPTH. Called until the interpreter's own call cap of 200, every
     * Micra-level frame keeps ~400 Java frames of pending binary evals live. It is NOT the
     * deepest program the limits allow, though - {@link #worstCaseNestedForScript} puts
     * the {@code for} nest INSIDE {@code f}, so every one of the 200 call levels carries
     * ~96 live {@code for} frames on top of those evals. This plain chain is only the
     * cheaper of the two shapes the stack is sized against.
     */
    private static String worstCaseScript() {
        return "def f(n):\n    return f(n + 1) + " + "1 + ".repeat(194) + "1\nf(0)\n";
    }

    /**
     * The deeper of the two worst-case shapes the worker stack is sized against: 96
     * nested {@code for} blocks INSIDE {@code f}'s body, each iterating once before
     * the innermost one reaches the {@code return} - so every one of the up-to-200
     * nested calls keeps ~96 live {@code for} frames on the worker stack on top of
     * the ~400 Java frames of pending evals {@link #worstCaseScript} already
     * carries. (Wrapping the same blocks around the top-level {@code f(0)} call
     * instead - the shape this helper used to build - enters them once, so they add
     * nothing over the plain chain.) The {@code return} chain has 100 terms: chain
     * depth 102 + {@code return} 1 + 96 {@code for} levels + {@code def} 1 keeps the
     * function at exactly PLAN_MAX_AST_DEPTH. One space of indent per level keeps
     * the whole script near 7,000 characters, well under MAX_SCRIPT_CHARS.
     */
    private static String worstCaseNestedForScript() {
        StringBuilder out = new StringBuilder("def f(n):\n");
        for (int i = 1; i <= 96; i++) {
            out.append(" ".repeat(i)).append("for i in range(1):\n");
        }
        out.append(" ".repeat(97)).append("return f(n + 1) + ")
                .append("1 + ".repeat(98)).append("1\n");
        out.append("f(0)\n");
        return out.toString();
    }

    /**
     * A short description of a run's decisive outcome, for the stack-sweep log.
     */
    private static String outcomeOf(PlanScriptRunner.Result r) {
        if (r.ok()) {
            return "ok";
        }
        if (r.issues().isEmpty()) {
            return "no issue?";
        }
        io.github.khayashi4337.micradrone.build.model.Issue issue = r.issues().get(0);
        if (issue.message().contains("too much recursion")) {
            return "too much recursion";
        }
        if (issue.message().contains("構文木が深すぎます")) {
            return "ast depth refused";
        }
        if (issue.message().contains("nested too deeply")) {
            return "parse nesting refused";
        }
        return issue.id();
    }

    /**
     * The hostile shapes the controller measured, each pinned to its exact expected outcome:
     * a left-associative chain never nests while PARSING, so the parser limit cannot see it
     * and the finished-AST depth limit is what refuses it; parenthesis/bracket/unary/`not`
     * nesting is refused while parsing; a legitimately nested recursion bomb runs and ends at
     * the interpreter's own call cap. Expected ids and message details are asserted exactly,
     * not just compared across stacks.
     */
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> stackIndependentOutcomes() {
        return java.util.stream.Stream.of(
                // 4,900 terms (compact - spaced it would exceed MAX_SCRIPT_CHARS) -> AssignStmt
                // + 4,900 = AST depth 4,901 > 200; parses without nesting, so the AST bound is
                // what refuses it
                org.junit.jupiter.params.provider.Arguments.of("a 4,900-term sum",
                        "x=" + "1+".repeat(4_899) + "1\n",
                        "E-SCHEMA:#syntax:1", "構文木が深すぎます"),
                // 1,600 `and` terms -> AST depth 1,601 > 200
                org.junit.jupiter.params.provider.Arguments.of("a 1,600-term and chain",
                        "x = " + "1 and ".repeat(1_599) + "1\n",
                        "E-SCHEMA:#syntax:1", "構文木が深すぎます"),
                org.junit.jupiter.params.provider.Arguments.of("2,000 nested parentheses",
                        "x = " + "(".repeat(2_000) + "1" + ")".repeat(2_000),
                        "E-SCHEMA:#syntax:1", "nested too deeply (limit 100)"),
                org.junit.jupiter.params.provider.Arguments.of("4,900 unary minuses",
                        "x = " + "-".repeat(4_900) + "1",
                        "E-SCHEMA:#syntax:1", "nested too deeply (limit 100)"),
                org.junit.jupiter.params.provider.Arguments.of("150 nested 'not'",
                        "x = " + "not ".repeat(150) + "True",
                        "E-SCHEMA:#syntax:1", "nested too deeply (limit 100)"),
                // 30 levels of list literal around the recursive call: parses (nesting ~32),
                // shallow AST (~36), runs, and dies at the interpreter's call cap
                org.junit.jupiter.params.provider.Arguments.of(
                        "a recursive function returning a 30-deep list nest",
                        "def f(n):\n    return " + "[".repeat(30) + "f(n + 1)" + "]".repeat(30)
                                + "\nf(0)\n",
                        "E-SCHEMA:#run:1", "too much recursion"),
                // an unexpected throw inside the worker (the recorder refuses a non-string
                // dict key) must surface as the same ordinary issue, not leak a worker Error
                org.junit.jupiter.params.provider.Arguments.of("a recorder refusal",
                        "logistics([{1: 2}], [], [])",
                        "E-SCHEMA:#run:1", "logistics()"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("stackIndependentOutcomes")
    void theSameScriptGivesTheSameOutcomeOnAnyCallerStack(String shape, String script,
            String expectedId, String expectedDetail) throws InterruptedException {
        assertTrue(script.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS,
                shape + " must fit MAX_SCRIPT_CHARS to be a realistic attack");

        PlanScriptRunner.Result tiny = runOnCallerStack(TINY_CALLER_STACK_BYTES, script);
        PlanScriptRunner.Result huge = runOnCallerStack(HUGE_CALLER_STACK_BYTES, script);

        assertNull(tiny.patch(), shape);
        assertNull(huge.patch(), shape);
        assertEquals(tiny.issues(), huge.issues(),
                shape + " - identical issues (ids AND messages) on a 256 KiB vs a 64 MiB caller");
        assertEquals(1, tiny.issues().size(), shape);
        assertEquals(expectedId, tiny.issues().get(0).id(), shape);
        assertTrue(tiny.issues().get(0).message().contains(expectedDetail),
                shape + " - got " + tiny.issues().get(0).message());
    }

    @Test
    void anAstOfDepthExactly200RunsAnd201IsRefused() throws InterruptedException {
        // AstDepth counts the AssignStmt itself, so an n-term `1+1+...+1` chain under `x =`
        // measures n + 1: 199 terms is exactly the 200 limit, 200 terms is one over. Both
        // shapes parse without ever nesting - this is the boundary the parser limit CANNOT
        // see, so the refusal must come from measuring the finished tree
        PlanScriptRunner.Result ok = runOnCallerStack(TINY_CALLER_STACK_BYTES,
                "x = " + "1 + ".repeat(198) + "1\n");
        assertTrue(ok.ok(), ok.issues().toString());

        PlanScriptRunner.Result refused = runOnCallerStack(TINY_CALLER_STACK_BYTES,
                "x = " + "1 + ".repeat(199) + "1\n");
        assertNull(refused.patch());
        assertEquals(List.of("E-SCHEMA"), codes(refused));
        assertEquals("E-SCHEMA:#syntax:1", refused.issues().get(0).id());
        assertTrue(refused.issues().get(0).message().contains("深さ 201 > 上限 200"),
                refused.issues().get(0).message());
    }

    @Test
    void aHundredNestedParenthesesParseAndAHundredAndOneAreRefused() {
        // the parser counts one level per `(`: 100 is the boundary, 101 throws while parsing
        PlanScriptRunner.Result ok = run("x = " + "(".repeat(100) + "1" + ")".repeat(100));
        assertTrue(ok.ok(), ok.issues().toString());

        PlanScriptRunner.Result refused = run("x = " + "(".repeat(101) + "1" + ")".repeat(101));
        assertNull(refused.patch());
        assertEquals(List.of("E-SCHEMA"), codes(refused));
        assertEquals("E-SCHEMA:#syntax:1", refused.issues().get(0).id());
        assertTrue(refused.issues().get(0).message().contains("nested too deeply (limit 100)"),
                refused.issues().get(0).message());
    }

    @Test
    void legitimateDeepScriptsStillRun() {
        // a 150-term chain measures AST depth 151 and a 50-level list nest parses at nesting
        // depth ~51 - comfortably inside both limits, and neither touched by them
        PlanScriptRunner.Result chain = run("x = " + "1 + ".repeat(149) + "1\nmood(\"ok\")\n");
        assertTrue(chain.ok(), chain.issues().toString());

        PlanScriptRunner.Result nest = run("x = " + "[".repeat(50) + "1" + "]".repeat(50)
                + "\nmood(\"ok\")\n");
        assertTrue(nest.ok(), nest.issues().toString());

        // a params argument at the recorder's own 8-level depth limit is still recorded:
        // MAX_PARAM_DEPTH, not the new AST bound, is what governs recorded values
        PlanScriptRunner.Result params = run("wall(\"w\", None, [0,0,0], {\"x\": "
                + "[".repeat(8) + "1" + "]".repeat(8) + "})");
        assertTrue(params.ok(), params.issues().toString());
        assertEquals(1, params.patch().ops().size());
    }

    @Test
    void theDeepestAllowedScriptEndsWithTheRecursionCapOnATinyCallerStack() throws InterruptedException {
        // the deep recursive shape the stack was first sized against (see
        // worstCaseScript): 200 nested calls each
        // carrying a 198-deep eval - roughly 80,000 live Java frames. On an ordinary thread
        // stack that is a StackOverflowError; on the runner's sized worker stack it must end
        // in the interpreter's own "too much recursion" issue, even when the CALLER that
        // asked for the run only had 256 KiB of stack itself
        PlanScriptRunner.Result r = runOnCallerStack(TINY_CALLER_STACK_BYTES, worstCaseScript());
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#run:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("too much recursion"),
                r.issues().get(0).message());
    }

    /**
     * The smallest dedicated stack on which {@code script} reaches its deterministic
     * interpreter cap ("too much recursion") instead of dying in a caught
     * StackOverflowError: sweep by doubling, then bisect (thread stacks are only honoured
     * to OS granularity anyway); every step is printed so the numbers land in the test
     * report.
     */
    private static long firstDeterministicStack(String script) throws InterruptedException {
        long firstDeterministic = -1;
        for (long size = 256L * 1024; size <= 512L * 1024 * 1024; size *= 2) {
            String outcome = outcomeOf(runScriptsOnStack(size, script));
            System.out.println("stack=" + size + " -> " + outcome);
            if ("too much recursion".equals(outcome)) {
                firstDeterministic = size;
                break;
            }
        }
        assertTrue(firstDeterministic > 0,
                "the script never reached the interpreter's recursion cap");

        long lo = firstDeterministic / 2;
        long hi = firstDeterministic;
        while (hi - lo > 256L * 1024) {
            long mid = lo + (hi - lo) / 2;
            String outcome = outcomeOf(runScriptsOnStack(mid, script));
            System.out.println("stack=" + mid + " -> " + outcome);
            if ("too much recursion".equals(outcome)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    @Test
    void theWorkerStackCoversTheWorstCaseWithAFourFoldMargin() throws InterruptedException {
        // the sizing measurement behind PLAN_RUN_STACK_BYTES, taken for BOTH deep shapes:
        // the plain recursive chain, and the deeper 96-nested-`for` nest INSIDE the
        // recursive function, so every call level carries its own ~96 live `for`
        // frames (the chain alone is NOT the deepest permitted program - see
        // worstCaseNestedForScript).
        // On a loaded machine a run can land a few MiB either side of the printed value,
        // so the assertion is only on the required 4x margin, not an exact byte count
        assertTrue(worstCaseNestedForScript().length() <= PlanScriptWriter.MAX_SCRIPT_CHARS,
                "the worst-shape script must fit MAX_SCRIPT_CHARS to be a realistic attack");
        long chainMin = firstDeterministicStack(worstCaseScript());
        long nestedForMin = firstDeterministicStack(worstCaseNestedForScript());
        long measured = Math.max(chainMin, nestedForMin);
        System.out.println("measured minimum: chain=" + chainMin + " nested-for=" + nestedForMin);
        assertTrue(PlanScriptRunner.PLAN_RUN_STACK_BYTES >= 4 * measured,
                "worker stack " + PlanScriptRunner.PLAN_RUN_STACK_BYTES + " is less than 4x the"
                        + " measured minimum " + measured);
    }

    @Test
    void anInterruptOfTheCallerStillReturnsTheResultAndRestoresTheFlag() throws InterruptedException {
        // the caller is interrupted while the worker is still running: join() must keep
        // waiting, hand the result back, and leave the caller's interrupt flag set - the
        // result of a construction run is never thrown away just because somebody poked us.
        // 200,001 loop trips pass the 100,000-step budget, so this ends as E-SCRIPT-LIMIT
        String busy = "x = 0\nwhile x < 200000:\n    x = x + 1\n";
        PlanScriptRunner.Result[] out = new PlanScriptRunner.Result[1];
        boolean[] flagAfter = new boolean[1];
        Thread caller = new Thread(null, () -> {
            // the flag is already set when run() starts, so the FIRST join() is guaranteed to
            // throw InterruptedException while the worker runs - no timing dependence
            Thread.currentThread().interrupt();
            out[0] = run(busy);
            flagAfter[0] = Thread.currentThread().isInterrupted();
        }, "interrupted-caller");
        caller.start();
        // also keep poking mid-run: the run only needs a few ms, and extra interrupts arriving
        // while join() is blocked exercise the same wait-again path
        while (caller.isAlive()) {
            caller.interrupt();
            Thread.sleep(1);
        }
        caller.join();

        assertNotNull(out[0], "the interrupted caller still got the result");
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(out[0]));
        assertTrue(flagAfter[0], "the caller's interrupt flag must be set again after run()");
    }

    // ---- the worker boundary itself: what the body throws comes back unchanged (P3 H-3) ----

    @Test
    void aRuntimeExceptionInsideTheWorkerIsRethrownAsTheSameInstance() {
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException caught = assertThrows(RuntimeException.class,
                () -> PlanScriptRunner.runOnWorker(() -> {
                    throw boom;
                }));
        assertSame(boom, caught);
    }

    @Test
    void anErrorInsideTheWorkerIsRethrownAsTheSameInstance() {
        AssertionError boom = new AssertionError("boom");
        AssertionError caught = assertThrows(AssertionError.class,
                () -> PlanScriptRunner.runOnWorker(() -> {
                    throw boom;
                }));
        assertSame(boom, caught);
    }

    @Test
    void theWorkerHandsItsResultBackToTheCaller() {
        assertEquals("answer", PlanScriptRunner.runOnWorker(() -> "answer"));
    }

    @Test
    void theBodyRunsOnTheDedicatedWorkerThreadNotTheCaller() {
        Thread ran = PlanScriptRunner.runOnWorker(Thread::currentThread);
        assertEquals("micra-construction-script", ran.getName());
        assertNotSame(Thread.currentThread(), ran);
    }

    // ---- one run's heap at a time: the process-wide run permit (P3 H-4c) ----

    /**
     * Waits inside a body on the gate a test uses to keep that body alive. A timeout or an
     * interrupt unwinds the body instead of hanging it, so a broken run can never wedge the
     * permit for the rest of the suite.
     */
    private static void awaitGate(CountDownLatch gate) {
        try {
            assertTrue(gate.await(10, TimeUnit.SECONDS), "the test gate stayed closed forever");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Spins (never sleeps) until {@code caller} is parked inside the run permit's acquire
     * wait - told apart from every other WAITING state (such as the worker's join()) by the
     * {@code java.util.concurrent} frames in its stack. Asserts it actually parked rather
     * than finishing or wedging somewhere else.
     */
    private static void awaitParkedOnThePermit(Thread caller, String who) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!parkedOnThePermit(caller)) {
            assertTrue(caller.isAlive(), who + " finished without ever parking on the permit");
            assertTrue(System.nanoTime() < deadline,
                    who + " never parked on the run permit (state " + caller.getState() + ")");
            Thread.yield();
        }
    }

    /**
     * Whether {@code caller} is parked inside {@code Semaphore.acquireUninterruptibly}: the
     * acquire parks through {@code java.util.concurrent} frames, while the other parking
     * wait inside runOnWorker - the worker's join() - parks through {@code Object.wait}.
     */
    private static boolean parkedOnThePermit(Thread caller) {
        if (caller.getState() != Thread.State.WAITING) {
            return false;
        }
        for (StackTraceElement frame : caller.getStackTrace()) {
            if (frame.getClassName().startsWith("java.util.concurrent")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void twoRunsAtOnceNeverOverlapInsideTheWorker() throws InterruptedException {
        // caller A parks inside its body on a gate; caller B starts while A is still inside
        // and can only enter its own body after A is completely out - the maximum number of
        // bodies live at once must stay 1, because only one run's heap may ever be live
        AtomicInteger inBody = new AtomicInteger();
        AtomicInteger maxInBody = new AtomicInteger();
        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<String> aResult = new AtomicReference<>();
        AtomicReference<String> bResult = new AtomicReference<>();
        CountDownLatch bInside = new CountDownLatch(1);
        Thread a = new Thread(() -> aResult.set(PlanScriptRunner.runOnWorker(() -> {
            maxInBody.accumulateAndGet(inBody.incrementAndGet(), Math::max);
            aInside.countDown();
            awaitGate(gate);
            inBody.decrementAndGet();
            return "a";
        })));
        Thread b = new Thread(() -> bResult.set(PlanScriptRunner.runOnWorker(() -> {
            maxInBody.accumulateAndGet(inBody.incrementAndGet(), Math::max);
            bInside.countDown();
            inBody.decrementAndGet();
            return "b";
        })));
        a.start();
        assertTrue(aInside.await(10, TimeUnit.SECONDS), "A never entered its body");
        b.start();
        // wait until B either queues for its turn (correct: parked on the permit while A
        // is still inside) or has already entered its own body next to A (the overlap
        // being forbidden) - only then is it safe to let A out without making the check
        // depend on how fast B happened to be scheduled
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (bInside.getCount() > 0 && !parkedOnThePermit(b) && b.isAlive()
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(bInside.getCount() == 0 || parkedOnThePermit(b) || !b.isAlive(),
                "B neither queued nor ran its body within 10 s");
        gate.countDown();
        a.join();
        b.join();
        assertEquals("a", aResult.get());
        assertEquals("b", bResult.get());
        assertEquals(1, maxInBody.get(), "the two bodies ran at the same time");
        assertEquals(0, inBody.get());
    }

    @Test
    void waitingCallersGetThePermitInArrivalOrder() throws InterruptedException {
        // the permit is fair: with A parked inside its body and B then C queued behind it,
        // the bodies run in the callers' arrival order. Each waiter is only started after
        // the previous one is OBSERVED parked on the permit, so the queue order B-then-C
        // is real rather than hoped for
        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread a = new Thread(() -> PlanScriptRunner.runOnWorker(() -> {
            aInside.countDown();
            awaitGate(gate);
            order.add("a");
            return null;
        }));
        a.start();
        assertTrue(aInside.await(10, TimeUnit.SECONDS), "A never entered its body");
        Thread b = new Thread(() -> PlanScriptRunner.runOnWorker(() -> {
            order.add("b");
            return null;
        }));
        b.start();
        awaitParkedOnThePermit(b, "B");
        Thread c = new Thread(() -> PlanScriptRunner.runOnWorker(() -> {
            order.add("c");
            return null;
        }));
        c.start();
        awaitParkedOnThePermit(c, "C");
        gate.countDown();
        a.join();
        b.join();
        c.join();
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void aBodyThatThrowsStillReleasesThePermit() {
        // the worker dies mid-run: the caller still gets the failure rethrown, and the next
        // caller is not left parked forever on a permit nobody holds any more
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException caught = assertThrows(RuntimeException.class,
                () -> PlanScriptRunner.runOnWorker(() -> {
                    throw boom;
                }));
        assertSame(boom, caught);
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertEquals("ok", PlanScriptRunner.runOnWorker(() -> "ok")));
    }

    @Test
    void anInterruptedWaiterStillGetsItsResultWithTheFlagSet() throws InterruptedException {
        // caller A parks inside its body holding the permit; caller B queues behind it and
        // is interrupted while parked in the acquire. The poke must not drop the wait: B
        // still gets its own result afterwards, and its interrupt flag is set again by the
        // time runOnWorker returns - the same remember-and-restore the join loop already
        // gives a mid-run interrupt
        CountDownLatch aInside = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicReference<String> aResult = new AtomicReference<>();
        AtomicReference<String> bResult = new AtomicReference<>();
        AtomicBoolean bFlagAfter = new AtomicBoolean();
        Thread a = new Thread(() -> aResult.set(PlanScriptRunner.runOnWorker(() -> {
            aInside.countDown();
            awaitGate(gate);
            return "a";
        })));
        a.start();
        assertTrue(aInside.await(10, TimeUnit.SECONDS), "A never entered its body");
        Thread b = new Thread(() -> {
            bResult.set(PlanScriptRunner.runOnWorker(() -> "b"));
            bFlagAfter.set(Thread.currentThread().isInterrupted());
        });
        b.start();
        awaitParkedOnThePermit(b, "B");
        b.interrupt();
        // A still holds the permit and its gate is still closed, so B can only produce a
        // result by having escaped the acquire wait - there must be none yet
        assertNull(bResult.get(), "an interrupt must not end the acquire wait early");
        gate.countDown();
        a.join();
        b.join();
        assertEquals("a", aResult.get());
        assertEquals("b", bResult.get());
        assertTrue(bFlagAfter.get(), "the interrupt flag must be set again when the run returns");
    }

    // ---- block statements count against the parse nesting limit too (P3 H-3, mutant Q4) ----

    /**
     * {@code n} nested {@code if True:} blocks, each indented one space deeper than its parent -
     * the lexer accepts ANY strictly increasing space indentation (only tabs are refused), so
     * one level costs about six characters and ~100 levels fit easily under the script length
     * limit. Line i carries i spaces; the last line is {@code pass} at n spaces.
     */
    private static String nestedIfs(int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(" ".repeat(i)).append("if True:\n");
        }
        out.append(" ".repeat(n)).append("pass\n");
        return out.toString();
    }

    @Test
    void deeplyNestedBlocksHitTheParseNestingLimit() {
        // the `if` at indent k is a statement inside the block of the one at indent k-1, so it
        // parses at nesting k - and the innermost body statement sits one level deeper still.
        // With 100 nested `if`s the `pass` lands exactly on 100 and parses; its AST measures
        // 101 deep, under PLAN_MAX_AST_DEPTH, so the script is accepted and runs to an empty
        // patch. With 101 nested `if`s the innermost `pass` would sit at 101 and the parser
        // refuses it.
        String accepted = nestedIfs(100);
        String refused = nestedIfs(101);
        assertTrue(accepted.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS);
        assertTrue(refused.length() <= PlanScriptWriter.MAX_SCRIPT_CHARS);
        assertEquals(101, AstDepth.of(new Parser(new Lexer(accepted).scan()).parseProgram()));

        PlanScriptRunner.Result ok = run(accepted);
        assertTrue(ok.ok(), ok.issues().toString());

        PlanScriptRunner.Result r = run(refused);
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#syntax:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("nested too deeply (limit 100)"),
                r.issues().get(0).message());
    }

    // ---- a digit the lexer cannot parse is a syntax issue, never an exception (P3 Task 20 final review) ----

    @Test
    void aNonAsciiDigitInANumberIsASyntaxIssueNotAnException() {
        // Character.isDigit accepts every Unicode digit but Double.parseDouble only
        // understands ASCII 0-9: a full-width or other script's digit used to escape
        // run() as a raw NumberFormatException and lose the whole batch
        List<String> scripts = List.of(
                "x = １２\n",
                "for i in range(５):\n    pass\n",
                "x = [０, 0, 0]\n",
                "x = ٣\n",
                "x = 1१\n");
        for (String script : scripts) {
            PlanScriptRunner.Result r = run(script);
            assertNull(r.patch(), script);
            assertEquals(List.of("E-SCHEMA"), codes(r), script);
            assertEquals("E-SCHEMA:#syntax:1", r.issues().get(0).id(), script);
            assertTrue(r.issues().get(0).message().contains("スクリプト1"), r.issues().get(0).message());
            assertTrue(r.issues().get(0).message().contains("1行目"), r.issues().get(0).message());
            assertTrue(r.issues().get(0).message().contains("0〜9"), r.issues().get(0).message());
        }
    }

    @Test
    void aNonAsciiDigitIssueNamesTheLineOfTheDigit() {
        // the first non-ASCII digit sits on line 2 of a three-line script
        PlanScriptRunner.Result r = run("x = 1\ny = １２\nz = 3\n");
        assertNull(r.patch());
        assertEquals("E-SCHEMA:#syntax:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト1の2行目"), r.issues().get(0).message());
    }

    @Test
    void aNonAsciiDigitInOneScriptLeavesTheRestOfTheBatchRunning() {
        // script 2 carries the bad digit: only it gets an issue, and the batch is
        // still refused (patch null) while scripts 1 and 3 simply run
        PlanScriptRunner.Result r = run("mood(\"a\")", "x = １２\n", "mood(\"b\")");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertEquals("E-SCHEMA:#syntax:2", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト2"), r.issues().get(0).message());
    }

    @Test
    void aUnicodeHeavyFuzzOfScriptsNeverThrowsOutOfRun() {
        // a seeded corpus of 300 scripts mixed from Unicode digits, whitespace and
        // format marks, unpaired surrogates and ASCII code fragments: whatever a
        // script turns out to be, run() must hand back a Result for it - the
        // reviewer's fuzz measured 1,534 escapes in 40,000 scripts, all of them
        // NumberFormatException out of the lexer
        String[] pieces = {
                "x = ", "print(", ")", "(", "[", "]", ",", "+", "-", ":",
                "if True:\n    ", "for i in range(3):\n    ", "pass", "mood(\"ok\")",
                "\n", " ", "\t", "　", " ", "​", "﻿", "\"abc\"", "# c",
                "0", "9", "１", "７", "１２", "٣", "١", "१", "১", "๙",
                "x", "def f():", "1.5", ".", "￣", ""};
        java.util.Random rnd = new java.util.Random(20_260_926L);
        for (int i = 0; i < 300; i++) {
            StringBuilder script = new StringBuilder();
            int pieceCount = 1 + rnd.nextInt(40);
            for (int k = 0; k < pieceCount; k++) {
                script.append(pieces[rnd.nextInt(pieces.length)]);
            }
            if (script.length() > PlanScriptWriter.MAX_SCRIPT_CHARS) {
                script.setLength(PlanScriptWriter.MAX_SCRIPT_CHARS);
            }
            assertNotNull(run(script.toString()), "script " + i + " threw out of run()");
        }
    }

    @Test
    void aPlainAsciiNumberScriptStillWorks() {
        PlanScriptRunner.Result r = run("x = 12\nprint(x + 1)\n");
        assertTrue(r.ok(), r.issues().toString());
        assertEquals(List.of("13"), r.printed());
    }
}
