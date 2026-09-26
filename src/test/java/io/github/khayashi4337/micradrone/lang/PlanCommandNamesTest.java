package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.parts.BuildingParts;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanCommandNamesTest {
    @Test
    void theFarmListIsUntouched() {
        assertEquals(49, CommandNames.ALL.size(), "the farm command list must not change; update this only if the farm gains a command");
        for (String name : CommandNames.PLAN) {
            assertFalse(CommandNames.ALL.contains(name), name + " must not be a farm command");
        }
    }

    @Test
    void partCommandsAreExactlyTheBuildingPartNames() {
        assertEquals(BuildingParts.NAMES, CommandNames.PLAN_PART_COMMANDS.stream().sorted().toList());
        assertEquals(22, CommandNames.PLAN_PART_COMMANDS.size());
    }

    @Test
    void theGeneralCommandsAreTheDocumentedOnes() {
        assertEquals(List.of("site", "style", "mood", "part", "update_params", "relocate", "remove_part", "connect",
                "disconnect", "logistics"), CommandNames.PLAN_GENERAL);
        assertEquals(CommandNames.PLAN_GENERAL.size() + CommandNames.PLAN_PART_COMMANDS.size(), CommandNames.PLAN.size());
        assertEquals(List.of("print", "len", "abs", "min", "max", "str", "list", "dict", "set", "range"), CommandNames.PLAN_HELPERS);
    }

    @Test
    void everyPlanCommandIsRecognisedByAConstructionInterpreter() {
        for (String name : CommandNames.PLAN) {
            try {
                new Interpreter(new RecordingPlanApi(), PlanRunLimits.DEFAULT)
                        .run(new Parser(new Lexer(name + "()").scan()).parseProgram());
            } catch (MicraLangException e) {
                assertFalse(e.getMessage().contains("unknown function"), name + "() is in CommandNames.PLAN but is not recognised: " + e.getMessage());
            }
        }
    }

    @Test
    void helpersAreFarmBuiltinsThatAreSafeInAConstructionScript() {
        Set<String> nondeterministic = new HashSet<>(List.of("random", "create_task", "semaphore", "attach_isr", "raise_interrupt", "sleep_ticks"));
        for (String helper : CommandNames.PLAN_HELPERS) {
            assertTrue(CommandNames.ALL.contains(helper), helper);
            assertFalse(nondeterministic.contains(helper), helper);
        }
        assertEquals(CommandNames.PLAN.size() + CommandNames.PLAN_HELPERS.size(), CommandNames.PLAN_VISIBLE.size());
    }
}
