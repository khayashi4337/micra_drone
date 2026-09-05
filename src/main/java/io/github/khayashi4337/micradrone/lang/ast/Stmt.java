package io.github.khayashi4337.micradrone.lang.ast;

import java.util.List;

public sealed interface Stmt {
    int line();

    record AssignStmt(String name, Expr value, int line) implements Stmt {}

    /** {@code target[index] = value} - a list position or a dict key. */
    record IndexAssignStmt(Expr target, Expr index, Expr value, int line) implements Stmt {}

    record ExprStmt(Expr expr, int line) implements Stmt {}

    /** if/elif chain: each branch is a (condition, block) pair; elseBlock is null when absent. */
    record IfStmt(List<Branch> branches, List<Stmt> elseBlock, int line) implements Stmt {
        public record Branch(Expr condition, List<Stmt> block) {}
    }

    record WhileStmt(Expr condition, List<Stmt> block, int line) implements Stmt {}

    /** for name in <rangeExpr>: block — rangeExpr must be a Call to "range" (checked at interpret time). */
    record ForStmt(String varName, Expr rangeExpr, List<Stmt> block, int line) implements Stmt {}

    /** def name(params...): block — nested def (funcDepth &gt; 0 at parse time) is rejected by the parser. */
    record FunctionDef(String name, List<String> params, List<Stmt> body, int line) implements Stmt {}

    /** {@code return [expr]} - value is null for a bare {@code return}. */
    record ReturnStmt(Expr value, int line) implements Stmt {}

    /** Rejected by the parser outside a loop (loopDepth == 0). */
    record BreakStmt(int line) implements Stmt {}

    /** Rejected by the parser outside a loop (loopDepth == 0). */
    record ContinueStmt(int line) implements Stmt {}

    record PassStmt(int line) implements Stmt {}
}
