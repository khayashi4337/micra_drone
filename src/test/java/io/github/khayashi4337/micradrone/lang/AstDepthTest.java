package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AstDepthTest {
    private static List<Stmt> parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    @Test
    void aLeafStatementIsOneAndEveryNodeAddsOne() {
        assertEquals(0, AstDepth.of(List.of()));
        assertEquals(1, AstDepth.of(parse("pass\n")));
        // AssignStmt + NumberLit
        assertEquals(2, AstDepth.of(parse("x = 1\n")));
        // AssignStmt + Binary + leaves
        assertEquals(3, AstDepth.of(parse("x = 1 + 2\n")));
        // a left-associative n-term chain under `x =` measures n + 1
        assertEquals(5, AstDepth.of(parse("x = 1 + 1 + 1 + 1\n")));
    }

    @Test
    void blocksAndBranchesCountAsLevels() {
        // if (1) > if (2) > pass (3); the condition VarRef is a leaf sibling, not deeper
        assertEquals(3, AstDepth.of(parse("if a:\n    if b:\n        pass\n")));
        // def (1) > return (2) > unary (3) > leaf (4)
        assertEquals(4, AstDepth.of(parse("def f():\n    return -x\n")));
        // while (1) > ExprStmt (2) > MethodCall (3) > ListLit arg (4) > leaf (5)
        assertEquals(5, AstDepth.of(parse("while x:\n    l.append([y])\n")));
    }

    @Test
    void parenthesesDoNotExistInTheAst() {
        // the parser returns the inner expression, so only the parse nesting
        // limit - never this measurement - sees bracket depth
        assertEquals(2, AstDepth.of(parse("x = ((((((1))))))\n")));
    }

    @Test
    void theDeepestTopLevelStatementDecides() {
        assertEquals(5, AstDepth.of(parse("x = 1\ny = 1 + 1 + 1 + 1\npass\n")));
    }

    @Test
    void aThousandTermChainIsMeasuredIterativelyWithoutOverflow() {
        // deeper than any stack-bounded recursion would survive on a small
        // thread stack - exactly the shape the runner asks about
        assertEquals(1_001, AstDepth.of(parse("x = " + "1 + ".repeat(999) + "1\n")));
    }

    @Test
    void everySealedAstVariantIsHandled() {
        // the switches inside AstDepth have no default, so a new Stmt/Expr
        // variant fails the build outright; this pins the variant LIST so the
        // coverage claim itself cannot silently rot
        assertEquals(
                Set.of("AssignStmt", "IndexAssignStmt", "ExprStmt", "IfStmt", "WhileStmt", "ForStmt",
                        "FunctionDef", "ReturnStmt", "BreakStmt", "ContinueStmt", "PassStmt"),
                simpleNames(Stmt.class.getPermittedSubclasses()));
        assertEquals(
                Set.of("NumberLit", "StringLit", "BoolLit", "NoneLit", "VarRef", "Unary", "Binary",
                        "Call", "ListLit", "DictLit", "SetLit", "Index", "MethodCall"),
                simpleNames(Expr.class.getPermittedSubclasses()));
    }

    private static Set<String> simpleNames(Class<?>[] permitted) {
        return Arrays.stream(permitted).map(Class::getSimpleName).collect(Collectors.toSet());
    }

    @Test
    void everyVariantContributesItsChildren() {
        // spot-check the child relations that are easy to get wrong: Index and
        // IndexAssignStmt walk BOTH target and index, DictLit walks keys and
        // values, ForStmt walks the range expression and the body
        assertEquals(4, AstDepth.of(parse("x = a[b[0]]\n"))); // assign > index > index > leaf
        assertEquals(3, AstDepth.of(parse("d[x[0]] = 1\n"))); // index-assign > index expr > leaf
        assertEquals(4, AstDepth.of(parse("x = {k: [v]}\n"))); // assign > dict > list > leaf
        assertEquals(4, AstDepth.of(parse("for i in range(3):\n    y = f(i)\n")));
        assertTrue(AstDepth.of(parse("if a:\n    pass\nelse:\n    x = [b[c]]\n")) >= 5);
    }

    /**
     * One case per (variant, child position): in every snippet the probed child
     * carries the whole depth - {@code [[z]]} is a three-node subtree (list of a
     * list of a leaf) - while every sibling child is a leaf, so a childrenOf
     * mutant that drops this one position measures strictly shallower and fails
     * the case. Expected depths are hand-derived from the counting rule in the
     * AstDepth javadoc: a leaf counts 1, a node counts 1 + its deepest child, a
     * program is its deepest statement, and an IfStmt.Branch is flattened into
     * its condition plus its block's statements (no level of its own).
     */
    private static Stream<Arguments> everyChildPosition() {
        return Stream.of(
                // statement variants
                Arguments.of("AssignStmt value", "x = [[z]]\n", 4),
                Arguments.of("IndexAssignStmt target", "[[z]][0] = 1\n", 4),
                Arguments.of("IndexAssignStmt index", "y[[[z]]] = 1\n", 4),
                Arguments.of("IndexAssignStmt value", "y[0] = [[z]]\n", 4),
                Arguments.of("ExprStmt expr", "f([[z]])\n", 5),
                Arguments.of("IfStmt if condition", "if [[z]]:\n    pass\n", 4),
                Arguments.of("IfStmt elif condition", "if a:\n    pass\nelif [[z]]:\n    pass\n", 4),
                Arguments.of("IfStmt if block", "if a:\n    x = [[z]]\n", 5),
                Arguments.of("IfStmt elif block", "if a:\n    pass\nelif b:\n    x = [[z]]\n", 5),
                Arguments.of("IfStmt else block", "if a:\n    pass\nelse:\n    x = [[z]]\n", 5),
                Arguments.of("WhileStmt condition", "while [[z]]:\n    pass\n", 4),
                Arguments.of("WhileStmt block", "while a:\n    x = [[z]]\n", 5),
                Arguments.of("ForStmt range expression", "for i in [[z]]:\n    pass\n", 4),
                Arguments.of("ForStmt block", "for i in range(3):\n    x = [[z]]\n", 5),
                Arguments.of("FunctionDef body", "def f():\n    x = [[z]]\n", 5),
                Arguments.of("ReturnStmt value", "def f():\n    return [[z]]\n", 5),
                // expression variants
                Arguments.of("Binary left", "x = [[z]] + 1\n", 5),
                Arguments.of("Binary right", "x = 1 + [[z]]\n", 5),
                Arguments.of("Unary operand", "x = -[[z]]\n", 5),
                Arguments.of("Call args", "x = f([[z]])\n", 5),
                Arguments.of("MethodCall target", "x = [[z]].at(1)\n", 5),
                Arguments.of("MethodCall args", "x = y.at([[z]])\n", 5),
                Arguments.of("Index target", "x = [[z]][0]\n", 5),
                Arguments.of("Index index", "x = y[[[z]]]\n", 5),
                Arguments.of("ListLit elements", "x = [1, [[z]]]\n", 5),
                Arguments.of("DictLit keys", "x = {[[z]]: 1}\n", 5),
                Arguments.of("DictLit values", "x = {1: [[z]]}\n", 5),
                Arguments.of("SetLit elements", "x = {1, [[z]]}\n", 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyChildPosition")
    void everyChildPositionContributesToTheDepth(String position, String source, int expectedDepth) {
        assertEquals(expectedDepth, AstDepth.of(parse(source)), position);
    }
}
