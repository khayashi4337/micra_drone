package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

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
}
