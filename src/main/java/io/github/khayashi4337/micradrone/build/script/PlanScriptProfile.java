package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.lang.CommandNames;
import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The deterministic profile of construction scripts (D-15, allow-list): only construction commands, control flow and
 * pure helpers. A denied name is refused before anything runs, so the same script always gives the same PlanPatch.
 * Being an allow list, a command added to CommandNames.ALL later is NOT usable here unless it is added on purpose.
 */
public final class PlanScriptProfile {
    public enum Reason { NONDETERMINISTIC, FARM_COMMAND, UNKNOWN, RESERVED_NAME }

    public record Violation(int line, String name, Reason reason) {
    }

    public enum Kind { PLAN, FARM, MIXED, NEUTRAL }

    private static final Set<String> NONDETERMINISTIC = Set.of("random", "create_task", "semaphore", "attach_isr", "raise_interrupt",
            "sleep_ticks", "post", "wait");

    private PlanScriptProfile() {
    }

    public static List<Violation> check(List<Stmt> program) {
        Set<String> own = userFunctions(program);
        List<Violation> out = new ArrayList<>();
        walk(program, own, out, null);
        return out;
    }

    public static Kind classify(List<Stmt> program) {
        Set<String> own = userFunctions(program);
        boolean plan = false;
        boolean farm = false;
        List<String> called = new ArrayList<>();
        walk(program, own, null, called);
        for (String name : called) {
            if (CommandNames.PLAN.contains(name)) {
                plan = true;
            } else if (CommandNames.ALL.contains(name) && !CommandNames.PLAN_HELPERS.contains(name)) {
                farm = true;
            }
        }
        return plan && farm ? Kind.MIXED : plan ? Kind.PLAN : farm ? Kind.FARM : Kind.NEUTRAL;
    }

    private static Set<String> userFunctions(List<Stmt> program) {
        Set<String> names = new HashSet<>();
        for (Stmt s : program) {
            if (s instanceof Stmt.FunctionDef f) {
                names.add(f.name());
            }
        }
        return names;
    }

    private static void walk(List<Stmt> stmts, Set<String> own, List<Violation> out, List<String> called) {
        for (Stmt s : stmts) {
            switch (s) {
                case Stmt.AssignStmt a -> expr(a.value(), own, out, called);
                case Stmt.IndexAssignStmt a -> {
                    expr(a.target(), own, out, called);
                    expr(a.index(), own, out, called);
                    expr(a.value(), own, out, called);
                }
                case Stmt.ExprStmt e -> expr(e.expr(), own, out, called);
                case Stmt.IfStmt i -> {
                    for (Stmt.IfStmt.Branch b : i.branches()) {
                        expr(b.condition(), own, out, called);
                        walk(b.block(), own, out, called);
                    }
                    if (i.elseBlock() != null) {
                        walk(i.elseBlock(), own, out, called);
                    }
                }
                case Stmt.WhileStmt w -> {
                    expr(w.condition(), own, out, called);
                    walk(w.block(), own, out, called);
                }
                case Stmt.ForStmt f -> {
                    expr(f.rangeExpr(), own, out, called);
                    walk(f.block(), own, out, called);
                }
                case Stmt.FunctionDef f -> {
                    if (out != null && (CommandNames.ALL.contains(f.name()) || CommandNames.PLAN.contains(f.name()))) {
                        out.add(new Violation(f.line(), f.name(), Reason.RESERVED_NAME));
                    }
                    walk(f.body(), own, out, called);
                }
                case Stmt.ReturnStmt r -> {
                    if (r.value() != null) {
                        expr(r.value(), own, out, called);
                    }
                }
                case Stmt.BreakStmt b -> { }
                case Stmt.ContinueStmt c -> { }
                case Stmt.PassStmt p -> { }
            }
        }
    }

    private static void expr(Expr e, Set<String> own, List<Violation> out, List<String> called) {
        switch (e) {
            case Expr.Call c -> {
                if (called != null) {
                    called.add(c.name());
                }
                if (out != null) {
                    classify(c.name(), c.line(), own, out);
                }
                for (Expr a : c.args()) {
                    expr(a, own, out, called);
                }
            }
            case Expr.MethodCall m -> {
                if (out != null && (m.name().equals("post") || m.name().equals("wait"))) {
                    out.add(new Violation(m.line(), m.name(), Reason.NONDETERMINISTIC));
                }
                expr(m.target(), own, out, called);
                for (Expr a : m.args()) {
                    expr(a, own, out, called);
                }
            }
            case Expr.Unary u -> expr(u.operand(), own, out, called);
            case Expr.Binary b -> {
                expr(b.left(), own, out, called);
                expr(b.right(), own, out, called);
            }
            case Expr.ListLit l -> l.elements().forEach(x -> expr(x, own, out, called));
            case Expr.SetLit s -> s.elements().forEach(x -> expr(x, own, out, called));
            case Expr.DictLit d -> {
                d.keys().forEach(x -> expr(x, own, out, called));
                d.values().forEach(x -> expr(x, own, out, called));
            }
            case Expr.Index i -> {
                expr(i.target(), own, out, called);
                expr(i.index(), own, out, called);
            }
            case Expr.NumberLit n -> { }
            case Expr.StringLit s -> { }
            case Expr.BoolLit b -> { }
            case Expr.NoneLit n -> { }
            case Expr.VarRef v -> { }
        }
    }

    private static void classify(String name, int line, Set<String> own, List<Violation> out) {
        if (own.contains(name) || CommandNames.PLAN.contains(name) || CommandNames.PLAN_HELPERS.contains(name)) {
            return;
        }
        if (NONDETERMINISTIC.contains(name)) {
            out.add(new Violation(line, name, Reason.NONDETERMINISTIC));
        } else if (CommandNames.ALL.contains(name)) {
            out.add(new Violation(line, name, Reason.FARM_COMMAND));
        } else {
            out.add(new Violation(line, name, Reason.UNKNOWN));
        }
    }
}
