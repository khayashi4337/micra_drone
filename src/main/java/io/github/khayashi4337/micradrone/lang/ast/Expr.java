package io.github.khayashi4337.micradrone.lang.ast;

import java.util.List;

public sealed interface Expr {
    int line();

    record NumberLit(double value, int line) implements Expr {}

    record StringLit(String value, int line) implements Expr {}

    record BoolLit(boolean value, int line) implements Expr {}

    record NoneLit(int line) implements Expr {}

    record VarRef(String name, int line) implements Expr {}

    record Unary(String op, Expr operand, int line) implements Expr {}

    record Binary(String op, Expr left, Expr right, int line) implements Expr {}

    record Call(String name, List<Expr> args, int line) implements Expr {}
}
