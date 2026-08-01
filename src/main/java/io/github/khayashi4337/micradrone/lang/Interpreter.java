package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

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
    /**
     * The {@code evalCall} case names under its "general-purpose builtins (no drone involved)"
     * marker - these must NOT reset {@link #statementsSinceApiCall}. None of them touch DroneApi,
     * and several ({@code str}/{@code list}/{@code set}) allocate, so a loop that only calls these
     * (e.g. {@code while True: x = list([1])}) needs to keep counting toward the runaway threshold
     * the same as pure arithmetic - otherwise it never trips and can exhaust the heap.
     */
    private static final Set<String> GENERAL_PURPOSE_BUILTINS =
            Set.of("len", "abs", "min", "max", "random", "str", "list", "set", "dict");
    /** How deep {@link #stringify(Object, int)} descends into nested collections before giving up. */
    private static final int MAX_STRINGIFY_DEPTH = 8;

    private final DroneApi api;
    /** Optional debugger (breakpoints/pause/step - see DebugController); null = no debugging overhead. */
    private final DebugController debug;
    private final Environment env = new Environment();
    /** Backs {@code random()}. Unseeded on purpose - scripts that want repeatable runs shouldn't call it. */
    private final Random random = new Random();
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
            case Stmt.IndexAssignStmt s -> execIndexAssign(s);
            case Stmt.ExprStmt s -> eval(s.expr());
            case Stmt.IfStmt s -> execIf(s);
            case Stmt.WhileStmt s -> execWhile(s);
            case Stmt.ForStmt s -> execFor(s);
        }
    }

    /** {@code a[i] = v} on a list (existing position only) or {@code d[k] = v} on a dict (adds or replaces). */
    private void execIndexAssign(Stmt.IndexAssignStmt s) {
        Object target = eval(s.target());
        Object index = eval(s.index());
        Object value = eval(s.value());
        if (target instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> mutable = (List<Object>) list;
            mutable.set(listIndex(index, mutable.size(), s.line()), value);
            return;
        }
        if (target instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.put(index, value);
            return;
        }
        throw new MicraLangException(s.line(), "cannot assign into " + typeName(target));
    }

    /** Validates a list position and returns it as an int; lists are indexed from 0, negatives are not supported. */
    private int listIndex(Object index, int size, int line) {
        double raw = asDouble(index, line);
        if (raw != Math.floor(raw)) {
            throw new MicraLangException(line, "list index must be a whole number but was " + stringify(index));
        }
        int i = (int) raw;
        if (i < 0 || i >= size) {
            throw new MicraLangException(line, "list index " + i + " is out of range (list has " + size + " items)");
        }
        return i;
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

    /**
     * {@code range(...)} stays a syntactic special case - it is not a value in this language, so it
     * is recognised here rather than evaluated - while anything else is evaluated and walked as a
     * collection (see {@link #iterableOf}).
     */
    private void execFor(Stmt.ForStmt s) {
        if (s.rangeExpr() instanceof Expr.Call call && call.name().equals("range")) {
            execForRange(s, call);
            return;
        }
        Iterable<Object> values = iterableOf(eval(s.rangeExpr()), s.line());
        enterLoopForDebug();
        try {
            for (Object value : values) {
                checkCancellation(s.line());
                env.set(s.varName(), value);
                execBlock(s.block());
            }
        } finally {
            exitLoopForDebug();
        }
    }

    private void execForRange(Stmt.ForStmt s, Expr.Call call) {
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

    /**
     * What {@code for x in ...} walks: a list's items, a set's members, a dict's keys (as in
     * Python), or a string's characters. Snapshots lists and sets so that a body which appends to
     * the very collection it is walking can't throw ConcurrentModificationException out of the
     * script - it simply iterates what was there when the loop started.
     */
    private Iterable<Object> iterableOf(Object value, int line) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (value instanceof Set<?> set) {
            return new ArrayList<>(set);
        }
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.keySet());
        }
        if (value instanceof String s) {
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                chars.add(String.valueOf(s.charAt(i)));
            }
            return chars;
        }
        throw new MicraLangException(line, "cannot loop over " + typeName(value)
                + " - expected range(...), a list, a set, a dict, or a string");
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
            case Expr.ListLit e -> evalListLit(e);
            case Expr.DictLit e -> evalDictLit(e);
            case Expr.SetLit e -> evalSetLit(e);
            case Expr.Index e -> evalIndex(e);
            case Expr.MethodCall e -> evalMethodCall(e);
        };
    }

    /**
     * The collection methods. Deliberately a small set - the ones needed to build a collection up
     * and take it apart again - rather than all of Python's: every one of these is something a
     * script genuinely can't do otherwise, since the literals alone can only ever produce a
     * collection of a fixed size.
     *
     * <p>Unlike {@link #evalCall}, this does NOT reset the runaway-loop counter: {@code append}/
     * {@code add} grow the JVM heap with no DroneApi pacing to slow them down, so a loop like
     * {@code while True: items.append(1)} would otherwise never trip the watchdog and OOM the
     * server. Method calls fall under the same statement budget as pure arithmetic instead.
     */
    private Object evalMethodCall(Expr.MethodCall call) {
        Object target = eval(call.target());
        List<Object> args = new ArrayList<>(call.args().size());
        for (Expr arg : call.args()) {
            args.add(eval(arg));
        }
        return switch (target) {
            case List<?> list -> listMethod(uncheckedList(list), call, args);
            case Set<?> set -> setMethod(uncheckedSet(set), call, args);
            case Map<?, ?> map -> dictMethod(uncheckedMap(map), call, args);
            default -> throw new MicraLangException(call.line(),
                    typeName(target) + " has no methods (tried ." + call.name() + "())");
        };
    }

    private Object listMethod(List<Object> list, Expr.MethodCall call, List<Object> args) {
        return switch (call.name()) {
            case "append" -> {
                requireMethodArgCount(call, args, 1);
                list.add(args.get(0));
                yield MicraNone.INSTANCE;
            }
            case "pop" -> {
                requireMethodArgCount(call, args, 0);
                if (list.isEmpty()) {
                    throw new MicraLangException(call.line(), "pop() on an empty list");
                }
                yield list.remove(list.size() - 1);
            }
            case "remove" -> {
                requireMethodArgCount(call, args, 1);
                if (!list.remove(args.get(0))) {
                    throw new MicraLangException(call.line(), stringify(args.get(0)) + " is not in this list");
                }
                yield MicraNone.INSTANCE;
            }
            case "clear" -> {
                requireMethodArgCount(call, args, 0);
                list.clear();
                yield MicraNone.INSTANCE;
            }
            default -> throw unknownMethod(call, "list", "append, pop, remove, clear");
        };
    }

    private Object setMethod(Set<Object> set, Expr.MethodCall call, List<Object> args) {
        return switch (call.name()) {
            case "add" -> {
                requireMethodArgCount(call, args, 1);
                set.add(args.get(0));
                yield MicraNone.INSTANCE;
            }
            case "remove" -> {
                requireMethodArgCount(call, args, 1);
                if (!set.remove(args.get(0))) {
                    throw new MicraLangException(call.line(), stringify(args.get(0)) + " is not in this set");
                }
                yield MicraNone.INSTANCE;
            }
            case "clear" -> {
                requireMethodArgCount(call, args, 0);
                set.clear();
                yield MicraNone.INSTANCE;
            }
            default -> throw unknownMethod(call, "set", "add, remove, clear");
        };
    }

    private Object dictMethod(Map<Object, Object> map, Expr.MethodCall call, List<Object> args) {
        return switch (call.name()) {
            case "keys" -> {
                requireMethodArgCount(call, args, 0);
                yield new ArrayList<>(map.keySet());
            }
            case "values" -> {
                requireMethodArgCount(call, args, 0);
                yield new ArrayList<>(map.values());
            }
            // Unlike d[k], this answers None for a missing key instead of stopping the script.
            case "get" -> {
                requireMethodArgCount(call, args, 1);
                Object value = map.get(args.get(0));
                yield value == null ? MicraNone.INSTANCE : value;
            }
            case "remove" -> {
                requireMethodArgCount(call, args, 1);
                if (!map.containsKey(args.get(0))) {
                    throw new MicraLangException(call.line(), "no key " + stringify(args.get(0)) + " in this dict");
                }
                yield map.remove(args.get(0));
            }
            case "clear" -> {
                requireMethodArgCount(call, args, 0);
                map.clear();
                yield MicraNone.INSTANCE;
            }
            default -> throw unknownMethod(call, "dict", "keys, values, get, remove, clear");
        };
    }

    private MicraLangException unknownMethod(Expr.MethodCall call, String type, String available) {
        return new MicraLangException(call.line(),
                type + " has no method '" + call.name() + "' - available: " + available);
    }

    private void requireMethodArgCount(Expr.MethodCall call, List<Object> args, int expected) {
        if (args.size() != expected) {
            throw new MicraLangException(call.line(),
                    "." + call.name() + "() takes " + expected + " argument(s) but got " + args.size());
        }
    }

    // The runtime only ever creates these collections with Object elements (see evalListLit and
    // friends), so widening back to Object is safe even though the wildcard capture can't prove it.
    @SuppressWarnings("unchecked")
    private static List<Object> uncheckedList(List<?> list) {
        return (List<Object>) list;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> uncheckedSet(Set<?> set) {
        return (Set<Object>) set;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> uncheckedMap(Map<?, ?> map) {
        return (Map<Object, Object>) map;
    }

    private Object evalListLit(Expr.ListLit e) {
        List<Object> list = new ArrayList<>(e.elements().size());
        for (Expr element : e.elements()) {
            list.add(eval(element));
        }
        return list;
    }

    /** Insertion-ordered, so printing a dict shows its keys in the order they were added (as in Python). */
    private Object evalDictLit(Expr.DictLit e) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < e.keys().size(); i++) {
            map.put(eval(e.keys().get(i)), eval(e.values().get(i)));
        }
        return map;
    }

    private Object evalSetLit(Expr.SetLit e) {
        Set<Object> set = new LinkedHashSet<>();
        for (Expr element : e.elements()) {
            set.add(eval(element));
        }
        return set;
    }

    private Object evalIndex(Expr.Index e) {
        Object target = eval(e.target());
        Object index = eval(e.index());
        if (target instanceof List<?> list) {
            return list.get(listIndex(index, list.size(), e.line()));
        }
        if (target instanceof Map<?, ?> map) {
            Object value = map.get(index);
            if (value == null && !map.containsKey(index)) {
                throw new MicraLangException(e.line(), "no key " + stringify(index) + " in this dict");
            }
            return value;
        }
        if (target instanceof String s) {
            return String.valueOf(s.charAt(listIndex(index, s.length(), e.line())));
        }
        throw new MicraLangException(e.line(), "cannot index " + typeName(target));
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
        if (e.op().equals("in")) {
            return contains(right, left, e.line());
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
            case "do_a_flip" -> {
                requireArgCount(call, 0);
                api.doAFlip();
                yield MicraNone.INSTANCE;
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
            case "set_output" -> {
                api.setOutput(asBoolean(argAt(call, 0), call.line()));
                yield MicraNone.INSTANCE;
            }
            case "get_output" -> {
                requireArgCount(call, 0);
                yield api.getOutput();
            }
            case "pair_with" -> {
                api.pairWith(asString(argAt(call, 0), call.line()));
                yield MicraNone.INSTANCE;
            }
            case "is_paired" -> {
                requireArgCount(call, 0);
                yield api.isPaired();
            }
            // Perception (GitHub issue #10): read-only looks at the world around the drone.
            case "get_ground" -> {
                requireArgCount(call, 0);
                yield api.getGround();
            }
            case "get_block_above" -> {
                requireArgCount(call, 0);
                yield api.getBlockAbove();
            }
            case "get_time" -> {
                requireArgCount(call, 0);
                yield api.getTime();
            }
            case "get_weather" -> {
                requireArgCount(call, 0);
                yield api.getWeather();
            }
            case "get_biome" -> {
                requireArgCount(call, 0);
                yield api.getBiome();
            }
            case "get_light" -> {
                requireArgCount(call, 0);
                yield api.getLight();
            }
            case "get_plot_id" -> {
                requireArgCount(call, 0);
                yield api.getPlotId();
            }
            case "print" -> {
                requireArgCount(call, 1);
                api.print(stringify(eval(args.get(0))));
                yield MicraNone.INSTANCE;
            }
            // ---- general-purpose builtins (no drone involved) ----
            case "len" -> {
                requireArgCount(call, 1);
                yield (double) lengthOf(argAt(call, 0), call.line());
            }
            case "abs" -> {
                requireArgCount(call, 1);
                yield Math.abs(asDouble(argAt(call, 0), call.line()));
            }
            case "min" -> extreme(call, true);
            case "max" -> extreme(call, false);
            case "random" -> {
                requireArgCount(call, 0);
                yield random.nextDouble();
            }
            case "str" -> {
                requireArgCount(call, 1);
                yield stringify(eval(args.get(0)));
            }
            case "list" -> {
                requireArgCount(call, args.isEmpty() ? 0 : 1);
                yield args.isEmpty() ? new ArrayList<>() : new ArrayList<>(collectionArg(call, "list"));
            }
            case "set" -> {
                requireArgCount(call, args.isEmpty() ? 0 : 1);
                yield args.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(collectionArg(call, "set"));
            }
            case "dict" -> {
                requireArgCount(call, 0);
                yield new LinkedHashMap<>();
            }
            case "range" -> throw new MicraLangException(call.line(), "range() can only be used in a for-loop");
            default -> throw new MicraLangException(call.line(), "unknown function '" + call.name() + "'");
        };
        if (!GENERAL_PURPOSE_BUILTINS.contains(call.name())) {
            statementsSinceApiCall = 0;
        }
        return result;
    }

    /** {@code len(x)} - items in a collection, or characters in a string. */
    private int lengthOf(Object v, int line) {
        if (v instanceof Collection<?> c) return c.size();
        if (v instanceof Map<?, ?> m) return m.size();
        if (v instanceof String s) return s.length();
        throw new MicraLangException(line, "len() expects a list, a set, a dict, or a string but got " + typeName(v));
    }

    /**
     * {@code min}/{@code max}, in both of Python's shapes: several arguments
     * ({@code max(1, 2, 3)}) or one collection to scan ({@code max(items)}).
     */
    private Object extreme(Expr.Call call, boolean wantSmallest) {
        List<Object> candidates = new ArrayList<>();
        if (call.args().size() == 1) {
            Object only = eval(call.args().get(0));
            if (only instanceof Collection<?> c) {
                candidates.addAll(c);
            } else {
                candidates.add(only);
            }
        } else {
            if (call.args().isEmpty()) {
                throw new MicraLangException(call.line(), call.name() + "() needs at least one argument");
            }
            for (Expr arg : call.args()) {
                candidates.add(eval(arg));
            }
        }
        if (candidates.isEmpty()) {
            throw new MicraLangException(call.line(), call.name() + "() got an empty collection");
        }
        Object best = candidates.get(0);
        double bestValue = asDouble(best, call.line());
        for (Object candidate : candidates) {
            double value = asDouble(candidate, call.line());
            if (wantSmallest ? value < bestValue : value > bestValue) {
                best = candidate;
                bestValue = value;
            }
        }
        return best;
    }

    /** The single collection argument of {@code list(...)}/{@code set(...)}; a dict contributes its keys. */
    private Collection<?> collectionArg(Expr.Call call, String name) {
        Object value = eval(call.args().get(0));
        if (value instanceof Collection<?> c) {
            return c;
        }
        if (value instanceof Map<?, ?> m) {
            return m.keySet();
        }
        if (value instanceof String s) {
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                chars.add(String.valueOf(s.charAt(i)));
            }
            return chars;
        }
        throw new MicraLangException(call.line(),
                name + "() expects a list, a set, a dict, or a string but got " + typeName(value));
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
        if (v instanceof Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return v != MicraNone.INSTANCE;
    }

    /** {@code x in y}: a list's items, a set's members, a dict's keys, or a substring of a string. */
    private boolean contains(Object container, Object item, int line) {
        if (container instanceof Collection<?> c) {
            return c.contains(item);
        }
        if (container instanceof Map<?, ?> m) {
            return m.containsKey(item);
        }
        if (container instanceof String s) {
            return s.contains(asString(item, line));
        }
        throw new MicraLangException(line, "'in' expects a list, a set, a dict, or a string on the right, but got "
                + typeName(container));
    }

    private double asDouble(Object v, int line) {
        if (v instanceof Double d) return d;
        throw new MicraLangException(line, "expected a number but got " + typeName(v));
    }

    private String asString(Object v, int line) {
        if (v instanceof String s) return s;
        throw new MicraLangException(line, "expected a string but got " + typeName(v));
    }

    private boolean asBoolean(Object v, int line) {
        if (v instanceof Boolean b) return b;
        throw new MicraLangException(line, "expected a bool but got " + typeName(v));
    }

    private static String typeName(Object v) {
        if (v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "bool";
        if (v instanceof List) return "list";
        if (v instanceof Set) return "set";
        if (v instanceof Map) return "dict";
        return "None";
    }

    static String stringify(Object v) {
        return stringify(v, 0);
    }

    /**
     * Collections print their contents, so {@code print(items)} is actually useful. {@code depth}
     * caps the recursion: a script can build a collection that contains itself
     * ({@code a = []; a.append(a)}), and running off the stack would raise a StackOverflowError -
     * an Error, not an Exception, so it would slip past the runner's {@code catch (RuntimeException)}
     * and kill the script thread with no message at all. Beyond the cap the nested value is shown
     * as an ellipsis instead, the way Python renders the same cycle.
     */
    private static String stringify(Object v, int depth) {
        if (v instanceof Double d) {
            return (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long) (double) d) : String.valueOf(d);
        }
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof String s) return s;
        if (v instanceof List<?> || v instanceof Set<?> || v instanceof Map<?, ?>) {
            if (depth >= MAX_STRINGIFY_DEPTH) {
                return "...";
            }
            if (v instanceof Map<?, ?> map) {
                return map.entrySet().stream()
                        .map(entry -> quoted(entry.getKey(), depth + 1) + ": " + quoted(entry.getValue(), depth + 1))
                        .collect(Collectors.joining(", ", "{", "}"));
            }
            Collection<?> items = (Collection<?>) v;
            String open = v instanceof Set<?> ? "{" : "[";
            String close = v instanceof Set<?> ? "}" : "]";
            return items.stream().map(item -> quoted(item, depth + 1)).collect(Collectors.joining(", ", open, close));
        }
        return "None";
    }

    /**
     * How a value looks *inside* a collection: strings get quotes there (so an empty string and a
     * missing one are told apart) even though a bare {@code print("hi")} prints them without.
     */
    private static String quoted(Object v, int depth) {
        return v instanceof String s ? "\"" + s + "\"" : stringify(v, depth);
    }
}
