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

    // ---- collections ----

    @Test
    void listLiteralsIndexAndPrint() {
        FakeDroneApi api = run("""
                items = [1, 2, 3]
                print(items)
                print(items[0])
                print(items[2])
                """);
        assertEquals(List.of("[1, 2, 3]", "1", "3"), api.printed);
    }

    @Test
    void dictLiteralsLookUpAndPrint() {
        FakeDroneApi api = run("""
                costs = {"wheat": 20, "carrot": 15}
                print(costs)
                print(costs["wheat"])
                """);
        assertEquals(List.of("{\"wheat\": 20, \"carrot\": 15}", "20"), api.printed);
    }

    @Test
    void emptyBracesAreAnEmptyDict() {
        FakeDroneApi api = run("""
                d = {}
                print(d)
                """);
        assertEquals(List.of("{}"), api.printed);
    }

    @Test
    void setLiteralsDropDuplicates() {
        FakeDroneApi api = run("""
                s = {1, 2, 2, 3}
                print(s)
                """);
        assertEquals(List.of("{1, 2, 3}"), api.printed);
    }

    @Test
    void indexAssignmentReplacesListItemsAndDictValues() {
        FakeDroneApi api = run("""
                items = [1, 2, 3]
                items[1] = 99
                print(items)
                d = {}
                d["a"] = 1
                d["a"] = 2
                print(d)
                """);
        assertEquals(List.of("[1, 99, 3]", "{\"a\": 2}"), api.printed);
    }

    @Test
    void nestedListsIndexByChaining() {
        FakeDroneApi api = run("""
                grid = [[1, 2], [3, 4]]
                print(grid[1][0])
                """);
        assertEquals(List.of("3"), api.printed);
    }

    /** Nesting has no fixed limit - indexing and index-assignment both chain as deep as the data goes. */
    @Test
    void threeDimensionalListsReadAndWrite() {
        FakeDroneApi api = run("""
                cube = [[[1, 2], [3, 4]], [[5, 6], [7, 8]]]
                print(cube[1][0][1])
                cube[0][1][0] = 99
                print(cube[0][1][0])
                print(cube[0][1])
                total = 0
                for plane in cube:
                    for row in plane:
                        for cell in row:
                            total = total + cell
                print(total)
                """);
        assertEquals(List.of("6", "99", "[99, 4]", "132"), api.printed);
    }

    /** Building nesting up at runtime (rather than as one literal) must work the same way. */
    @Test
    void nestedListsCanBeBuiltAndMutatedThroughVariables() {
        FakeDroneApi api = run("""
                inner = [0, 0]
                middle = [inner, inner]
                outer = [middle]
                outer[0][0][1] = 7
                print(outer[0][0][1])
                """);
        assertEquals(List.of("7"), api.printed);
    }

    @Test
    void forLoopsWalkListsSetsDictKeysAndStrings() {
        FakeDroneApi api = run("""
                for x in [1, 2]:
                    print(x)
                for k in {"a": 1}:
                    print(k)
                for c in "hi":
                    print(c)
                """);
        assertEquals(List.of("1", "2", "a", "h", "i"), api.printed);
    }

    /** A body that appends to the very list it walks must not blow up - the loop sees the original items. */
    @Test
    void appendingDuringIterationDoesNotThrow() {
        FakeDroneApi api = run("""
                items = [1, 2]
                seen = 0
                for x in items:
                    items[0] = 9
                    seen = seen + 1
                print(seen)
                """);
        assertEquals(List.of("2"), api.printed);
    }

    @Test
    void inOperatorWorksOnEveryContainer() {
        FakeDroneApi api = run("""
                print(2 in [1, 2, 3])
                print(5 in [1, 2, 3])
                print("a" in {"a": 1})
                print(1 in {1, 2})
                print("ell" in "hello")
                print(not 5 in [1, 2])
                """);
        assertEquals(List.of("True", "False", "True", "True", "True", "True"), api.printed);
    }

    @Test
    void collectionsCompareByValue() {
        FakeDroneApi api = run("""
                print([1, 2] == [1, 2])
                print([1, 2] == [2, 1])
                print({"a": 1} == {"a": 1})
                """);
        assertEquals(List.of("True", "False", "True"), api.printed);
    }

    @Test
    void emptyCollectionsAreFalsy() {
        FakeDroneApi api = run("""
                if []:
                    print("no")
                if not {}:
                    print("empty dict is falsy")
                if [1]:
                    print("non-empty list is truthy")
                """);
        assertEquals(List.of("empty dict is falsy", "non-empty list is truthy"), api.printed);
    }

    /** Self-referencing collections must print, not blow the stack (a StackOverflowError would kill the thread silently). */
    @Test
    void selfReferencingListPrintsInsteadOfOverflowing() {
        FakeDroneApi api = new FakeDroneApi(5);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                a = [1]
                a[0] = a
                print(a)
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(1, api.printed.size());
        assertTrue(api.printed.get(0).endsWith("...]]]]]]]]"), "expected the cycle to bottom out in an ellipsis");
    }

    // ---- general-purpose builtins ----

    @Test
    void lenCountsEveryContainer() {
        FakeDroneApi api = run("""
                print(len([1, 2, 3]))
                print(len({"a": 1}))
                print(len({1, 2}))
                print(len("hello"))
                print(len([]))
                """);
        assertEquals(List.of("3", "1", "2", "5", "0"), api.printed);
    }

    @Test
    void absHandlesBothSigns() {
        FakeDroneApi api = run("""
                print(abs(-3))
                print(abs(3))
                print(abs(-2.5))
                """);
        assertEquals(List.of("3", "3", "2.5"), api.printed);
    }

    /** Python's two shapes: several arguments, or one collection to scan. */
    @Test
    void minAndMaxTakeArgumentsOrACollection() {
        FakeDroneApi api = run("""
                print(min(3, 1, 2))
                print(max(3, 1, 2))
                print(min([3, 1, 2]))
                print(max([3, 1, 2]))
                print(max({4, 9}))
                """);
        assertEquals(List.of("1", "3", "1", "3", "9"), api.printed);
    }

    @Test
    void randomStaysWithinZeroToOne() {
        FakeDroneApi api = run("""
                for i in range(20):
                    r = random()
                    if r < 0:
                        print("below")
                    if r >= 1:
                        print("above")
                print("done")
                """);
        assertEquals(List.of("done"), api.printed);
    }

    @Test
    void strTurnsValuesIntoText() {
        FakeDroneApi api = run("""
                print(str(5) + " items")
                print(str(True))
                print(str([1, 2]))
                """);
        assertEquals(List.of("5 items", "True", "[1, 2]"), api.printed);
    }

    @Test
    void listAndSetConvertBetweenContainers() {
        FakeDroneApi api = run("""
                print(list({1, 1, 2}))
                print(set([3, 3, 4]))
                print(list("ab"))
                print(list())
                print(set())
                print(dict())
                """);
        assertEquals(List.of("[1, 2]", "{3, 4}", "[\"a\", \"b\"]", "[]", "{}", "{}"), api.printed);
    }

    @Test
    void lenOfANumberRaises() {
        assertThrows(MicraLangException.class, () -> run("print(len(5))\n"));
    }

    @Test
    void maxOfAnEmptyListRaises() {
        assertThrows(MicraLangException.class, () -> run("print(max([]))\n"));
    }

    @Test
    void listIndexOutOfRangeRaises() {
        assertThrows(MicraLangException.class, () -> run("print([1, 2][5])\n"));
    }

    @Test
    void missingDictKeyRaises() {
        assertThrows(MicraLangException.class, () -> run("print({\"a\": 1}[\"b\"])\n"));
    }

    @Test
    void loopingOverANumberRaises() {
        assertThrows(MicraLangException.class, () -> run("for x in 5:\n    print(x)\n"));
    }

    @Test
    void assigningToSomethingUnassignableRaises() {
        assertThrows(MicraLangException.class, () -> run("1 = 2\n"));
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
    void doAFlipDispatchesAndReturnsNoneLikePrint() {
        FakeDroneApi api = run("""
                do_a_flip()
                x = do_a_flip()
                print(x)
                """);
        assertEquals(List.of("do_a_flip", "do_a_flip"), api.calls);
        assertEquals(List.of("None"), api.printed);
    }

    @Test
    void doAFlipRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                do_a_flip(1)
                """));
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
