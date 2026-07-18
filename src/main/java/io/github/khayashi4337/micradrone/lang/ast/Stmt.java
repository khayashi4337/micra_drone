package io.github.khayashi4337.micradrone.lang.ast;

import java.util.List;

public sealed interface Stmt {
    int line();

    record AssignStmt(String name, Expr value, int line) implements Stmt {}

    record ExprStmt(Expr expr, int line) implements Stmt {}

    /** if/elif chain: each branch is a (condition, block) pair; elseBlock is null when absent. */
    record IfStmt(List<Branch> branches, List<Stmt> elseBlock, int line) implements Stmt {
        public record Branch(Expr condition, List<Stmt> block) {}
    }

    record WhileStmt(Expr condition, List<Stmt> block, int line) implements Stmt {}

    /** for name in <rangeExpr>: block — rangeExpr must be a Call to "range" (checked at interpret time). */
    record ForStmt(String varName, Expr rangeExpr, List<Stmt> block, int line) implements Stmt {}
}
