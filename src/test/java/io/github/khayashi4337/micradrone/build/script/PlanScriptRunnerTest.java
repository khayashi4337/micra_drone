package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.ParamValue;
import io.github.khayashi4337.micradrone.build.model.PlanOp;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanScriptRunnerTest {
    private static PlanScriptRunner.Result run(String... scripts) {
        return PlanScriptRunner.run(List.of(scripts), "patch", 0, "test", PlanRunLimits.DEFAULT);
    }

    private static List<String> codes(PlanScriptRunner.Result r) {
        return r.issues().stream().map(i -> i.code().label()).toList();
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
    void aStackOverflowInsideARunIsALimitIssueNotAnError() {
        // Java list equality on cyclic lists recurses until the stack gives out
        PlanScriptRunner.Result r = run("a = []\na.append(a)\nb = []\nb.append(b)\nprint(a == b)");
        assertNull(r.patch());
        assertEquals(List.of("E-SCRIPT-LIMIT"), codes(r));
        assertEquals("E-SCRIPT-LIMIT:#stack:1", r.issues().get(0).id());
        assertTrue(r.issues().get(0).message().contains("スクリプト1"), r.issues().get(0).message());
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
}
