package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class InterpreterTest {

    private FakeDroneApi run(String source) {
        FakeDroneApi api = new FakeDroneApi(5);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program =
                new Parser(new Lexer(source).scan()).parseProgram();
        new Interpreter(api).run(program);
        return api;
    }

    @Test
    void variablesAndArithmetic() {
        FakeDroneApi api = run("""
                x = 2
                y = x * 3 + 1
                print(y)
                """);
        assertEquals(List.of("7"), api.printed);
    }

    @Test
    void ifElifElse() {
        FakeDroneApi api = run("""
                x = 2
                if x == 1:
                    print("one")
                elif x == 2:
                    print("two")
                else:
                    print("other")
                """);
        assertEquals(List.of("two"), api.printed);
    }

    @Test
    void whileLoop() {
        FakeDroneApi api = run("""
                n = 0
                while n < 3:
                    print(n)
                    n = n + 1
                """);
        assertEquals(List.of("0", "1", "2"), api.printed);
    }

    @Test
    void forRangeOneTwoThreeArgs() {
        FakeDroneApi api = run("""
                for i in range(3):
                    print(i)
                for i in range(1, 4):
                    print(i)
                for i in range(0, 6, 2):
                    print(i)
                """);
        assertEquals(List.of("0", "1", "2", "1", "2", "3", "0", "2", "4"), api.printed);
    }

    @Test
    void moveTillPlantHarvest() {
        FakeDroneApi api = run("""
                till()
                plant("wheat")
                harvest()
                """);
        assertEquals(List.of("till", "plant:wheat", "harvest"), api.calls);
    }

    @Test
    void moveFailsAtBoundaryAndReturnsFalse() {
        FakeDroneApi api = run("""
                if move("north"):
                    print("moved")
                else:
                    print("blocked")
                """);
        assertEquals(List.of("blocked"), api.printed);
    }

    @Test
    void harvestOnlyWhenMature() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setCropAge(0, 0, 3); // mature at the drone's starting cell
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                if can_harvest():
                    harvest()
                    print("harvested")
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("harvested"), api.printed);
    }

    @Test
    void getPointsReflectsSuccessfulHarvests() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setCropAge(0, 0, 3); // mature at the drone's starting cell
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                print(get_points())
                harvest()
                print(get_points())
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("0", "1"), api.printed);
    }

    @Test
    void getPointsAcceptsACropNameArgument() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setCropAge(0, 0, 3);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                harvest()
                print(get_points("wheat"))
                print(get_points("pumpkin"))
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("1", "0"), api.printed);
    }

    @Test
    void isRottenReflectsTheCurrentCellAndClearsOnReplant() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setRotten(0, 0, true);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                print(is_rotten())
                till()
                plant("wheat")
                print(is_rotten())
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("True", "False"), api.printed);
    }

    @Test
    void harvestingARottenCellSucceedsWithoutAwardingPoints() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setRotten(0, 0, true);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                print(harvest())
                print(get_points())
                print(is_rotten())
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("True", "0", "False"), api.printed);
    }

    @Test
    void booleanLogicShortCircuitsAndNot() {
        FakeDroneApi api = run("""
                print(not False)
                print(True and False)
                print(False or True)
                """);
        assertEquals(List.of("True", "False", "True"), api.printed);
    }

    @Test
    void stringConcatenation() {
        FakeDroneApi api = run("""
                print("a" + "b")
                """);
        assertEquals(List.of("ab"), api.printed);
    }

    @Test
    void divisionByZeroRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                x = 1 / 0
                """));
    }

    @Test
    void undefinedVariableRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                print(x)
                """));
    }

    @Test
    void unknownFunctionRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                nope()
                """));
    }

    @Test
    void rangeOutsideForLoopRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                x = range(3)
                """));
    }

    // ---- perception (issue #10) ----

    @Test
    void perceptionCommandsReachTheApiAndComeBackAsScriptValues() {
        FakeDroneApi api = run("""
                print(get_ground())
                print(get_block_above())
                print(get_time())
                print(get_weather())
                print(get_biome())
                print(get_light())
                print(get_plot_id())
                """);
        assertEquals(List.of("dirt", "air", "6000", "clear", "plains", "15", ""), api.printed);
        assertEquals(List.of("get_ground", "get_block_above", "get_time", "get_weather", "get_biome", "get_light",
                "get_plot_id"), api.calls);
    }

    @Test
    void getPlotIdReportsTheMarkersCurrentId() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setPlotId("north_field");
        new Interpreter(api).run(new Parser(new Lexer("""
                print(get_plot_id())
                """).scan()).parseProgram());
        assertEquals(List.of("north_field"), api.printed);
    }

    @Test
    void getGroundReportsTheCellsRealStateSoBranchingOnItActuallyWorks() {
        FakeDroneApi api = run("""
                if get_ground() == "dirt":
                    till()
                print(get_ground())
                """);
        assertEquals(List.of("farmland"), api.printed, "till() should be visible to the next get_ground()");
    }

    @Test
    void perceptionValuesCompareAndBranchLikeAnyOtherValue() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setWeather("thunder");
        api.setDayTime(18000);
        api.setLight(4);
        new Interpreter(api).run(new Parser(new Lexer("""
                if get_weather() == "thunder":
                    print("storm")
                if get_time() > 13000:
                    print("night")
                if get_light() < 9:
                    print("dark")
                """).scan()).parseProgram());
        assertEquals(List.of("storm", "night", "dark"), api.printed);
    }

    @Test
    void perceptionCommandsTakeNoArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                x = get_ground("here")
                """));
        assertThrows(MicraLangException.class, () -> run("""
                x = get_weather(1)
                """));
        assertThrows(MicraLangException.class, () -> run("""
                x = get_plot_id(1)
                """));
    }
}
