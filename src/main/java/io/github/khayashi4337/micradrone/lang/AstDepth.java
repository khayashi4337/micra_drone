package io.github.khayashi4337.micradrone.lang;

import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Measures the depth of a parsed program with an explicit stack instead of recursion: a leaf
 * node counts 1, every other node counts 1 + the deepest of its children, and a program's
 * depth is the deepest of its top-level statements (an empty program counts 0).
 *
 * <p>Iterative on purpose: this measurement exists to decide whether the interpreter's and the
 * static profile's recursion may run on a thread of a chosen stack size, so it must not itself
 * be limited by the very stack it is protecting. A {@code 1+1+...+1} chain of n terms parses
 * without ever nesting (left-associative chains are built by loops), yet produces an AST of
 * depth n - measuring the finished tree is the only way to bound it.
 *
 * <p>Children are derived by reading the sealed {@link Stmt}/{@link Expr} variants: the
 * switches below have no {@code default}, so a newly added variant stops the build until its
 * children are listed here.
 */
public final class AstDepth {
    private AstDepth() {
    }

    /** The depth of the deepest top-level statement in {@code program}; 0 when empty. */
    public static int of(List<Stmt> program) {
        int deepest = 0;
        for (Stmt stmt : program) {
            deepest = Math.max(deepest, depthOf(stmt));
        }
        return deepest;
    }

    /** Post-order walk on an explicit stack: a node's depth is 1 + its deepest child's. */
    private static int depthOf(Object node) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(node));
        while (true) {
            Frame top = stack.peek();
            if (top.next < top.children.size()) {
                stack.push(new Frame(top.children.get(top.next++)));
                continue;
            }
            int depth = 1 + top.deepestChild;
            stack.pop();
            if (stack.isEmpty()) {
                return depth;
            }
            Frame parent = stack.peek();
            parent.deepestChild = Math.max(parent.deepestChild, depth);
        }
    }

    /** One node being measured: its children and, once resolved, the deepest child depth seen. */
    private static final class Frame {
        final List<Object> children;
        int next = 0;
        int deepestChild = 0;

        Frame(Object node) {
            this.children = childrenOf(node);
        }
    }

    /**
     * All direct children of a {@link Stmt} or {@link Expr} node. {@link Stmt.IfStmt.Branch} is
     * flattened into its condition plus its block's statements so it contributes no level of its
     * own - it is a bookkeeping record, not a program node.
     */
    private static List<Object> childrenOf(Object node) {
        List<Object> children = new ArrayList<>();
        if (node instanceof Stmt s) {
            switch (s) {
                case Stmt.AssignStmt a -> children.add(a.value());
                case Stmt.IndexAssignStmt a -> {
                    children.add(a.target());
                    children.add(a.index());
                    children.add(a.value());
                }
                case Stmt.ExprStmt e -> children.add(e.expr());
                case Stmt.IfStmt i -> {
                    for (Stmt.IfStmt.Branch branch : i.branches()) {
                        children.add(branch.condition());
                        children.addAll(branch.block());
                    }
                    if (i.elseBlock() != null) {
                        children.addAll(i.elseBlock());
                    }
                }
                case Stmt.WhileStmt w -> {
                    children.add(w.condition());
                    children.addAll(w.block());
                }
                case Stmt.ForStmt f -> {
                    children.add(f.rangeExpr());
                    children.addAll(f.block());
                }
                case Stmt.FunctionDef f -> children.addAll(f.body());
                case Stmt.ReturnStmt r -> {
                    if (r.value() != null) {
                        children.add(r.value());
                    }
                }
                case Stmt.BreakStmt b -> {
                }
                case Stmt.ContinueStmt c -> {
                }
                case Stmt.PassStmt p -> {
                }
            }
        } else if (node instanceof Expr e) {
            switch (e) {
                case Expr.Unary u -> children.add(u.operand());
                case Expr.Binary b -> {
                    children.add(b.left());
                    children.add(b.right());
                }
                case Expr.Call c -> children.addAll(c.args());
                case Expr.ListLit l -> children.addAll(l.elements());
                case Expr.DictLit d -> {
                    children.addAll(d.keys());
                    children.addAll(d.values());
                }
                case Expr.SetLit s -> children.addAll(s.elements());
                case Expr.Index i -> {
                    children.add(i.target());
                    children.add(i.index());
                }
                case Expr.MethodCall m -> {
                    children.add(m.target());
                    children.addAll(m.args());
                }
                case Expr.NumberLit n -> {
                }
                case Expr.StringLit s -> {
                }
                case Expr.BoolLit b -> {
                }
                case Expr.NoneLit n -> {
                }
                case Expr.VarRef v -> {
                }
            }
        } else {
            throw new IllegalArgumentException("not an AST node: " + node.getClass().getSimpleName());
        }
        return children;
    }
}
