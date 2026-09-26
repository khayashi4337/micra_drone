package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.IssueCode;
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
        PlanScriptRunner.Result r = run("structure(\"hut\", None, [0,0,0], {})", "wall(\"w\"\n");
        assertNull(r.patch());
        assertEquals(List.of("E-SCHEMA"), codes(r));
        assertTrue(r.issues().get(0).message().contains("2"), r.issues().get(0).message());
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
}
