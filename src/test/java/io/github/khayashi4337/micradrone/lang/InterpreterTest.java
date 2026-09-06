package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

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
    void castLineFailsWithoutARodAndSucceedsOnceOneIsGiven() {
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(cast_line())
                """).scan()).parseProgram());
        assertEquals(List.of("False"), api.printed);

        api.setHasRod(true);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(cast_line())
                print(is_fishing())
                """).scan()).parseProgram());
        assertEquals(List.of("False", "True", "True"), api.printed);
    }

    @Test
    void reelInFailsWithNothingOutAndSucceedsAfterACast() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setHasRod(true);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(reel_in())
                cast_line()
                print(is_fishing())
                print(reel_in())
                print(is_fishing())
                """).scan()).parseProgram());
        assertEquals(List.of("False", "True", "True", "False"), api.printed);
    }

    @Test
    void castLineFailsWhileAlreadyFishing() {
        FakeDroneApi api = new FakeDroneApi(5);
        api.setHasRod(true);
        new Interpreter(api).run(new Parser(new Lexer("""
                cast_line()
                print(cast_line())
                """).scan()).parseProgram());
        assertEquals(List.of("False"), api.printed);
    }

    @Test
    void castLineRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                cast_line(1)
                """));
    }

    @Test
    void reelInRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                reel_in(1)
                """));
    }

    @Test
    void isFishingRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                is_fishing(1)
                """));
    }

    @Test
    void fishingPerceptionCommandsReadStateFromTheApi() {
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(get_rod_durability())
                """).scan()).parseProgram());
        assertEquals(List.of("-1"), api.printed);

        api.setBobbing(true);
        api.setBiting(true);
        api.setOpenWaterCast(true);
        api.setRodDurability(10);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(is_bobber_bobbing())
                print(did_fish_bite())
                print(is_open_water_cast())
                print(get_rod_durability())
                """).scan()).parseProgram());
        assertEquals(List.of("-1", "True", "True", "True", "10"), api.printed);
    }

    @Test
    void isBobberBobbingRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                is_bobber_bobbing(1)
                """));
    }

    @Test
    void didFishBiteRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                did_fish_bite(1)
                """));
    }

    @Test
    void isOpenWaterCastRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                is_open_water_cast(1)
                """));
    }

    @Test
    void getRodDurabilityRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                get_rod_durability(1)
                """));
    }

    @Test
    void anvilRepairCommandsReadAndActThroughTheApi() {
        FakeDroneApi api = new FakeDroneApi(5);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(is_anvil())
                print(get_repair_cost())
                print(repair_rod())
                """).scan()).parseProgram());
        assertEquals(List.of("False", "-1", "False"), api.printed);

        api.setAnvil(true);
        api.setRepairCost(3);
        api.setRepairPossible(true);
        new Interpreter(api).run(new Parser(new Lexer("""
                print(is_anvil())
                print(get_repair_cost())
                print(repair_rod())
                """).scan()).parseProgram());
        assertEquals(List.of("False", "-1", "False", "True", "3", "True"), api.printed);
    }

    @Test
    void isAnvilRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                is_anvil(1)
                """));
    }

    @Test
    void getRepairCostRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                get_repair_cost(1)
                """));
    }

    @Test
    void repairRodRejectsArguments() {
        assertThrows(MicraLangException.class, () -> run("""
                repair_rod(1)
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

    // ---- semaphore() ----
    // Cross-thread blocking behavior (wait() actually blocks until another thread posts) is
    // exercised directly against MicraSemaphore in MicraSemaphoreTest - there is no way to share
    // one semaphore between two independent Interpreter runs yet at the language level (that
    // becomes testable end-to-end once create_task exists, see InterpreterTest's task-related
    // tests once that lands). These tests only cover the language-level dispatch wiring.

    @Test
    void semaphoreStartsEmptySoPostThenWaitOnTheSameThreadDoesNotBlock() {
        FakeDroneApi api = run("""
                s = semaphore()
                s.post()
                s.wait()
                print("done")
                """);
        assertEquals(List.of("done"), api.printed);
    }

    /**
     * Distinguishes "starts at 0 permits" from "starts at >=1 permits" - the test above calls
     * post() before wait() either way, so it can't tell the two apart on its own. A bare
     * semaphore().wait() with nothing posted first must actually block.
     */
    @Test
    void semaphoreConstructorStartsWithZeroPermitsSoABareWaitBlocks() throws InterruptedException {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = new Interpreter(api);
        Thread waiter = new Thread(() -> interpreter.run(new Parser(new Lexer("""
                s = semaphore()
                s.wait()
                print("done")
                """).scan()).parseProgram()));
        waiter.setDaemon(true);
        try {
            waiter.start();
            Thread.sleep(50);
            assertTrue(waiter.isAlive(), "a bare semaphore().wait() should block - it must not start with a permit");
        } finally {
            waiter.interrupt(); // unblock the still-waiting thread so it doesn't outlive this test
            waiter.join(2000);
        }
    }

    @Test
    void semaphorePrintsAsAngleBracketPlaceholder() {
        FakeDroneApi api = run("""
                s = semaphore()
                print(s)
                """);
        assertEquals(List.of("<semaphore>"), api.printed);
    }

    @Test
    void semaphorePostAndWaitRejectArguments() {
        // Check the exact "takes 0 argument(s)" phrasing, not just that the method name appears -
        // "post"/"wait" also appear in the unknown-method fallback's "available: post, wait" text,
        // so a weaker contains("post") would still pass even if the post() case were deleted
        // entirely and every call fell through to that fallback (Codex review finding).
        MicraLangException postError = assertThrows(MicraLangException.class, () -> run("""
                s = semaphore()
                s.post(1)
                """));
        assertTrue(postError.getMessage().contains(".post() takes 0 argument"),
                "expected a post() arg-count error, got: " + postError.getMessage());

        MicraLangException waitError = assertThrows(MicraLangException.class, () -> run("""
                s = semaphore()
                s.post()
                s.wait(1)
                """));
        assertTrue(waitError.getMessage().contains(".wait() takes 0 argument"),
                "expected a wait() arg-count error, got: " + waitError.getMessage());
    }

    @Test
    void unknownSemaphoreMethodMentionsPostAndWait() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                s = semaphore()
                s.acquire()
                """));
        assertTrue(e.getMessage().contains("post, wait"), "expected the method list in the error, got: " + e.getMessage());
    }

    @Test
    void semaphoreConstructorTakesNoArguments() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("s = semaphore(1)"));
        assertTrue(e.getMessage().contains("takes 0 argument"), "expected an arg-count error, got: " + e.getMessage());
    }

    // ---- create_task() ----
    // Every test that actually creates a task must clean it up via interpreter.stopAllTasks() in a
    // finally block - a leaked daemon thread survives past the test method (see docs/design/
    // lang_rtos_task_foundation.md's "SampleScriptsTestへの影響" for why this matters).

    private static Interpreter runKeepingInterpreter(FakeDroneApi api, String source) {
        Interpreter interpreter = new Interpreter(api);
        interpreter.run(new Parser(new Lexer(source).scan()).parseProgram());
        return interpreter;
    }

    @Test
    void createTaskAcceptsAZeroArgFunctionAndReturnsAResultCode() {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    def blink():
                        pass
                    print(create_task("blinker", 5, 0, blink))
                    """);
            assertEquals(List.of("ACCEPTED"), api.printed);
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    @Test
    void createTaskDeniesANonFunctionValue() {
        FakeDroneApi api = run("""
                print(create_task("t", 5, 0, 42))
                """);
        assertEquals(List.of("DENIED"), api.printed);
    }

    @Test
    void createTaskDeniesAFunctionThatTakesArguments() {
        FakeDroneApi api = run("""
                def needsArg(x):
                    pass
                print(create_task("t", 5, 0, needsArg))
                """);
        assertEquals(List.of("DENIED"), api.printed);
    }

    @Test
    void createTaskDeniesADuplicateName() {
        // The first task must genuinely still be alive (not have already finished and released
        // its name) by the time the second create_task call happens - a "pass"-only body would
        // race, since a task's finally block can release its name before the very next statement
        // on the calling thread even runs, making the second call's expected DENIED flaky (Codex
        // review finding). Blocking on a never-posted semaphore guarantees it can't have finished.
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    gate = semaphore()
                    def blocked():
                        gate.wait()
                    print(create_task("same_name", 5, 0, blocked))
                    print(create_task("same_name", 5, 0, blocked))
                    """);
            assertEquals(List.of("ACCEPTED", "DENIED"), api.printed);
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    @Test
    void createTaskDeniesANegativeBudget() {
        FakeDroneApi api = run("""
                def blink():
                    pass
                print(create_task("a", 5, -1, blink))
                """);
        assertEquals(List.of("DENIED"), api.printed);
    }

    /**
     * This language has no exponent-notation literal (e.g. "1e300"), so an actually non-finite
     * value has to be produced the same way a script accidentally could: repeated squaring past
     * Double.MAX_VALUE (~1.8e308) overflows to Infinity, same as plain Java double arithmetic.
     * 1e21 squared four times is 1e21, 1e42, 1e84, 1e168, 1e336 - the last exceeds MAX_VALUE.
     */
    @Test
    void createTaskDeniesANonFiniteBudget() {
        FakeDroneApi api = run("""
                def blink():
                    pass
                huge = 1000000000000000000000.0
                huge = huge * huge
                huge = huge * huge
                huge = huge * huge
                huge = huge * huge
                print(create_task("b", 5, huge, blink))
                """);
        assertEquals(List.of("DENIED"), api.printed);
    }

    @Test
    void createTaskAcceptsAnExplicitZeroBudgetAsUnlimited() {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    def blink():
                        pass
                    print(create_task("unlimited", 5, 0, blink))
                    """);
            assertEquals(List.of("ACCEPTED"), api.printed);
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    /**
     * Regression test for the fractional-budget bug Fable5.1's review found: a truncating cast
     * ((long) 0.5 == 0) would silently turn "budget 0.5" into budgetTicks == 0, which create_task
     * treats as "no limit at all" (budgetTicks > 0 gates the watchdog) - the exact silently-
     * unbounded-task outcome create_task's finite/non-negative check exists to prevent. Math.ceil
     * fixes this: 0.5 must round UP to a real 1-tick budget, so a task stuck forever still gets a
     * working (if very short) watchdog instead of none at all.
     */
    @Test
    void aFractionalBudgetBetweenZeroAndOneRoundsUpToARealOneTickBudget() throws Exception {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    stuck = semaphore()
                    def blocked_forever():
                        stuck.wait()
                    print(create_task("fractional_budget", 5, 0.5, blocked_forever))
                    """);
            assertEquals(List.of("ACCEPTED"), api.printed);

            // If 0.5 had truncated to 0 (no limit), this name would never free up on its own.
            long deadline = System.currentTimeMillis() + 2000;
            String secondAttempt = "DENIED";
            while (System.currentTimeMillis() < deadline) {
                List<io.github.khayashi4337.micradrone.lang.ast.Stmt> retry = new Parser(
                        new Lexer("print(create_task(\"fractional_budget\", 5, 0, blocked_forever))").scan())
                        .parseProgram();
                interpreter.run(retry);
                secondAttempt = api.printed.get(api.printed.size() - 1);
                if (secondAttempt.equals("ACCEPTED")) {
                    break;
                }
                Thread.sleep(10);
            }
            assertEquals("ACCEPTED", secondAttempt,
                    "budget 0.5 should have rounded up to a real (if short) budget and freed the name, not become unlimited");
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    /**
     * Regression test for the scoping bug found in review: a task's body must get its own child
     * frame (like a normal function call), not write straight into the forked global frame. Without
     * the fix, task_body's "x = 99" would overwrite the global x, and helper() (which always reads
     * the true global scope) would then see 99 instead of the original 1.
     */
    @Test
    void taskLocalAssignmentDoesNotLeakIntoTheForkedGlobalScope() throws Exception {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    x = 1
                    def helper():
                        return x
                    def task_body():
                        x = 99
                        print(helper())
                    create_task("scoping_check", 5, 0, task_body)
                    """);
            // Give the task thread a moment to actually run its one statement and print.
            awaitPrinted(api, 1, 2000);
            assertEquals(List.of("1"), api.printedSnapshot());
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    /** Polls api's printed output until it has at least {@code expectedSize} entries or the timeout elapses. */
    private static void awaitPrinted(FakeDroneApi api, int expectedSize, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        // Read through the synchronized snapshot, not the raw field - a task thread may still be
        // concurrently calling print() (which is synchronized) while this loop polls.
        while (api.printedSnapshot().size() < expectedSize) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + expectedSize + " printed line(s), got: " + api.printedSnapshot());
            }
            Thread.sleep(2);
        }
    }

    /**
     * The whole point of semaphore()/create_task(): one task posts, another (or the main script,
     * here) waits, sharing the exact same MicraSemaphore object via the shallow-copied global scope.
     */
    @Test
    void twoTasksHandOffThroughASharedSemaphore() throws Exception {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    ready = semaphore()
                    def producer():
                        print("producing")
                        ready.post()
                    def consumer():
                        ready.wait()
                        print("consumed")
                    create_task("producer", 5, 0, producer)
                    create_task("consumer", 5, 0, consumer)
                    """);
            awaitPrinted(api, 2, 2000);
            assertEquals(Set.of("producing", "consumed"), Set.copyOf(api.printedSnapshot()));
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    @Test
    void concurrentTaskCapDeniesTheSeventeenthTaskUntilOneFrees() throws Exception {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    gate = semaphore()
                    def blocked():
                        gate.wait()
                    for i in range(16):
                        create_task(str(i), 5, 0, blocked)
                    print(create_task("seventeenth", 5, 0, blocked))
                    """);
            assertEquals(List.of("DENIED"), api.printed);

            // Post the shared gate once - this wakes exactly one of the 16 blocked tasks, which
            // then finishes its body and (asynchronously, from its own finally block) releases its
            // slot. Poll retrying create_task("seventeenth", ...) until that slot actually frees up.
            interpreter.run(new Parser(new Lexer("gate.post()").scan()).parseProgram());
            List<io.github.khayashi4337.micradrone.lang.ast.Stmt> retry =
                    new Parser(new Lexer("print(create_task(\"seventeenth\", 5, 0, blocked))").scan()).parseProgram();
            long deadline = System.currentTimeMillis() + 2000;
            String result = "DENIED";
            while (System.currentTimeMillis() < deadline) {
                interpreter.run(retry);
                result = api.printed.get(api.printed.size() - 1);
                if (result.equals("ACCEPTED")) {
                    break;
                }
                Thread.sleep(10);
            }
            assertEquals("ACCEPTED", result, "a slot should have freed up once the posted task finished");
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    @Test
    void budgetTicksInterruptsATaskThatNeverFinishesOnItsOwn() throws Exception {
        FakeDroneApi api = new FakeDroneApi(5);
        Interpreter interpreter = null;
        try {
            interpreter = runKeepingInterpreter(api, """
                    stuck = semaphore()
                    def blocked_forever():
                        stuck.wait()
                    print(create_task("budgeted", 5, 1, blocked_forever))
                    """);
            assertEquals(List.of("ACCEPTED"), api.printed);

            // budget_ticks=1 == 50ms; poll until the name frees up again (proving the watchdog
            // actually interrupted the task and its finally released the name), well within margin.
            long deadline = System.currentTimeMillis() + 2000;
            String secondAttempt = "DENIED";
            while (System.currentTimeMillis() < deadline) {
                List<io.github.khayashi4337.micradrone.lang.ast.Stmt> retry =
                        new Parser(new Lexer("print(create_task(\"budgeted\", 5, 0, blocked_forever))").scan()).parseProgram();
                interpreter.run(retry);
                secondAttempt = api.printed.get(api.printed.size() - 1);
                if (secondAttempt.equals("ACCEPTED")) {
                    break;
                }
                Thread.sleep(10);
            }
            assertEquals("ACCEPTED", secondAttempt, "the budgeted task should eventually be interrupted and free its name");
        } finally {
            if (interpreter != null) interpreter.stopAllTasks();
        }
    }

    // ---- sleep_ticks() ----
    // The real tick-driven pacing (and the fix for the 5-second-timeout bug the design review
    // found) is exercised end-to-end against LiveDroneApi/PacedActionQueue in
    // DroneScriptRunnerTest - FakeDroneApi doesn't model ticks at all, so these only check that the
    // interpreter dispatches the call with the right argument.

    @Test
    void sleepTicksDispatchesToTheApiWithItsArgument() {
        FakeDroneApi api = run("sleep_ticks(10)");
        assertEquals(List.of("sleep_ticks:10.0"), api.calls);
    }

    @Test
    void sleepTicksRequiresExactlyOneArgument() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("sleep_ticks()"));
        assertTrue(e.getMessage().contains("sleep_ticks() takes 1 argument"), "expected an arg-count error, got: " + e.getMessage());
    }

    // ---- attach_isr() / raise_interrupt() ----

    @Test
    void raiseInterruptRunsTheAttachedHandlerSynchronously() {
        // The handler itself can't call print() (forbidden in ISR context, see below) - it records
        // into a shared list instead (pure computation, always allowed), read back by the main
        // script (outside ISR context) once raise_interrupt returns.
        FakeDroneApi api = run("""
                seen = []
                def handler():
                    seen.append("fired")
                attach_isr("edge", handler)
                raise_interrupt("edge")
                print(seen)
                print("after")
                """);
        assertEquals(List.of("[\"fired\"]", "after"), api.printed);
    }

    @Test
    void raiseInterruptOnAnUnregisteredFaceDoesNothing() {
        FakeDroneApi api = run("""
                raise_interrupt("no_such_face")
                print("still here")
                """);
        assertEquals(List.of("still here"), api.printed);
    }

    @Test
    void attachIsrRejectsAHandlerThatTakesArguments() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def needsArg(x):
                    pass
                attach_isr("edge", needsArg)
                """));
        assertTrue(e.getMessage().contains("no arguments"), "expected a handler-signature error, got: " + e.getMessage());
    }

    @Test
    void attachIsrRejectsANonFunctionHandler() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("attach_isr(\"edge\", 42)"));
        assertTrue(e.getMessage().contains("no arguments"), "expected a handler-signature error, got: " + e.getMessage());
    }

    @Test
    void attachingTheSameFaceTwiceReplacesTheHandler() {
        FakeDroneApi api = run("""
                seen = []
                def first():
                    seen.append("first")
                def second():
                    seen.append("second")
                attach_isr("edge", first)
                attach_isr("edge", second)
                raise_interrupt("edge")
                print(seen)
                """);
        assertEquals(List.of("[\"second\"]"), api.printed);
    }

    @Test
    void isrHandlerCannotCallMoveOrOtherDroneApiBuiltins() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def handler():
                    move("east")
                attach_isr("edge", handler)
                raise_interrupt("edge")
                """));
        assertTrue(e.getMessage().contains("interrupt handler"), "expected an ISR-context rejection, got: " + e.getMessage());
    }

    @Test
    void isrHandlerCannotCallPrint() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def handler():
                    print("should not run")
                attach_isr("edge", handler)
                raise_interrupt("edge")
                """));
        assertTrue(e.getMessage().contains("interrupt handler"), "expected an ISR-context rejection, got: " + e.getMessage());
    }

    @Test
    void isrHandlerCanPostASemaphoreButNotWaitOnOne() {
        FakeDroneApi api = run("""
                s = semaphore()
                def poster():
                    s.post()
                attach_isr("edge", poster)
                raise_interrupt("edge")
                s.wait()
                print("woken by the isr's post")
                """);
        assertEquals(List.of("woken by the isr's post"), api.printed);

        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                s = semaphore()
                def waiter():
                    s.wait()
                attach_isr("edge", waiter)
                raise_interrupt("edge")
                """));
        assertTrue(e.getMessage().contains("interrupt handler"), "expected wait() to be rejected in ISR context, got: " + e.getMessage());
    }

    @Test
    void isrHandlerCanCallAPureHelperFunctionButNotOneThatTouchesDroneApi() {
        // Calling a user function at all must be allowed inside an ISR (isrContext only blocks at
        // the point a forbidden builtin is actually reached, not the function call itself) - see
        // the placement note on the ISR gate in evalCall. The handler stores the result rather than
        // print()ing it directly, since print() is itself forbidden in ISR context.
        FakeDroneApi api = run("""
                seen = []
                def pure_add(a, b):
                    return a + b
                def handler():
                    seen.append(pure_add(2, 3))
                attach_isr("edge", handler)
                raise_interrupt("edge")
                print(seen)
                """);
        assertEquals(List.of("[5]"), api.printed);

        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                def touches_drone():
                    return move("east")
                def handler():
                    touches_drone()
                attach_isr("edge", handler)
                raise_interrupt("edge")
                """));
        assertTrue(e.getMessage().contains("interrupt handler"),
                "expected the rejection to happen once the helper reaches move(), got: " + e.getMessage());
    }

    @Test
    void isrHandlerCannotCreateATaskOrReattachOrReRaise() {
        for (String forbidden : List.of(
                "create_task(\"x\", 5, 0, handler)",
                "attach_isr(\"other\", handler)",
                "raise_interrupt(\"other\")")) {
            MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                    def handler():
                        %s
                    attach_isr("edge", handler)
                    raise_interrupt("edge")
                    """.formatted(forbidden)));
            assertTrue(e.getMessage().contains("interrupt handler"),
                    "expected " + forbidden + " to be rejected in ISR context, got: " + e.getMessage());
        }
    }

    /**
     * Regression test for the runaway-detection-bypass Codex's review found: raise_interrupt on an
     * unregistered face (a near no-op) must not be exempt from GENERAL_PURPOSE_BUILTINS's reset
     * rule the same way create_task already isn't (see the "暴走検知すり抜けの修正" note on
     * GENERAL_PURPOSE_BUILTINS) - otherwise a tight loop of it could spin forever undetected.
     */
    @Test
    void raiseInterruptLoopOnAnUnregisteredFaceStillTripsTheRunawayWatchdog() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                while True:
                    raise_interrupt("no_such_face")
                """));
        assertTrue(e.getMessage().contains("too long"), "expected the runaway-loop message, got: " + e.getMessage());
    }

    /**
     * Same regression as raiseInterruptLoopOnAnUnregisteredFaceStillTripsTheRunawayWatchdog, for
     * create_task specifically - explicitly called out in docs/design/lang_rtos_task_foundation.md's
     * 検証方法 section ("while True: create_task(...)（常にDENIED）...が RUNAWAY_STATEMENT_THRESHOLD
     * で正しく止まる") but not yet covered by a test (Codex review finding). Passing a non-function
     * value means every call is denied instantly without ever spawning a real thread.
     */
    @Test
    void createTaskLoopThatAlwaysDeniesStillTripsTheRunawayWatchdog() {
        MicraLangException e = assertThrows(MicraLangException.class, () -> run("""
                while True:
                    create_task("x", 5, 0, 42)
                """));
        assertTrue(e.getMessage().contains("too long"), "expected the runaway-loop message, got: " + e.getMessage());
    }

    /**
     * Regression test mirroring taskLocalAssignmentDoesNotLeakIntoTheForkedGlobalScope, but for an
     * ISR handler (Codex review finding: the task-side test existed, but nothing directly verified
     * runIsolatedBody's child-frame fix for the ISR path specifically, even though raiseInterrupt
     * uses the exact same method).
     */
    @Test
    void isrHandlerLocalAssignmentDoesNotLeakIntoTheForkedGlobalScope() {
        FakeDroneApi api = run("""
                x = 1
                def helper():
                    return x
                seen = []
                def handler():
                    x = 99
                    seen.append(helper())
                attach_isr("edge", handler)
                raise_interrupt("edge")
                print(seen)
                """);
        assertEquals(List.of("[1]"), api.printed);
    }

    /**
     * Positive coverage for ISR_SAFE_BUILTINS (Codex review finding: the existing ISR tests only
     * ever exercised the gate's rejections plus one user-defined pure-computation helper; nothing
     * called an actual general-purpose builtin directly from inside a handler, so a builtin
     * accidentally dropped from ISR_SAFE_BUILTINS wouldn't have been caught).
     */
    @Test
    void isrHandlerCanCallGeneralPurposeBuiltinsDirectly() {
        FakeDroneApi api = run("""
                seen = []
                def handler():
                    seen.append(len([1, 2, 3]))
                    seen.append(abs(-5))
                    seen.append(str(7))
                    local_sem = semaphore()
                    local_sem.post()
                    seen.append("semaphore ok")
                attach_isr("edge", handler)
                raise_interrupt("edge")
                print(seen)
                """);
        assertEquals(List.of("[3, 5, \"7\", \"semaphore ok\"]"), api.printed);
    }
}
