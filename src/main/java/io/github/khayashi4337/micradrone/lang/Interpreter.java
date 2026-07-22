package io.github.khayashi4337.micradrone.lang;

import java.util.List;

import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/**
 * Tree-walking interpreter for the Micra Drone script language (MVP subset).
 * Runs entirely on the caller's thread; callers are expected to invoke
 * {@link #run(List)} from a dedicated worker thread and use
 * {@link Thread#interrupt()} on that thread to request a stop.
 */
public final class Interpreter {
    /** Number of statements allowed to execute with zero DroneApi calls before we assume a runaway loop. */
    private static final long RUNAWAY_STATEMENT_THRESHOLD = 1_000_000;

    private final DroneApi api;
    /** Optional debugger (breakpoints/pause/step - see DebugController); null = no debugging overhead. */
    private final DebugController debug;
    private final Environment env = new Environment();
    private long statementsSinceApiCall = 0;

    public Interpreter(DroneApi api) {
        this(api, null);
    }

    public Interpreter(DroneApi api, DebugController debug) {
        this.api = api;
        this.debug = debug;
    }

    public void run(List<Stmt> program) {
        execBlock(program);
    }

    // ---- statements ----

    private void execBlock(List<Stmt> stmts) {
        for (Stmt stmt : stmts) {
            execStmt(stmt);
        }
    }

    private void execStmt(Stmt stmt) {
        checkCancellation(stmt.line());
        if (debug != null) {
            debug.onStatement(stmt.line()); // may block here while paused at a breakpoint/step
        }
        switch (stmt) {
            case Stmt.AssignStmt s -> env.set(s.name(), eval(s.value()));
            case Stmt.ExprStmt s -> eval(s.expr());
            case Stmt.IfStmt s -> execIf(s);
            case Stmt.WhileStmt s -> execWhile(s);
            case Stmt.ForStmt s -> execFor(s);
        }
    }

    private void execIf(Stmt.IfStmt s) {
        for (Stmt.IfStmt.Branch branch : s.branches()) {
            if (isTruthy(eval(branch.condition()))) {
                execBlock(branch.block());
                return;
            }
        }
        if (s.elseBlock() != null) {
            execBlock(s.elseBlock());
        }
    }

    private void execWhile(Stmt.WhileStmt s) {
        enterLoopForDebug();
        try {
            while (isTruthy(eval(s.condition()))) {
                checkCancellation(s.line());
                execBlock(s.block());
            }
        } finally {
            exitLoopForDebug();
        }
    }

    private void execFor(Stmt.ForStmt s) {
        if (!(s.rangeExpr() instanceof Expr.Call call) || !call.name().equals("range")) {
            throw new MicraLangException(s.line(), "for-in currently only supports range(...)");
        }
        double[] bounds = rangeBounds(call);
        double start = bounds[0];
        double stop = bounds[1];
        double step = bounds[2];
        if (step == 0) {
            throw new MicraLangException(call.line(), "range() step must not be 0");
        }
        enterLoopForDebug();
        try {
            for (double i = start; step > 0 ? i < stop : i > stop; i += step) {
                checkCancellation(s.line());
                env.set(s.varName(), i);
                execBlock(s.block());
            }
        } finally {
            exitLoopForDebug();
        }
    }

    /** Loop-depth bookkeeping for the debugger's step-out - see {@link DebugController#stepOut}. */
    private void enterLoopForDebug() {
        if (debug != null) {
            debug.enterLoop();
        }
    }

    private void exitLoopForDebug() {
        if (debug != null) {
            debug.exitLoop();
        }
    }

    private double[] rangeBounds(Expr.Call call) {
        List<Expr> args = call.args();
        double[] vals = new double[args.size()];
        for (int i = 0; i < args.size(); i++) {
            vals[i] = asDouble(eval(args.get(i)), call.line());
        }
        return switch (vals.length) {
            case 1 -> new double[]{0, vals[0], 1};
            case 2 -> new double[]{vals[0], vals[1], 1};
            case 3 -> new double[]{vals[0], vals[1], vals[2]};
            default -> throw new MicraLangException(call.line(), "range() takes 1 to 3 arguments");
        };
    }

    private void checkCancellation(int line) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptStoppedException();
        }
        statementsSinceApiCall++;
        if (statementsSinceApiCall > RUNAWAY_STATEMENT_THRESHOLD) {
            throw new MicraLangException(line,
                    "script ran too long without any drone action (possible infinite loop) - stopped");
        }
    }

    // ---- expressions ----

    private Object eval(Expr expr) {
        return switch (expr) {
            case Expr.NumberLit e -> e.value();
            case Expr.StringLit e -> e.value();
            case Expr.BoolLit e -> e.value();
            case Expr.NoneLit ignored -> MicraNone.INSTANCE;
            case Expr.VarRef e -> env.get(e.name(), e.line());
            case Expr.Unary e -> evalUnary(e);
            case Expr.Binary e -> evalBinary(e);
            case Expr.Call e -> evalCall(e);
        };
    }

    private Object evalUnary(Expr.Unary e) {
        if (e.op().equals("not")) {
            return !isTruthy(eval(e.operand()));
        }
        double v = asDouble(eval(e.operand()), e.line());
        return e.op().equals("-") ? -v : v;
    }

    private Object evalBinary(Expr.Binary e) {
        if (e.op().equals("and")) {
            Object left = eval(e.left());
            return isTruthy(left) ? eval(e.right()) : left;
        }
        if (e.op().equals("or")) {
            Object left = eval(e.left());
            return isTruthy(left) ? left : eval(e.right());
        }

        Object left = eval(e.left());
        Object right = eval(e.right());

        if (e.op().equals("+") && left instanceof String ls && right instanceof String rs) {
            return ls + rs;
        }
        if (e.op().equals("==")) {
            return left.equals(right);
        }
        if (e.op().equals("!=")) {
            return !left.equals(right);
        }

        double l = asDouble(left, e.line());
        double r = asDouble(right, e.line());
        return switch (e.op()) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> {
                if (r == 0) throw new MicraLangException(e.line(), "division by zero");
                yield l / r;
            }
            case "%" -> {
                if (r == 0) throw new MicraLangException(e.line(), "division by zero");
                yield l % r;
            }
            case "<" -> l < r;
            case ">" -> l > r;
            case "<=" -> l <= r;
            case ">=" -> l >= r;
            default -> throw new MicraLangException(e.line(), "unsupported operator '" + e.op() + "'");
        };
    }

    private Object evalCall(Expr.Call call) {
        List<Expr> args = call.args();
        Object result = switch (call.name()) {
            case "move" -> api.move(asString(argAt(call, 0), call.line()));
            case "till" -> {
                requireArgCount(call, 0);
                yield api.till();
            }
            case "plant" -> api.plant(asString(argAt(call, 0), call.line()));
            case "harvest" -> {
                requireArgCount(call, 0);
                yield api.harvest();
            }
            case "can_harvest" -> {
                requireArgCount(call, 0);
                yield api.canHarvest();
            }
            case "is_rotten" -> {
                requireArgCount(call, 0);
                yield api.isRotten();
            }
            case "get_pos_x" -> {
                requireArgCount(call, 0);
                yield api.getPosX();
            }
            case "get_pos_y" -> {
                requireArgCount(call, 0);
                yield api.getPosY();
            }
            case "get_world_size" -> {
                requireArgCount(call, 0);
                yield api.getWorldSize();
            }
            case "get_points" -> {
                if (args.isEmpty()) {
                    yield api.getPoints();
                } else if (args.size() == 1) {
                    yield api.getPoints(asString(argAt(call, 0), call.line()));
                } else {
                    throw new MicraLangException(call.line(),
                            "get_points() takes 0 or 1 argument(s) but got " + args.size());
                }
            }
            case "print" -> {
                requireArgCount(call, 1);
                api.print(stringify(eval(args.get(0))));
                yield MicraNone.INSTANCE;
            }
            case "range" -> throw new MicraLangException(call.line(), "range() can only be used in a for-loop");
            default -> throw new MicraLangException(call.line(), "unknown function '" + call.name() + "'");
        };
        statementsSinceApiCall = 0;
        return result;
    }

    private Object argAt(Expr.Call call, int index) {
        requireArgCount(call, index + 1);
        return eval(call.args().get(index));
    }

    private void requireArgCount(Expr.Call call, int expected) {
        if (call.args().size() != expected) {
            throw new MicraLangException(call.line(),
                    call.name() + "() takes " + expected + " argument(s) but got " + call.args().size());
        }
    }

    // ---- value helpers ----

    static boolean isTruthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0.0;
        if (v instanceof String s) return !s.isEmpty();
        return v != MicraNone.INSTANCE;
    }

    private double asDouble(Object v, int line) {
        if (v instanceof Double d) return d;
        throw new MicraLangException(line, "expected a number but got " + typeName(v));
    }

    private String asString(Object v, int line) {
        if (v instanceof String s) return s;
        throw new MicraLangException(line, "expected a string but got " + typeName(v));
    }

    private static String typeName(Object v) {
        if (v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "bool";
        return "None";
    }

    static String stringify(Object v) {
        if (v instanceof Double d) {
            return (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long) (double) d) : String.valueOf(d);
        }
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof String s) return s;
        return "None";
    }
}
