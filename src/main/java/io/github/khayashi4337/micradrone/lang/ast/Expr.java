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

    /** {@code [a, b, c]} - also {@code []}. */
    record ListLit(List<Expr> elements, int line) implements Expr {}

    /** {@code {k: v, ...}} - also {@code {}}, which is an empty dict (as in Python; {@code set()} makes an empty set). */
    record DictLit(List<Expr> keys, List<Expr> values, int line) implements Expr {}

    /** {@code {a, b, c}} - told apart from a dict literal by the absence of a {@code :} after the first element. */
    record SetLit(List<Expr> elements, int line) implements Expr {}

    /** {@code target[index]} - a list position, a dict key, or a character of a string. */
    record Index(Expr target, Expr index, int line) implements Expr {}
}
