package io.github.khayashi4337.micradrone.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanScriptProfileTest {
    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    private static List<String> names(String source) {
        return PlanScriptProfile.check(parse(source)).stream().map(v -> v.name() + ":" + v.reason()).toList();
    }

    @Test
    void constructionCommandsControlFlowAndPureHelpersAreAllowed() {
        assertEquals(List.of(), names("style(\"roof\", \"minecraft:bricks\")\n"
                + "def post(n):\n    pillar(\"p-\" + str(n), None, [n, 0, 0], {})\n"
                + "for i in range(3):\n    post(i)\n"
                + "x = len([1, 2]) + abs(-3) + min(1, 2) + max(1, 2)\n"
                + "items = list()\nitems.append(1)\nd = dict()\ns = set()\nprint(\"ok\")\n"));
    }

    @Test
    void nondeterministicBuiltinsAreRefused() {
        for (String call : List.of("random()", "create_task(\"t\", 1, 0, f)", "semaphore()", "attach_isr(\"north\", f)",
                "raise_interrupt(\"north\")", "sleep_ticks(5)")) {
            List<String> v = names("def f():\n    pass\n" + call);
            assertEquals(1, v.size(), call);
            assertTrue(v.get(0).endsWith(":NONDETERMINISTIC"), call + " -> " + v);
        }
    }

    @Test
    void farmCommandsAndPerceptionAreRefused() {
        for (String call : List.of("move(\"north\")", "till()", "plant(\"wheat\")", "harvest()", "do_a_flip()", "can_harvest()",
                "get_pos_x()", "get_time()", "get_weather()", "get_ground()", "set_output(True)", "pair_with(\"x\")",
                "cast_line()", "repair_rod()", "measure()", "is_rotten()")) {
            List<String> v = names(call);
            assertEquals(1, v.size(), call);
            assertTrue(v.get(0).endsWith(":FARM_COMMAND"), call + " -> " + v);
        }
    }

    @Test
    void unknownCallsAreRefusedAndUserFunctionsAreNot() {
        assertEquals(List.of("frobnicate:UNKNOWN"), names("frobnicate(1)"));
        assertEquals(List.of(), names("def helper(x):\n    return x + 1\nhelper(2)\n"));
    }

    @Test
    void violationsInsideNestedBlocksAndExpressionsAreFound() {
        String source = "def f():\n    while True:\n        if random() > 0.5:\n            pass\nx = [1, harvest()]\nd = {\"a\": get_time()}\nfor i in range(2):\n    y = d[move(\"north\")]\n";
        List<String> v = names(source);
        assertEquals(List.of("random:NONDETERMINISTIC", "harvest:FARM_COMMAND", "get_time:FARM_COMMAND", "move:FARM_COMMAND"), v);
        assertEquals(3, PlanScriptProfile.check(parse("\n\nharvest()\n")).get(0).line());
    }

    @Test
    void aFunctionMayNotTakeTheNameOfACommand() {
        assertEquals(List.of("random:RESERVED_NAME"), names("def random():\n    pass\n"));
        assertEquals(List.of("wall:RESERVED_NAME"), names("def wall():\n    pass\n"));
    }

    @Test
    void semaphoreMethodsAreRefused() {
        List<String> v = names("s = list()\ns.post()\ns.wait()\ns.append(1)\n");
        assertEquals(List.of("post:NONDETERMINISTIC", "wait:NONDETERMINISTIC"), v);
    }

    @Test
    void classifyTellsFarmFromConstructionAndFlagsMixtures() {
        assertEquals(PlanScriptProfile.Kind.PLAN, PlanScriptProfile.classify(parse("wall(\"w\", None, [0,0,0], {\"side\": \"north\"})")));
        assertEquals(PlanScriptProfile.Kind.FARM, PlanScriptProfile.classify(parse("harvest()")));
        assertEquals(PlanScriptProfile.Kind.MIXED, PlanScriptProfile.classify(parse("harvest()\nwall(\"w\", None, [0,0,0], {})")));
        assertEquals(PlanScriptProfile.Kind.NEUTRAL, PlanScriptProfile.classify(parse("x = 1 + 2\nprint(x)")));
    }
}
