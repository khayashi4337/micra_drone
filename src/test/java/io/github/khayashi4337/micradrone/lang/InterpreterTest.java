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

    // ---- collection methods ----

    @Test
    void listsCanBeBuiltUpWithAppend() {
        FakeDroneApi api = run("""
                items = []
                for i in range(4):
                    items.append(i * 2)
                print(items)
                print(len(items))
                print(max(items))
                """);
        assertEquals(List.of("[0, 2, 4, 6]", "4", "6"), api.printed);
    }

    @Test
    void listPopRemoveAndClear() {
        FakeDroneApi api = run("""
                items = [1, 2, 3]
                print(items.pop())
                print(items)
                items.remove(1)
                print(items)
                items.clear()
                print(items)
                """);
        assertEquals(List.of("3", "[1, 2]", "[2]", "[]"), api.printed);
    }

    @Test
    void setAddRemoveAndClear() {
        FakeDroneApi api = run("""
                seen = set()
                seen.add("a")
                seen.add("a")
                seen.add("b")
                print(seen)
                print(len(seen))
                seen.remove("a")
                print(seen)
                seen.clear()
                print(len(seen))
                """);
        assertEquals(List.of("{\"a\", \"b\"}", "2", "{\"b\"}", "0"), api.printed);
    }

    @Test
    void dictKeysValuesGetRemoveAndClear() {
        FakeDroneApi api = run("""
                counts = {}
                counts["wheat"] = 3
                counts["carrot"] = 1
                print(counts.keys())
                print(counts.values())
                print(counts.get("wheat"))
                print(counts.get("nope"))
                print(counts.remove("wheat"))
                print(counts)
                counts.clear()
                print(counts)
                """);
        assertEquals(List.of(
                "[\"wheat\", \"carrot\"]", "[3, 1]", "3", "None", "3", "{\"carrot\": 1}", "{}"), api.printed);
    }

    /** Methods must chain off whatever an index produced, not just off a bare name. */
    @Test
    void methodsChainOffIndexedValues() {
        FakeDroneApi api = run("""
                grid = [[1], [2]]
                grid[0].append(9)
                print(grid)
                """);
        assertEquals(List.of("[[1, 9], [2]]"), api.printed);
    }

    @Test
    void unknownMethodRaises() {
        assertThrows(MicraLangException.class, () -> run("[1].sort()\n"));
    }

    /**
     * A loop that only calls a memory-growing method (never touches DroneApi) must still trip the
     * runaway-loop watchdog, not run forever growing the heap. Method calls used to reset the same
     * counter evalCall does, so this loop never tripped it at all - confirmed by an out-of-memory
     * repro before the fix.
     */
    @Test
    void appendOnlyLoopWithNoDroneApiCallsStillTripsTheRunawayWatchdog() {
        MicraLangException ex = assertThrows(MicraLangException.class, () -> run("""
                items = []
                while True:
                    items.append(1)
                """));
        assertTrue(ex.getMessage().contains("too long"), "expected the runaway-loop message, got: " + ex.getMessage());
    }

    /**
     * The general-purpose builtins (len/abs/min/max/random/str/list/set/dict) touch no DroneApi
     * either, so a loop calling only those must trip the watchdog too - not just method calls.
     */
    @Test
    void generalPurposeBuiltinOnlyLoopWithNoDroneApiCallsStillTripsTheRunawayWatchdog() {
        MicraLangException ex = assertThrows(MicraLangException.class, () -> run("""
                while True:
                    x = list([1, 2, 3])
                """));
        assertTrue(ex.getMessage().contains("too long"), "expected the runaway-loop message, got: " + ex.getMessage());
    }

    /** A general-purpose builtin nested inside a method-call argument must not reset the counter either. */
    @Test
    void methodCallWithAGeneralPurposeBuiltinArgumentStillTripsTheRunawayWatchdog() {
        MicraLangException ex = assertThrows(MicraLangException.class, () -> run("""
                items = []
                while True:
                    items.append(str(1))
                """));
        assertTrue(ex.getMessage().contains("too long"), "expected the runaway-loop message, got: " + ex.getMessage());
    }

    @Test
    void methodOnANumberRaises() {
        assertThrows(MicraLangException.class, () -> run("(5).append(1)\n"));
    }

    @Test
    void methodWithTheWrongArgumentCountRaises() {
        assertThrows(MicraLangException.class, () -> run("[1].append()\n"));
    }

    @Test
    void popOnAnEmptyListRaises() {
        assertThrows(MicraLangException.class, () -> run("[].pop()\n"));
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
    void setOutputDispatchesAndGetOutputReadsBackTheSameFake() {
        FakeDroneApi api = run("""
                set_output(True)
                print(get_output())
                set_output(False)
                print(get_output())
                """);
        assertEquals(List.of("set_output:true", "get_output", "set_output:false", "get_output"), api.calls);
        assertEquals(List.of("True", "False"), api.printed);
    }

    @Test
    void setOutputRejectsANonBooleanArgument() {
        assertThrows(MicraLangException.class, () -> run("""
                set_output(1)
                """));
    }

    @Test
    void setOutputRejectsTheWrongArgumentCount() {
        assertThrows(MicraLangException.class, () -> run("""
                set_output()
                """));
        assertThrows(MicraLangException.class, () -> run("""
                set_output(True, False)
                """));
    }

    @Test
    void getOutputRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                get_output(True)
                """));
    }

    @Test
    void pairWithDispatchesTheIdAndIsPairedReadsBackWhateverTheFakeReports() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setPairedResult(true);
        new Interpreter(api).run(new Parser(new Lexer("""
                pair_with("north_field")
                print(is_paired())
                """).scan()).parseProgram());
        assertEquals(List.of("pair_with:north_field", "is_paired"), api.calls);
        assertEquals("north_field", api.pairTarget());
        assertEquals(List.of("True"), api.printed);
    }

    @Test
    void pairWithEmptyStringClearsThePairTarget() {
        FakeDroneApi api = run("""
                pair_with("north_field")
                pair_with("")
                """);
        assertEquals("", api.pairTarget());
    }

    @Test
    void pairWithRejectsANonStringArgument() {
        assertThrows(MicraLangException.class, () -> run("""
                pair_with(5)
                """));
    }

    @Test
    void isPairedRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                is_paired(True)
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
    void measureReportsTheGiantPumpkinSideUnderTheDroneAndZeroElsewhere() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setGiantSide(1, 0, 3);
        List<io.github.khayashi4337.micradrone.lang.ast.Stmt> program = new Parser(new Lexer("""
                print(measure())
                move("east")
                print(measure())
                if measure() >= 3:
                    print("big enough")
                """).scan()).parseProgram();
        new Interpreter(api).run(program);
        assertEquals(List.of("0", "3", "big enough"), api.printed);
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

    // ---- user-defined functions (def/return/break/continue/pass) ----

    @Test
    void defAndCallWithReturnValue() {
        FakeDroneApi api = run("""
                def add(a, b):
                    return a + b
                print(add(2, 3))
                """);
        assertEquals(List.of("5"), api.printed);
    }

    @Test
    void bareReturnYieldsNone() {
        FakeDroneApi api = run("""
                def f():
                    return
                print(f())
                """);
        assertEquals(List.of("None"), api.printed);
    }

    @Test
    void fallingOffTheEndWithoutReturnYieldsNoneJustLikeBareReturn() {
        FakeDroneApi api = run("""
                def f():
                    x = 1
                print(f())
                """);
        assertEquals(List.of("None"), api.printed);
    }

    @Test
    void passIsANoOp() {
        FakeDroneApi api = run("""
                def f():
                    pass
                f()
                print("done")
                """);
        assertEquals(List.of("done"), api.printed);
    }

    @Test
    void recursionWorks() {
        FakeDroneApi api = run("""
                def factorial(n):
                    if n <= 1:
                        return 1
                    return n * factorial(n - 1)
                print(factorial(5))
                """);
        assertEquals(List.of("120"), api.printed);
    }

    @Test
    void functionsCanReadGlobalsButAssignmentInsideAFunctionStaysLocal() {
        // No `global` statement in this language: `count = count + 1` inside a function reads the
        // global (falls through the scope chain) but the assignment always creates a *local* count,
        // leaving the outer binding untouched - an intentional simplification, see
        // docs/design/lang_def_return_break_continue.md.
        FakeDroneApi api = run("""
                count = 10
                def bump():
                    count = count + 1
                    return count
                print(bump())
                print(count)
                """);
        assertEquals(List.of("11", "10"), api.printed);
    }

    @Test
    void parametersShadowSameNamedGlobals() {
        FakeDroneApi api = run("""
                x = 100
                def f(x):
                    return x + 1
                print(f(1))
                print(x)
                """);
        assertEquals(List.of("2", "100"), api.printed);
    }

    @Test
    void breakAndContinueWorkInsideAFunctionsOwnLoop() {
        FakeDroneApi api = run("""
                def sumUntilThree():
                    total = 0
                    for i in range(10):
                        if i == 3:
                            break
                        if i == 1:
                            continue
                        total = total + i
                    return total
                print(sumUntilThree())
                """);
        assertEquals(List.of("2"), api.printed); // 0 + 2 (1 skipped by continue, loop stops before 3)
    }

    @Test
    void redefiningABuiltinCommandNameRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                def move():
                    pass
                """));
    }

    @Test
    void duplicateParameterNameRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                def f(a, a):
                    return a
                """));
    }

    @Test
    void returnOutsideFunctionRaisesAtParseTime() {
        assertThrows(MicraLangException.class, () -> run("""
                return 1
                """));
    }

    @Test
    void breakOutsideLoopRaisesAtParseTime() {
        assertThrows(MicraLangException.class, () -> run("""
                break
                """));
    }

    @Test
    void continueOutsideLoopRaisesAtParseTime() {
        assertThrows(MicraLangException.class, () -> run("""
                continue
                """));
    }

    @Test
    void nestedDefIsRejected() {
        assertThrows(MicraLangException.class, () -> run("""
                def outer():
                    def inner():
                        return 1
                    return inner()
                """));
    }

    /**
     * The parser must reset loopDepth to 0 while parsing a function body, even when that def
     * statement is textually nested inside a while/for block (as opposed to inside another def,
     * which is rejected outright by the nested-def check above). Without the reset, this break
     * would be wrongly accepted at parse time; at runtime it would then escape callFunction
     * (which only catches ReturnSignal) and get swallowed by the *enclosing* while's own
     * break-catch, silently exiting that loop early - which the assertion on api.printed below
     * would catch as a regression even without assertThrows firing.
     */
    @Test
    void breakInsideAFunctionDefinedInsideALoopIsStillRejectedAtParseTime() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                while True:
                    def f():
                        break
                    f()
                    print("unreachable")
                """));
        assertTrue(e.getMessage().contains("break"), "expected a break-outside-loop message, got: " + e.getMessage());
    }

    @Test
    void tooMuchRecursionRaisesACleanErrorInsteadOfOverflowingTheStack() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def recurse(n):
                    return recurse(n + 1)
                recurse(0)
                """));
        assertTrue(e.getMessage().contains("recursion"), "expected a recursion-limit message, got: " + e.getMessage());
    }

    @Test
    void callingANonFunctionValueGivesAClearError() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                x = 5
                x()
                """));
        assertTrue(e.getMessage().contains("not a function"), "expected a not-a-function message, got: " + e.getMessage());
    }

    /**
     * Shadowing a builtin's name with an ordinary (non-function) local must not break calling the
     * builtin itself - this mod already has published CurseForge releases, and evalCall never
     * consulted {@code env} at all before user-defined functions existed, so a script that (say)
     * uses {@code max} as a running-maximum variable and separately calls {@code max(a, b)} must
     * keep working exactly as it did before this feature shipped.
     */
    @Test
    void aLocalVariableSharingABuiltinsNameDoesNotBreakCallingTheBuiltin() {
        FakeDroneApi api = run("""
                max = 0
                print(max(3, 7))
                """);
        assertEquals(List.of("7"), api.printed);
    }

    @Test
    void wrongArgumentCountToAUserFunctionRaises() {
        assertThrows(MicraLangException.class, () -> run("""
                def f(a, b):
                    return a + b
                f(1)
                """));
    }

    @Test
    void returnInsideAWhileLoopExitsTheFunctionNotJustTheLoop() {
        FakeDroneApi api = run("""
                def firstAtLeastThree(items):
                    i = 0
                    while i < len(items):
                        if items[i] >= 3:
                            return items[i]
                        i = i + 1
                    return -1
                print(firstAtLeastThree([1, 2, 5, 9]))
                print("after")
                """);
        assertEquals(List.of("5", "after"), api.printed); // the loop's own frame must not swallow ReturnSignal
    }

    @Test
    void returnInsideAForLoopSkipsTheStatementsAfterTheLoop() {
        FakeDroneApi api = run("""
                def firstEven(items):
                    for x in items:
                        if x % 2 == 0:
                            return x
                    return -1
                print(firstEven([1, 3, 4, 5]))
                """);
        assertEquals(List.of("4"), api.printed);
    }

    @Test
    void breakAndContinueWorkInAPlainWhileLoopAtTopLevel() {
        FakeDroneApi api = run("""
                n = 0
                total = 0
                while True:
                    n = n + 1
                    if n > 5:
                        break
                    if n == 2:
                        continue
                    total = total + n
                print(total)
                """);
        assertEquals(List.of("13"), api.printed); // 1+3+4+5 (2 skipped by continue, loop stops after 5)
    }

    @Test
    void breakAndContinueWorkInAForLoopOverAListAtTopLevel() {
        FakeDroneApi api = run("""
                total = 0
                for x in [1, 2, 3, 4, 5]:
                    if x == 2:
                        continue
                    if x == 4:
                        break
                    total = total + x
                print(total)
                """);
        assertEquals(List.of("4"), api.printed); // 1 + 3 (2 skipped, loop stops before reaching 4)
    }

    @Test
    void breakInANestedLoopOnlyExitsTheInnermostLoop() {
        FakeDroneApi api = run("""
                outerRuns = 0
                for outer in range(3):
                    outerRuns = outerRuns + 1
                    for inner in range(10):
                        if inner == 2:
                            break
                        print(inner)
                print(outerRuns)
                """);
        assertEquals(List.of("0", "1", "0", "1", "0", "1", "3"), api.printed);
    }

    /**
     * A function's own frame is parented at the global scope, never the caller's - so a parameter
     * named the same as one of the caller's locals must not leak either direction.
     */
    @Test
    void aCalleeReadsGlobalsNotTheCallersLocals() {
        FakeDroneApi api = run("""
                shared = "global"
                def readsShared():
                    return shared
                def caller():
                    shared = "caller's own local"
                    return readsShared()
                print(caller())
                """);
        assertEquals(List.of("global"), api.printed);
    }

    /**
     * Functions are first-class values (falls out of resolving calls through the environment, see
     * Interpreter#evalCall) - passing one as an argument, the way the future RTOS-task design
     * (create_task(name, priority, budget, fn)) needs to, must work.
     */
    @Test
    void functionsCanBePassedAsArgumentsToOtherFunctions() {
        FakeDroneApi api = run("""
                def double(x):
                    return x * 2
                def applyTwice(f, x):
                    return f(f(x))
                print(applyTwice(double, 3))
                """);
        assertEquals(List.of("12"), api.printed);
    }

    /** Locks in exactly where MAX_CALL_DEPTH (200) draws the line: one level under succeeds, right at it fails. */
    @Test
    void recursionSucceedsRightUpToTheDepthLimitAndFailsOneLevelBeyondIt() {
        FakeDroneApi api = run("""
                def countdown(n):
                    if n <= 0:
                        return 0
                    return 1 + countdown(n - 1)
                print(countdown(199))
                """);
        assertEquals(List.of("199"), api.printed);

        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def countdown(n):
                    if n <= 0:
                        return 0
                    return 1 + countdown(n - 1)
                countdown(200)
                """));
        assertTrue(e.getMessage().contains("recursion"), "expected a recursion-limit message, got: " + e.getMessage());
    }

    /**
     * A loop that only calls a no-op user-defined function (never touches DroneApi) must still
     * trip the runaway-loop watchdog. User function calls are resolved before evalCall's builtin
     * switch (see Interpreter#evalCall), so they never touch the switch's post-call
     * statementsSinceApiCall reset at all - confirmed here the same way the existing
     * appendOnlyLoop/generalPurposeBuiltinOnlyLoop tests above confirm it for methods and
     * general-purpose builtins.
     */
    @Test
    void noOpUserFunctionCallLoopStillTripsTheRunawayWatchdog() {
        MicraLangException ex = assertThrows(MicraLangException.class, () -> run("""
                def noop():
                    pass
                while True:
                    noop()
                """));
        assertTrue(ex.getMessage().contains("too long"), "expected the runaway-loop message, got: " + ex.getMessage());
    }
}
