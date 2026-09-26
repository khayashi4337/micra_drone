package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
            Set.of("len", "abs", "min", "max", "random", "str", "list", "set", "dict", "semaphore", "create_task",
                    "attach_isr", "raise_interrupt");
    /**
     * The subset of {@link #GENERAL_PURPOSE_BUILTINS} also safe to call from inside an ISR handler
     * (see {@link #isrContext}) - deliberately NOT the same set as GENERAL_PURPOSE_BUILTINS
     * (that one only governs the runaway-loop counter, a different concern): {@code create_task}/
     * {@code attach_isr}/{@code raise_interrupt} don't touch DroneApi so they don't need to reset
     * the counter, but they must still be refused inside an ISR (spawning a task, registering
     * another handler, or firing another interrupt mid-interrupt is exactly the kind of thing a
     * real interrupt handler must never do). Every actual DroneApi-backed builtin (including
     * {@code print}, which happens to not route through the main thread today but might in the
     * future) is refused too, for the same reason - see the ISR gate in {@link #evalCall}.
     */
    private static final Set<String> ISR_SAFE_BUILTINS =
            Set.of("len", "abs", "min", "max", "random", "str", "list", "set", "dict", "semaphore");
    /** How deep the {@code stringify} walk descends into nested collections before giving up. */
    private static final int MAX_STRINGIFY_DEPTH = 8;

    /** Function call frames deep before a script is assumed to be runaway recursion, not real work. */
    private static final int MAX_CALL_DEPTH = 200;

    private final DroneApi api;
    /** Optional debugger (breakpoints/pause/step - see DebugController); null = no debugging overhead. */
    private final DebugController debug;
    /**
     * The active scope. Starts out as (and normally stays) the global frame; {@link #callFunction}
     * temporarily swaps it for a fresh child frame for the duration of a call and always restores
     * it afterwards, so one Interpreter instance is not reentrant/thread-safe - fine, since each
     * task/ISR handler (see {@link #createTask}/{@link #raiseInterrupt}) gets its own Interpreter
     * instance rather than sharing this one.
     */
    private Environment env;
    /**
     * The module-level frame. Every function call's frame is parented here, never the caller's
     * locals (no closures, see MicraFunction). For a task/ISR's forked Interpreter this is a
     * shallow copy of the creating Interpreter's globalEnv (see {@link Environment#snapshot()}) -
     * shared mutable values (lists, semaphores, ...) stay the same object, but a plain reassignment
     * in one Interpreter never affects the other.
     */
    private final Environment globalEnv;
    /** Backs {@code random()}. Unseeded on purpose - scripts that want repeatable runs shouldn't call it. */
    private final Random random = new Random();
    private long statementsSinceApiCall = 0;
    private int callDepth = 0;
    /**
     * True only for the Interpreter instance {@link #raiseInterrupt} builds to run an ISR handler.
     * Gates {@link #evalCall} against anything that isn't in {@link #ISR_SAFE_BUILTINS} - see that
     * set's javadoc for why a real interrupt handler must never call a DroneApi-backed builtin.
     */
    private final boolean isrContext;
    private final TaskRegistry taskRegistry;
    private final InterruptTable interruptTable;
    /**
     * The {@link TaskRegistry} generation captured once, when this Interpreter (or the ancestor it
     * forked from - a task/ISR interpreter always inherits its creator's value, never re-reads a
     * fresh one) was built. Passed to every {@link TaskRegistry#tryReserveAndBind} call this
     * Interpreter makes, so a create_task attempt that's already "stale" (its whole lineage traces
     * back to a script generation stopAll() has since moved past) is rejected even if it slips past
     * the interrupt itself - see TaskRegistry's class javadoc for why that's needed.
     */
    private final long generation;

    /**
     * Non-null only for a construction-script interpreter (see {@link #Interpreter(PlanApi, PlanRunLimits)}): it
     * enables {@link CommandNames#PLAN} and refuses the farm commands. Farm, task and ISR interpreters leave it null.
     */
    private final PlanApi planApi;
    /** The construction script's step/time limits; non-null exactly when {@link #planApi} is. */
    private final PlanRunLimits planLimits;
    private long planSteps = 0;
    /**
     * The sub-quantum remainder of all work charged so far in this run. Work charges
     * convert to steps through {@link #PLAN_WORK_PER_STEP}, but dropping the remainder
     * at each charge would let a loop of many sub-quantum operations (e.g. scanning a
     * list element by element) run forever without paying a single step, so the
     * leftover units carry over to the next charge instead - see {@link #chargePlanWork}.
     */
    private long planWorkCarry = 0;
    private long planStartNanos = 0;
    /** Checking the clock on every statement would cost more than the statements; every 1024th is enough. */
    private static final long PLAN_TIME_CHECK_MASK = 0x3FF;
    /**
     * How many charged work units (roughly character comparisons or node visits) equal one
     * statement step. Expensive single operations pay {@code work / PLAN_WORK_PER_STEP}
     * deterministic steps BEFORE they run (see {@link #chargePlanWork}), so the step budget -
     * not the wall clock, which a single statement can outrun between polls - decides whether
     * the operation may start. At 4,096 units per step the whole default 100,000-step budget
     * is a bound of about 4x10^8 work units - how much wall-clock time that buys depends on
     * what a unit models (a cheap character compare costs less than a hash probe), which is
     * exactly why the step counter, not the clock, is the limit that decides.
     */
    private static final long PLAN_WORK_PER_STEP = 4_096;
    /**
     * How deep {@link #planEqualsAt} may walk into nested collections before refusing.
     * Java's own {@code Object.equals} has no bound at all: on a cyclic value
     * ({@code a = []; a.append(a)}) it recurses until a {@link StackOverflowError},
     * whose depth depends on the JVM's -Xss and so is not even a deterministic limit.
     * 64 is far deeper than any plan value legitimately nests (the runner already
     * refuses recorded params past single-digit levels) while shallow enough that the
     * refusal lands long before the interpreter's own frames could exhaust the stack.
     */
    private static final int PLAN_MAX_COMPARE_DEPTH = 64;
    private static final String PLAN_REFUSED_SUFFIX =
            "' cannot be used in a construction script (only construction commands and pure helpers)";
    /**
     * The largest string one construction-script value may hold. Needed because the step/time limits alone are
     * not enough: {@code s = "x"} then {@code while True: s = s + s} doubles to about a gigabyte - an
     * OutOfMemoryError - in roughly sixty steps. Farm mode (planLimits == null) is deliberately not capped:
     * published farm scripts may legitimately build longer strings.
     */
    private static final int PLAN_MAX_STRING_CHARS = 1_000_000;
    /** The largest element count one construction-script collection may hold - same reason as {@link #PLAN_MAX_STRING_CHARS}. */
    private static final int PLAN_MAX_COLLECTION_ELEMENTS = 100_000;
    /**
     * Total units one construction-script run may allocate: one unit is about eight bytes of
     * retained heap for a list slot; a character of a produced string counts one unit although
     * it retains only one to two bytes - deliberately generous - and the heavier objects carry
     * higher weights, so the whole 10,000,000-unit budget stays around 80 MB of retained heap,
     * which fits comfortably under a small heap. A list literal's element counts {@link
     * #PLAN_LIST_LITERAL_ELEMENT_UNITS} units, a dict entry or set element counts {@link
     * #PLAN_HASH_ENTRY_UNITS} units, and a character of a split string counts {@link
     * #PLAN_CHAR_ELEMENT_UNITS} units. The per-value
     * caps ({@link #PLAN_MAX_STRING_CHARS}, {@link #PLAN_MAX_COLLECTION_ELEMENTS}) bound each
     * single value but not what a whole run retains - printing or copying a legal-sized value in
     * a loop still exhausts the heap, so the run as a whole gets a budget too. Charged where a
     * new string or collection materialises, including the list/dict/set literals: the step limit
     * does not bound them (a 3,000-element literal evaluated 100,000 times is 300 million
     * elements), only {@code append}/{@code add}/item assignment are left to it. The counter
     * belongs to one interpreter run, which is ONE script - the runner builds one interpreter per
     * script; what all scripts of a run leave behind is bounded separately by the recorder's
     * budgets.
     */
    private static final long PLAN_MAX_ALLOCATED_UNITS = 10_000_000;
    /**
     * Charge weight of one LIST LITERAL element: each element retains its ArrayList slot (about 8
     * bytes) plus, for the usual number literal, a freshly boxed Double (about 16 bytes) - about
     * 24 bytes in all, i.e. 3 units. Only the literal pays this: {@code list()}/{@code keys()}/
     * {@code values()}/the for-loop snapshot/{@code min}/{@code max} copy existing references, so
     * they stay at 1 unit per slot - except when the value being listed is a string, whose
     * "elements" are fresh one-character Strings weighing {@link #PLAN_CHAR_ELEMENT_UNITS} each.
     */
    private static final int PLAN_LIST_LITERAL_ELEMENT_UNITS = 3;
    /**
     * Charge weight of one element of a list built by splitting a string into one-character
     * strings ({@code list(s)}/{@code set(s)}/the {@code for c in s} snapshot): every element is
     * a FRESH one-character String object, about 40-48 bytes = 6 units of 8 bytes, not a copied
     * reference like the 1-unit sites.
     */
    private static final int PLAN_CHAR_ELEMENT_UNITS = 6;
    /**
     * Charge weight of one dict entry or set element: a LinkedHashMap/LinkedHashSet entry plus
     * its table slot plus two boxed values retains about 80 bytes = 10 units. {@code set(x)}
     * multiplies this by the INPUT size, not the deduplicated result size, because
     * {@code new LinkedHashSet<>(source)} sizes its hash table from the input: a set built from
     * a 65,536-character string of few distinct characters returns one element but still
     * retains a table of about 131,000 slots.
     */
    private static final int PLAN_HASH_ENTRY_UNITS = 10;
    private long planAllocatedUnits = 0;

    public Interpreter(DroneApi api) {
        this(api, null);
    }

    public Interpreter(DroneApi api, DebugController debug) {
        this(api, debug, new TaskRegistry(), new InterruptTable());
    }

    /**
     * Entry point for a top-level script that needs its {@code create_task}/{@code attach_isr}
     * bookkeeping to outlive this one run - see
     * {@link io.github.khayashi4337.micradrone.drone.DroneScriptRunner} and
     * docs/design/lang_rtos_task_foundation.md's "TaskRegistry/InterruptTableの所有者について".
     */
    public Interpreter(DroneApi api, DebugController debug, TaskRegistry taskRegistry, InterruptTable interruptTable) {
        this(api, debug, new Environment(), false, taskRegistry, interruptTable, taskRegistry.currentGeneration());
    }

    /** A construction-script interpreter: only PlanApi commands and pure helpers, under {@code limits}. */
    public Interpreter(PlanApi planApi, PlanRunLimits limits) {
        this(Objects.requireNonNull(planApi, "planApi"), Objects.requireNonNull(limits, "limits"), new TaskRegistry());
    }

    private Interpreter(PlanApi planApi, PlanRunLimits limits, TaskRegistry registry) {
        this(PlanModeDroneApi.create(planApi), null, new Environment(), false, registry, new InterruptTable(),
                registry.currentGeneration(), planApi, limits);
    }

    private Interpreter(DroneApi api, DebugController debug, Environment globalEnv, boolean isrContext,
            TaskRegistry taskRegistry, InterruptTable interruptTable, long generation) {
        this(api, debug, globalEnv, isrContext, taskRegistry, interruptTable, generation, null, null);
    }

    private Interpreter(DroneApi api, DebugController debug, Environment globalEnv, boolean isrContext,
            TaskRegistry taskRegistry, InterruptTable interruptTable, long generation, PlanApi planApi,
            PlanRunLimits planLimits) {
        this.api = api;
        this.debug = debug;
        this.globalEnv = globalEnv;
        this.env = globalEnv;
        this.isrContext = isrContext;
        this.taskRegistry = taskRegistry;
        this.interruptTable = interruptTable;
        this.generation = generation;
        this.planApi = planApi;
        this.planLimits = planLimits;
    }

    public void run(List<Stmt> program) {
        planStartNanos = System.nanoTime();
        planSteps = 0;
        planWorkCarry = 0;
        planAllocatedUnits = 0;
        execBlock(program);
    }

    /** Interrupts every task live in this Interpreter's TaskRegistry (shared across every Interpreter instance that was constructed with the same registry, e.g. via create_task's own forks). Tests that call create_task without a DroneScriptRunner should call this once done. */
    public void stopAllTasks() {
        taskRegistry.stopAll();
    }

    /**
     * Runs a task/ISR handler's body: {@link #callFunction}'s scoping rules but for a zero-arg
     * body that nobody calls (no ReturnSignal value to hand back, no call-depth bookkeeping since
     * this isn't a nested call within a running script - it IS the running script, for this
     * Interpreter instance).
     */
    private void runIsolatedBody(List<Stmt> body) {
        Environment previous = env;
        env = new Environment(globalEnv);
        try {
            execBlock(body);
        } catch (ReturnSignal ignored) {
            // an early return just ends the task/ISR handler
        } finally {
            env = previous;
        }
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
            case Stmt.FunctionDef s -> defineFunction(s);
            case Stmt.ReturnStmt s -> throw new ReturnSignal(s.value() == null ? MicraNone.INSTANCE : eval(s.value()));
            case Stmt.BreakStmt ignored -> throw BreakSignal.INSTANCE;
            case Stmt.ContinueStmt ignored -> throw ContinueSignal.INSTANCE;
            case Stmt.PassStmt ignored -> { }
        }
    }

    /** Rejects a name clash with a built-in command (the parser already rejects nested def). */
    private void defineFunction(Stmt.FunctionDef s) {
        if (isBuiltinName(s.name())) {
            throw new MicraLangException(s.line(), "'" + s.name() + "' is a built-in command and cannot be redefined");
        }
        env.set(s.name(), new MicraFunction(s.name(), s.params(), s.body()));
    }

    /** The farm commands always; the construction commands only in a construction interpreter. */
    private boolean isBuiltinName(String name) {
        return CommandNames.ALL.contains(name) || planApi != null && CommandNames.PLAN.contains(name);
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
            checkPlanHashable(index, s.line());
            chargeHashProbe(index, map.size(), s.line());
            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.put(index, value);
            checkPlanCollectionSize(mutable.size(), s.line());
            return;
        }
        throw new MicraLangException(s.line(), "cannot assign into " + typeName(target));
    }

    /** Validates a list position and returns it as an int; lists are indexed from 0, negatives are not supported. */
    private int listIndex(Object index, int size, int line) {
        double raw = asDouble(index, line);
        if (raw != Math.floor(raw)) {
            throw new MicraLangException(line, "list index must be a whole number but was " + stringifyValue(index, line));
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
            try {
                while (isTruthy(eval(s.condition()))) {
                    checkCancellation(s.line());
                    try {
                        execBlock(s.block());
                    } catch (ContinueSignal ignored) {
                        // fall through to re-check the condition
                    }
                }
            } catch (BreakSignal ignored) {
                // loop exits normally
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
            try {
                for (Object value : values) {
                    checkCancellation(s.line());
                    env.set(s.varName(), value);
                    try {
                        execBlock(s.block());
                    } catch (ContinueSignal ignored) {
                        // fall through to the next item
                    }
                }
            } catch (BreakSignal ignored) {
                // loop exits normally
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
            try {
                for (double i = start; step > 0 ? i < stop : i > stop; i += step) {
                    checkCancellation(s.line());
                    env.set(s.varName(), i);
                    try {
                        execBlock(s.block());
                    } catch (ContinueSignal ignored) {
                        // fall through to the next value of i
                    }
                }
            } catch (BreakSignal ignored) {
                // loop exits normally
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
            checkPlanCollectionSize(list.size(), line);
            List<Object> snapshot = new ArrayList<>(list);
            chargePlanAllocation(snapshot.size(), line);
            return snapshot;
        }
        if (value instanceof Set<?> set) {
            checkPlanCollectionSize(set.size(), line);
            List<Object> snapshot = new ArrayList<>(set);
            chargePlanAllocation(snapshot.size(), line);
            return snapshot;
        }
        if (value instanceof Map<?, ?> map) {
            checkPlanCollectionSize(map.size(), line);
            List<Object> snapshot = new ArrayList<>(map.keySet());
            chargePlanAllocation(snapshot.size(), line);
            return snapshot;
        }
        if (value instanceof String s) {
            checkPlanCollectionSize(s.length(), line);
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                chars.add(String.valueOf(s.charAt(i)));
            }
            // fresh one-character Strings, not copied references: the full per-character weight
            chargePlanAllocation(chars.size() * PLAN_CHAR_ELEMENT_UNITS, line);
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
        if (planLimits != null) {
            // Construction scripts have their own limits, so the farm heuristic below (statements without a drone
            // action) is skipped: it would fire first on a fast run and hide which limit was actually exceeded.
            planSteps++;
            checkPlanLimits(line, (planSteps & PLAN_TIME_CHECK_MASK) == 0);
            return;
        }
        statementsSinceApiCall++;
        if (statementsSinceApiCall > RUNAWAY_STATEMENT_THRESHOLD) {
            throw new MicraLangException(line,
                    "script ran too long without any drone action (possible infinite loop) - stopped");
        }
    }

    /**
     * The limit checks every construction-script step pays: the deterministic step counter
     * against {@code maxSteps}, and - only when {@code pollClock} says a polling point was
     * reached - the wall clock against {@code maxMillis}. {@link #checkCancellation} polls
     * the clock every 1,024th step; {@link #chargePlanWork} polls it whenever a
     * charge advanced the step counter, because a statement that did so may have
     * skipped such a boundary entirely.
     */
    private void checkPlanLimits(int line, boolean pollClock) {
        if (planSteps > planLimits.maxSteps()) {
            throw new PlanLimitException(line, "construction script exceeded " + planLimits.maxSteps() + " steps");
        }
        if (pollClock
                && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - planStartNanos) > planLimits.maxMillis()) {
            throw new PlanLimitException(line, "construction script exceeded " + planLimits.maxMillis() + " ms");
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
     * The collection methods (list/set/dict) plus {@link MicraSemaphore}'s {@code post}/{@code
     * wait}. The collection methods are deliberately a small set - the ones needed to build a
     * collection up and take it apart again - rather than all of Python's: every one of these is
     * something a script genuinely can't do otherwise, since the literals alone can only ever
     * produce a collection of a fixed size.
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
            case MicraSemaphore sem -> semaphoreMethod(sem, call, args);
            default -> throw new MicraLangException(call.line(),
                    typeName(target) + " has no methods (tried ." + call.name() + "())");
        };
    }

    /**
     * {@code .post()} (never blocks, always allowed) and {@code .wait()} (blocks until a permit is
     * available - refused inside an ISR handler, same reasoning as {@link #ISR_SAFE_BUILTINS}: a
     * real interrupt handler must never block).
     */
    private Object semaphoreMethod(MicraSemaphore sem, Expr.MethodCall call, List<Object> args) {
        return switch (call.name()) {
            case "post" -> {
                requireMethodArgCount(call, args, 0);
                sem.post();
                yield MicraNone.INSTANCE;
            }
            case "wait" -> {
                requireMethodArgCount(call, args, 0);
                if (isrContext) {
                    throw new MicraLangException(call.line(),
                            "wait() cannot be called from an interrupt handler (would block) - use post() instead");
                }
                sem.await();
                yield MicraNone.INSTANCE;
            }
            default -> throw unknownMethod(call, "semaphore", "post, wait");
        };
    }

    private Object listMethod(List<Object> list, Expr.MethodCall call, List<Object> args) {
        return switch (call.name()) {
            case "append" -> {
                requireMethodArgCount(call, args, 1);
                list.add(args.get(0));
                checkPlanCollectionSize(list.size(), call.line());
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
                boolean removed;
                if (planLimits != null) {
                    // the first index whose element is planEquals to the argument, removed by
                    // position - the same bounded walk as `x in list`, not List.remove's
                    // unbounded Object.equals
                    int index = -1;
                    for (int i = 0; i < list.size(); i++) {
                        if (planEquals(args.get(0), list.get(i), call.line())) {
                            index = i;
                            break;
                        }
                    }
                    removed = index >= 0;
                    if (removed) {
                        list.remove(index);
                    }
                } else {
                    removed = list.remove(args.get(0));
                }
                if (!removed) {
                    throw new MicraLangException(call.line(), stringifyValue(args.get(0), call.line()) + " is not in this list");
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
                checkPlanHashable(args.get(0), call.line());
                chargeHashProbe(args.get(0), set.size(), call.line());
                set.add(args.get(0));
                checkPlanCollectionSize(set.size(), call.line());
                yield MicraNone.INSTANCE;
            }
            case "remove" -> {
                requireMethodArgCount(call, args, 1);
                checkPlanHashable(args.get(0), call.line());
                chargeHashProbe(args.get(0), set.size(), call.line());
                if (!set.remove(args.get(0))) {
                    throw new MicraLangException(call.line(), stringifyValue(args.get(0), call.line()) + " is not in this set");
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
                List<Object> keys = new ArrayList<>(map.keySet());
                checkPlanCollectionSize(keys.size(), call.line());
                chargePlanAllocation(keys.size(), call.line());
                yield keys;
            }
            case "values" -> {
                requireMethodArgCount(call, args, 0);
                List<Object> values = new ArrayList<>(map.values());
                checkPlanCollectionSize(values.size(), call.line());
                chargePlanAllocation(values.size(), call.line());
                yield values;
            }
            // Unlike d[k], this answers None for a missing key instead of stopping the script.
            case "get" -> {
                requireMethodArgCount(call, args, 1);
                checkPlanHashable(args.get(0), call.line());
                chargeHashProbe(args.get(0), map.size(), call.line());
                Object value = map.get(args.get(0));
                yield value == null ? MicraNone.INSTANCE : value;
            }
            case "remove" -> {
                requireMethodArgCount(call, args, 1);
                checkPlanHashable(args.get(0), call.line());
                chargeHashProbe(args.get(0), map.size(), call.line());
                if (!map.containsKey(args.get(0))) {
                    throw new MicraLangException(call.line(), "no key " + stringifyValue(args.get(0), call.line()) + " in this dict");
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
        checkPlanCollectionSize(list.size(), e.line());
        chargePlanAllocation(list.size() * PLAN_LIST_LITERAL_ELEMENT_UNITS, e.line());
        return list;
    }

    /** Insertion-ordered, so printing a dict shows its keys in the order they were added (as in Python). */
    private Object evalDictLit(Expr.DictLit e) {
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < e.keys().size(); i++) {
            Object key = eval(e.keys().get(i));
            checkPlanHashable(key, e.line());
            chargeHashProbe(key, map.size(), e.line());
            map.put(key, eval(e.values().get(i)));
        }
        checkPlanCollectionSize(map.size(), e.line());
        chargePlanAllocation(map.size() * PLAN_HASH_ENTRY_UNITS, e.line());
        return map;
    }

    private Object evalSetLit(Expr.SetLit e) {
        Set<Object> set = new LinkedHashSet<>();
        for (Expr element : e.elements()) {
            Object value = eval(element);
            checkPlanHashable(value, e.line());
            chargeHashProbe(value, set.size(), e.line());
            set.add(value);
        }
        checkPlanCollectionSize(set.size(), e.line());
        chargePlanAllocation(set.size() * PLAN_HASH_ENTRY_UNITS, e.line());
        return set;
    }

    private Object evalIndex(Expr.Index e) {
        Object target = eval(e.target());
        Object index = eval(e.index());
        if (target instanceof List<?> list) {
            return list.get(listIndex(index, list.size(), e.line()));
        }
        if (target instanceof Map<?, ?> map) {
            checkPlanHashable(index, e.line());
            chargeHashProbe(index, map.size(), e.line());
            Object value = map.get(index);
            if (value == null && !map.containsKey(index)) {
                throw new MicraLangException(e.line(), "no key " + stringifyValue(index, e.line()) + " in this dict");
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
            String joined = ls + rs;
            checkPlanStringSize(joined, e.line());
            chargePlanAllocation(joined.length(), e.line());
            return joined;
        }
        if (e.op().equals("==")) {
            // plan mode pays the bounded walk - a shared-reference nest would otherwise outrun
            // the step budget inside a single statement; farm keeps Object.equals untouched
            return planLimits != null ? planEquals(left, right, e.line()) : left.equals(right);
        }
        if (e.op().equals("!=")) {
            return planLimits != null ? !planEquals(left, right, e.line()) : !left.equals(right);
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

    /**
     * User-defined functions are resolved before the built-in switch, not inside its {@code
     * default} case: {@link #defineFunction} already refuses a name that collides with a builtin,
     * so the two namespaces never overlap, and resolving here means a call to a user function
     * never touches the switch's post-call bookkeeping (the {@code statementsSinceApiCall} reset
     * below) at all - closing off what would otherwise be a runaway-loop-detection loophole
     * (calling a no-op user function in a tight loop would look like drone activity to that check).
     *
     * <p>A bound value that is <em>not</em> a function and whose name is <em>also not</em> a
     * builtin gets a clear "not a function" error immediately - strictly better than the old
     * "unknown function" message, and no compatibility concern, since calling it always failed
     * either way. But if the name IS a builtin (e.g. a script assigns {@code max = 0} as an
     * ordinary variable and later calls {@code max(a, b)}), this deliberately falls through to the
     * switch below unchanged: before user-defined functions existed, {@code evalCall} never
     * consulted {@code env} at all, so a same-named local variable could never shadow a builtin
     * call - scripts already shipped (this mod has published CurseForge releases) may rely on
     * that. Only an actual {@link MicraFunction} value takes precedence over a builtin's name.
     */
    private Object evalCall(Expr.Call call) {
        Object maybeFn = env.tryGet(call.name());
        if (maybeFn instanceof MicraFunction fn) {
            return callFunction(fn, call);
        }
        if (maybeFn != null && !isBuiltinName(call.name())) {
            throw new MicraLangException(call.line(),
                    "'" + call.name() + "' is not a function (it is a " + typeName(maybeFn) + ")");
        }
        // Construction interpreters only: farm commands (every ALL name except the pure helpers) are refused, and
        // construction commands go to the PlanApi. Placed after the user-function resolution for the same reason as
        // the ISR gate below: a helper function the script defined is still callable.
        if (planApi != null) {
            if (CommandNames.ALL.contains(call.name()) && !CommandNames.PLAN_HELPERS.contains(call.name())) {
                throw new MicraLangException(call.line(), "'" + call.name() + PLAN_REFUSED_SUFFIX);
            }
            if (CommandNames.PLAN.contains(call.name())) {
                List<Object> values = new ArrayList<>(call.args().size());
                for (Expr arg : call.args()) {
                    values.add(eval(arg));
                }
                return PlanCommandDispatcher.invoke(planApi, call.name(), values, call.line());
            }
        }
        // Placed after the user-function resolution above (not before it): a pure-computation
        // helper function must still be callable from inside an ISR handler, and only actually
        // hitting a forbidden builtin - whether directly or from inside such a helper's own body,
        // since isrContext is a field on this same Interpreter instance and so applies transitively
        // wherever callFunction takes this thread - should be rejected.
        if (isrContext && !ISR_SAFE_BUILTINS.contains(call.name())) {
            throw new MicraLangException(call.line(),
                    "'" + call.name() + "' cannot be called from an interrupt handler - it would block "
                            + "the caller (main-thread freeze risk if raised from there). "
                            + "Post a semaphore instead and let a task do the real work.");
        }
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
            case "sleep_ticks" -> {
                api.sleepTicks(asDouble(argAt(call, 0), call.line()));
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
            case "measure" -> {
                requireArgCount(call, 0);
                yield api.measure();
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
            case "cast_line" -> {
                requireArgCount(call, 0);
                yield api.castLine();
            }
            case "reel_in" -> {
                requireArgCount(call, 0);
                yield api.reelIn();
            }
            case "is_fishing" -> {
                requireArgCount(call, 0);
                yield api.isFishing();
            }
            case "is_bobber_bobbing" -> {
                requireArgCount(call, 0);
                yield api.isBobberBobbing();
            }
            case "did_fish_bite" -> {
                requireArgCount(call, 0);
                yield api.didFishBite();
            }
            case "is_open_water_cast" -> {
                requireArgCount(call, 0);
                yield api.isOpenWaterCast();
            }
            case "get_rod_durability" -> {
                requireArgCount(call, 0);
                yield api.getRodDurability();
            }
            case "is_anvil" -> {
                requireArgCount(call, 0);
                yield api.isAnvil();
            }
            case "get_repair_cost" -> {
                requireArgCount(call, 0);
                yield api.getRepairCost();
            }
            case "repair_rod" -> {
                requireArgCount(call, 0);
                yield api.repairRod();
            }
            case "print" -> {
                requireArgCount(call, 1);
                String rendered = stringifyValue(eval(args.get(0)), call.line());
                // charged before the api sees the text: a print that blows the budget is not recorded
                chargePlanAllocation(rendered.length(), call.line());
                if (planLimits != null) {
                    try {
                        api.print(rendered);
                    } catch (PlanBudgetException e) {
                        // the recorder's budgets are expected limits, not internal errors -
                        // rethrown with the line so the runner reports E-SCRIPT-LIMIT instead
                        // of an unexpected failure. Any other exception from the api is a
                        // recorder bug and propagates unchanged
                        throw new PlanLimitException(call.line(), e.getMessage());
                    }
                } else {
                    api.print(rendered);
                }
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
                String rendered = stringifyValue(eval(args.get(0)), call.line());
                chargePlanAllocation(rendered.length(), call.line());
                yield rendered;
            }
            case "list" -> {
                requireArgCount(call, args.isEmpty() ? 0 : 1);
                List<Object> copy = args.isEmpty() ? new ArrayList<>() : new ArrayList<>(collectionArg(call, "list"));
                chargePlanAllocation(copy.size(), call.line());
                yield copy;
            }
            case "set" -> {
                requireArgCount(call, args.isEmpty() ? 0 : 1);
                Collection<?> source = args.isEmpty() ? List.of() : collectionArg(call, "set");
                if (planLimits != null) {
                    // scanned before the set is built: collections cannot be hashed
                    // (checkPlanHashable), so set([[1]]) refuses on the argument rather
                    // than inside LinkedHashSet's hashing; each String element also
                    // pays for the insert probe against the full SOURCE size (the new
                    // table is sized from the input, so every probe walks a structure
                    // that big even when the set deduplicates most of it away)
                    for (Object element : source) {
                        checkPlanHashable(element, call.line());
                        chargeHashProbe(element, source.size(), call.line());
                    }
                }
                // charged on the INPUT size: new LinkedHashSet<>(source) sizes its hash table
                // from the input, so a set built from many duplicates still retains a table as
                // big as the input - the deduplicated result size would hide that
                Set<Object> copy = new LinkedHashSet<>(source);
                chargePlanAllocation(source.size() * PLAN_HASH_ENTRY_UNITS, call.line());
                yield copy;
            }
            case "dict" -> {
                requireArgCount(call, 0);
                yield new LinkedHashMap<>();
            }
            case "semaphore" -> {
                requireArgCount(call, 0);
                yield new MicraSemaphore(0);
            }
            case "create_task" -> {
                requireArgCount(call, 4); // validated once; read the rest with eval(...), not argAt (see below)
                String name = asString(eval(call.args().get(0)), call.line());
                double priorityRaw = asDouble(eval(call.args().get(1)), call.line());
                double budgetRaw = asDouble(eval(call.args().get(2)), call.line());
                Object fnValue = eval(call.args().get(3));
                yield createTask(name, priorityRaw, budgetRaw, fnValue);
            }
            case "attach_isr" -> {
                requireArgCount(call, 2); // validated once; read the rest with eval(...), not argAt (see above)
                String face = asString(eval(call.args().get(0)), call.line());
                Object fnValue = eval(call.args().get(1));
                if (!(fnValue instanceof MicraFunction fn) || !fn.params().isEmpty()) {
                    throw new MicraLangException(call.line(), "attach_isr()'s handler must be a function that takes no arguments");
                }
                interruptTable.attach(face, fn);
                yield MicraNone.INSTANCE;
            }
            case "raise_interrupt" -> {
                raiseInterrupt(asString(argAt(call, 0), call.line()));
                yield MicraNone.INSTANCE;
            }
            case "range" -> throw new MicraLangException(call.line(), "range() can only be used in a for-loop");
            default -> throw new MicraLangException(call.line(), "unknown function '" + call.name() + "'");
        };
        if (!GENERAL_PURPOSE_BUILTINS.contains(call.name())) {
            statementsSinceApiCall = 0;
        }
        return result;
    }

    /** Minecraft's own tick length (20 ticks/sec) - used only for the budget watchdog below, which is a wall-clock timer independent of any live game tick counter (a task may spin pure CPU with no DroneApi calls at all, so it can't rely on the paced-action tick clock the way sleep_ticks does). */
    private static final long TICK_MILLIS = 50;
    /** 1,000,000 ticks ~= 13.9 hours - an absolute cap so an absurd budget_ticks can't overflow `budgetTicks * TICK_MILLIS`. */
    private static final long MAX_BUDGET_TICKS = 1_000_000;

    /**
     * Spawns a real Java thread running {@code fn} (must take no arguments) as an RTOS-style
     * background task: its own Interpreter instance, its own fresh global frame seeded with a
     * shallow copy of this Interpreter's globals (shared mutable values like semaphores stay the
     * same object; a plain reassignment in the task never affects this Interpreter or vice versa -
     * see docs/design/lang_rtos_task_foundation.md's "共有可変状態・並行アクセスについての注記"
     * for what that does and doesn't make safe). Returns a MAVLink-style result code
     * ("ACCEPTED"/"DENIED") rather than throwing, matching how the rest of this feature reports
     * outcomes (see the design doc). {@code priority} is a best-effort {@link Thread#setPriority}
     * hint only - the JVM/OS is free to ignore it, so nothing here or in any user-facing text may
     * claim a higher-priority task is guaranteed to run first. {@code budgetTicks <= 0} means no
     * time limit; otherwise the task is interrupted (a cooperative stop signal, not a forcible
     * kill - see ScriptStoppedException) if it hasn't finished within that many ticks.
     */
    private String createTask(String name, double priorityRaw, double budgetTicksRaw, Object fnValue) {
        if (!(fnValue instanceof MicraFunction fn) || !fn.params().isEmpty()) {
            return "DENIED";
        }
        // Explicit 0 is a deliberate "no limit" sentinel; NaN/negative/infinite are input errors,
        // not silently-accepted "unlimited" - a script's arithmetic mistake shouldn't quietly hand
        // out an unbounded task (Codex review finding).
        if (!Double.isFinite(budgetTicksRaw) || budgetTicksRaw < 0) {
            return "DENIED";
        }
        // Math.ceil, not a truncating cast: 0 < budgetTicksRaw < 1 must round UP to 1 tick, not
        // truncate down to 0 - a `(long) 0.5` would otherwise silently turn "budget 0.5" into "no
        // limit at all" (budgetTicks > 0 gates the watchdog below), exactly the silent-unbounded-task
        // outcome the finite/non-negative check above exists to prevent (Fable5.1 review finding).
        long budgetTicks = (long) Math.min(MAX_BUDGET_TICKS, Math.ceil(budgetTicksRaw));
        // Clamp in double space before rounding/narrowing to int - narrowing an out-of-int-range
        // long first can wrap to an arbitrary (even negative) value (Codex review finding).
        double clampedPriorityRaw = Double.isNaN(priorityRaw) ? Thread.NORM_PRIORITY
                : Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, priorityRaw));
        int priority = (int) Math.round(clampedPriorityRaw);

        Environment forkedGlobal = new Environment();
        for (Map.Entry<String, Object> e : globalEnv.snapshot().entrySet()) {
            forkedGlobal.set(e.getKey(), e.getValue());
        }
        Interpreter taskInterpreter =
                new Interpreter(api, null, forkedGlobal, false, taskRegistry, interruptTable, generation);
        // AtomicReference (not a plain array) so the task thread's finally block is guaranteed to
        // observe the watchdog - a plain array write has no happens-before edge to a read on a
        // different thread (Codex review finding). Set *before* taskThread.start() (not after, as
        // an earlier draft had it) so there is no window where the task could finish and check
        // watchdogHolder before it's populated (Fable5.1 review finding) - a not-yet-started
        // Thread can still be interrupt()ed safely (its Thread.sleep below simply throws
        // InterruptedException immediately once started), so constructing the watchdog first and
        // starting it last is safe.
        AtomicReference<Thread> watchdogHolder = new AtomicReference<>();
        Thread taskThread = new Thread(() -> {
            try {
                taskInterpreter.runIsolatedBody(fn.body());
            } catch (ScriptStoppedException ignored) {
                // Stop, or budget exceeded - a normal way for a task to end.
            } catch (MicraLangException e) {
                api.print("task '" + name + "' error: " + e.getMessage());
            } catch (Throwable e) {
                // Mirrors DroneScriptRunner.runProgram's catch (Throwable): without this, anything
                // that isn't a ScriptStoppedException/MicraLangException (an IllegalArgumentException
                // from a bad move() direction, a ConcurrentModificationException from two tasks
                // sharing an unsynchronized list, ...) would vanish into the thread's default
                // uncaught-exception handler with nothing in the script's own log (Fable5.1 review
                // finding).
                try {
                    api.print("task '" + name + "' error: " + e);
                } finally {
                    if (e instanceof Error error) {
                        throw error; // log it, then let it surface like runProgram does
                    }
                }
            } finally {
                taskRegistry.release(name);
                Thread watchdog = watchdogHolder.get();
                if (watchdog != null) {
                    watchdog.interrupt(); // this task finished on its own; the watchdog is no longer needed
                }
            }
        }, "MicraDrone-Task-" + name);
        taskThread.setDaemon(true);
        taskThread.setPriority(priority);
        if (budgetTicks > 0) {
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(budgetTicks * TICK_MILLIS);
                    taskThread.interrupt();
                } catch (InterruptedException ignored) {
                    // the task finished first and interrupted this watchdog; nothing to do
                }
            }, "MicraDrone-Task-" + name + "-Watchdog");
            watchdog.setDaemon(true);
            watchdogHolder.set(watchdog);
        }

        // Reservation and binding happen as ONE atomic call, made only now that taskThread fully
        // exists but has NOT been started yet: an earlier design reserved the name first and bound
        // the real thread in a later, separate call, leaving a window where a concurrent stopAll()
        // would only ever see an inert placeholder and let the real thread start completely
        // unstopped right after (Codex review finding) - see TaskRegistry's class javadoc. Passing
        // `generation` (captured once, when this Interpreter was built - see that field's javadoc)
        // is what closes the narrower race where an old-generation task's own in-flight create_task
        // call reaches this point only after a stopAll()+reopen() already ran for a newer Run.
        if (!taskRegistry.tryReserveAndBind(generation, name, taskThread)) {
            return "DENIED";
        }
        try {
            taskThread.start();
        } catch (RuntimeException | Error e) {
            // Nothing is running yet - safe to just release (Fable5.1 review finding: without
            // this, a thread-creation failure like an OutOfMemoryError would leak the name/slot
            // forever, and MAX_CONCURRENT_TASKS is small enough that a few leaks meaningfully
            // starve this controller).
            taskRegistry.release(name);
            throw e;
        }
        Thread watchdog = watchdogHolder.get();
        if (watchdog != null) {
            try {
                watchdog.start();
            } catch (RuntimeException | Error e) {
                // taskThread is already running at this point - releasing here (like the block
                // above) would leave it alive but both untracked by TaskRegistry AND missing the
                // budget enforcement the caller explicitly asked for by passing budgetTicks > 0
                // (Codex review finding). Interrupting it instead asks it to stop the same way
                // Stop/a budget timeout would; its own existing finally block still runs and
                // releases the registry entry once it unwinds - nothing extra to do here.
                taskThread.interrupt();
                throw e;
            }
        }
        return "ACCEPTED";
    }

    /**
     * Software interrupt: synchronously runs {@code face}'s registered handler (if any) on the
     * calling thread - genuinely ISR-like immediacy, not deferred to some other thread. A real
     * hardware-triggered path (a redstone edge, wired up on the main thread) is future work - see
     * docs/design/lang_rtos_task_foundation.md's scope section - so this may be called from a
     * script or task thread today, never the main thread. An unregistered face does nothing
     * (matches set_output()'s "no marker, do nothing" pattern - a quiet no-op).
     *
     * <p><b>Not serialized across callers</b> (Codex review finding): re-entrant self-firing from
     * inside the handler currently running IS rejected (raise_interrupt/attach_isr/create_task are
     * all outside {@link #ISR_SAFE_BUILTINS}), but two different tasks raising the SAME face at
     * genuinely the same time each get their own isrContext=true Interpreter and can run the
     * handler concurrently - same as any other user-defined function two tasks might call at once,
     * this is only as safe as whatever the handler itself touches (see the design doc's
     * "共有可変状態・並行アクセスについての注記": protect shared state with a semaphore if that matters).
     */
    private void raiseInterrupt(String face) {
        MicraFunction handler = interruptTable.handlerFor(face);
        if (handler == null) {
            return;
        }
        Environment forkedGlobal = new Environment();
        for (Map.Entry<String, Object> e : globalEnv.snapshot().entrySet()) {
            forkedGlobal.set(e.getKey(), e.getValue());
        }
        Interpreter isrInterpreter =
                new Interpreter(api, null, forkedGlobal, true, taskRegistry, interruptTable, generation);
        isrInterpreter.runIsolatedBody(handler.body());
    }

    /**
     * Calls a user-defined function: evaluates the arguments in the caller's scope, then swaps in
     * a fresh frame (parented at {@link #globalEnv}, never the caller's locals - see {@link
     * MicraFunction}) for the body. The recursion-depth check happens before {@code callDepth} is
     * incremented and before entering the {@code try}, so a script that blows the limit never
     * leaves the counter off balance for the next call.
     */
    private Object callFunction(MicraFunction fn, Expr.Call call) {
        requireArgCount(call, fn.params().size());
        List<Object> argVals = new ArrayList<>(call.args().size());
        for (Expr arg : call.args()) {
            argVals.add(eval(arg));
        }
        if (callDepth >= MAX_CALL_DEPTH) {
            throw new MicraLangException(call.line(), "too much recursion in '" + fn.name() + "'");
        }
        callDepth++;
        Environment previous = env;
        env = new Environment(globalEnv);
        for (int i = 0; i < fn.params().size(); i++) {
            env.set(fn.params().get(i), argVals.get(i));
        }
        enterLoopForDebug(); // reuses the loop-depth hook for Step Out across a call frame too
        try {
            execBlock(fn.body());
            return MicraNone.INSTANCE;
        } catch (ReturnSignal r) {
            return r.value();
        } finally {
            env = previous;
            callDepth--;
            exitLoopForDebug();
        }
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
        checkPlanCollectionSize(candidates.size(), call.line());
        chargePlanAllocation(candidates.size(), call.line());
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
            checkPlanCollectionSize(c.size(), call.line());
            return c;
        }
        if (value instanceof Map<?, ?> m) {
            checkPlanCollectionSize(m.size(), call.line());
            return m.keySet();
        }
        if (value instanceof String s) {
            checkPlanCollectionSize(s.length(), call.line());
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                chars.add(String.valueOf(s.charAt(i)));
            }
            // every element is a fresh one-character String (PLAN_CHAR_ELEMENT_UNITS); the
            // calling site still adds its own per-element charge for the collection it builds
            // (1 unit per slot for list(), PLAN_HASH_ENTRY_UNITS for set()), so this branch
            // covers the split's weight minus the one slot-unit a plain list copy would pay
            chargePlanAllocation(chars.size() * (PLAN_CHAR_ELEMENT_UNITS - 1), call.line());
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
        if (container instanceof List<?> list) {
            if (planLimits != null) {
                // element-by-element planEquals (work-charged, depth-bounded), because
                // Collection.contains would run the same unbounded Object.equals that
                // the == case replaced
                for (Object element : list) {
                    if (planEquals(item, element, line)) {
                        return true;
                    }
                }
                return false;
            }
            return list.contains(item);
        }
        if (container instanceof Set<?> set) {
            checkPlanHashable(item, line);
            chargeHashProbe(item, set.size(), line);
            return set.contains(item);
        }
        if (container instanceof Map<?, ?> m) {
            checkPlanHashable(item, line);
            chargeHashProbe(item, m.size(), line);
            return m.containsKey(item);
        }
        if (container instanceof String s) {
            String needle = asString(item, line);
            // charged BEFORE the search runs: a single `in` over large strings could
            // otherwise keep running far past the step and time limits between two polls
            chargePlanWork(substringSearchWork(s.length(), needle.length()), line);
            return s.contains(needle);
        }
        throw new MicraLangException(line, "'in' expects a list, a set, a dict, or a string on the right, but got "
                + typeName(container));
    }

    /**
     * Converts the estimated cost of one expensive operation into deterministic steps and
     * pays them BEFORE the operation runs: a statement whose cost alone exceeds the
     * remaining step budget is refused up front instead of after it has already burned
     * the time. Only the step counter keeps the outcome the same on every machine - the
     * wall clock stays a backstop. A no-op for farm interpreters (no plan limits).
     *
     * <p>The sub-quantum remainder is never dropped: {@link #planWorkCarry} accumulates
     * every unit charged during the run and pays a step each time it crosses a whole
     * {@link #PLAN_WORK_PER_STEP}, so a million operations of 4,095 units each cost a
     * million times 4,095 units - not zero. The clock is re-polled whenever a charge
     * produced at least one whole step, because such a charge may have skipped the
     * 1,024-step polling boundary {@link #checkCancellation} relies on; a charge that
     * only grows the carry polls nothing.
     */
    private void chargePlanWork(long work, int line) {
        if (planLimits == null) {
            return;
        }
        planWorkCarry += work;
        long steps = planWorkCarry / PLAN_WORK_PER_STEP;
        planWorkCarry %= PLAN_WORK_PER_STEP;
        planSteps += steps;
        checkPlanLimits(line, steps > 0);
    }

    /**
     * ceil(log2(x)) for container sizes: 0 for x <= 1, otherwise the number of
     * bits in x - 1 (so powers of two map to their own exponent and everything
     * else rounds up: ceilLog2(2) = 1, ceilLog2(3) = 2, ceilLog2(1,025) = 11).
     */
    private static int ceilLog2(int x) {
        return x <= 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(x - 1);
    }

    /**
     * Charges the modelled cost of one hash-table probe or insert of {@code key}
     * into a container of {@code containerSize} entries. Only a String key does
     * real work: the probe's {@code equals}/{@code compareTo} scans the key's
     * characters at least once, and keys that share a hashCode pile into one
     * bucket that Java treeifies, so a lookup costs about log2(containerSize)
     * comparisons - (key.length() + 1) x (1 + ceilLog2(containerSize + 1)) units
     * covers the scan plus that depth. Every other key type (numbers, booleans,
     * None, functions, semaphores) is a constant-time probe and pays nothing -
     * and collections are refused outright by {@link #checkPlanHashable}. The
     * units land on the run-wide step counter through {@link #chargePlanWork},
     * so this is a no-op for farm interpreters.
     */
    private void chargeHashProbe(Object key, int containerSize, int line) {
        if (key instanceof String s) {
            chargePlanWork((s.length() + 1) * (long) (1 + ceilLog2(containerSize + 1)), line);
        }
    }

    /**
     * Worst-case cost of {@code needle in text} for two strings: the needle is tried at
     * each of {@code textLength - needleLength + 1} positions and each try may compare all
     * {@code needleLength} characters (Java's substring search is naive). Zero when the
     * needle is empty or longer than the text - {@code contains} answers immediately in
     * both cases. Computed in {@code long} so the product cannot overflow.
     */
    private static long substringSearchWork(int textLength, int needleLength) {
        if (needleLength == 0 || needleLength > textLength) {
            return 0;
        }
        return (long) (textLength - needleLength + 1) * needleLength;
    }

    /**
     * Refuses the plan-mode use of a collection where Java would have to HASH it: as a
     * set element, a dict key, or the probe of a membership test/lookup on a set or
     * dict. Python has the same rule (lists, sets and dicts are unhashable); here it is
     * also what keeps {@link #planEqualsAt} from recursing over a shared nest inside
     * hashCode the way equals used to - and the legal keys left (numbers, booleans,
     * None, functions, semaphores, and strings) either probe in constant time or pay
     * their scan through {@link #chargeHashProbe} at every call site. A no-op for
     * farm interpreters, whose Java collections happily hash other collections.
     */
    private void checkPlanHashable(Object v, int line) {
        if (planLimits != null && (v instanceof List<?> || v instanceof Set<?> || v instanceof Map<?, ?>)) {
            throw new MicraLangException(line,
                    "a " + typeName(v) + " cannot be used as a dict key or set element");
        }
    }

    /**
     * Plan-mode structural equality, used instead of {@code Object.equals} for
     * {@code ==}/{@code !=}, {@code x in list} and {@code list.remove(x)}: it gives
     * exactly the answers {@code Object.equals} gives for every value type the language
     * has, but bounded two ways so a single comparison cannot outrun the limits.
     * Java's own {@code equals} recurses over a shared-reference nest without
     * memoising (a 30-level {@code [a, a]} nest costs about 2^31 node visits inside ONE
     * statement), and on a cyclic value it recurses until a {@link StackOverflowError}
     * - an Error whose depth depends on -Xss, i.e. not even deterministic. This walk
     * refuses nesting deeper than {@link #PLAN_MAX_COMPARE_DEPTH} deterministically,
     * and pays its node visits as deterministic steps through {@link #chargePlanWork}
     * while it proceeds, so a comparison bigger than the remaining step budget is
     * refused as soon as the budget is exceeded.
     */
    private boolean planEquals(Object a, Object b, int line) {
        PlanCompareBudget work = new PlanCompareBudget();
        return planEqualsAt(a, b, 0, work, line);
    }

    /**
     * The work-charge adapter one {@link #planEquals} walk pays through. The visits
     * are passed to {@link #chargePlanWork} one at a time, whose run-wide {@link
     * #planWorkCarry} pays a step for every whole {@link #PLAN_WORK_PER_STEP} quantum
     * they accumulate - so a refused comparison has no unpaid work left at all, and
     * sub-quantum visits still count toward the next operation's charge.
     */
    private final class PlanCompareBudget {
        void spend(long units, int line) {
            chargePlanWork(units, line);
        }
    }

    /**
     * One node of the {@link #planEquals} walk. Every call counts one node visit, and
     * {@code depth} is how many collections enclose the pair being compared: the
     * top-level pair is 0, the elements of a collection compared at level d are d + 1.
     * A pair deeper than {@link #PLAN_MAX_COMPARE_DEPTH} is refused, so a value nested
     * exactly 64 levels still compares while 65 does not - and a cyclic value, which
     * descends without end, is refused deterministically instead of overflowing.
     */
    private boolean planEqualsAt(Object a, Object b, int depth, PlanCompareBudget work, int line) {
        work.spend(1, line);
        if (depth > PLAN_MAX_COMPARE_DEPTH) {
            throw compareDepthExceeded(line);
        }
        if (a instanceof Double || b instanceof Double) {
            // Double.equals exactly: NaN equals NaN, and 0.0 differs from -0.0; a number
            // compared across types answers false just like Object.equals does
            return a.equals(b);
        }
        if (a instanceof String sa && b instanceof String sb) {
            if (sa.length() == sb.length()) {
                // String.equals scans the characters of two equal-length strings before it
                // can answer - real work the step counter cannot otherwise see
                work.spend(sa.length(), line);
            }
            return sa.equals(sb);
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!planEqualsAt(la.get(i), lb.get(i), depth + 1, work, line)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Set<?> sa && b instanceof Set<?> sb) {
            if (sa.size() != sb.size()) {
                return false;
            }
            // set elements are scalars (checkPlanHashable), but a String element's
            // containsAll probe still scans its characters and walks the bucket
            // tree - charged per element before the equals runs
            for (Object element : sa) {
                chargeHashProbe(element, sb.size(), line);
            }
            work.spend(sa.size(), line);
            return sa.equals(sb);
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            // keys are scalars too, but a String key's lookup pays the same probe
            // scan; the VALUES can nest and go through the same bounded walk
            for (Object key : ma.keySet()) {
                chargeHashProbe(key, mb.size(), line);
            }
            work.spend(ma.size(), line);
            for (Map.Entry<?, ?> entry : ma.entrySet()) {
                Object other = mb.get(entry.getKey());
                if (other == null && !mb.containsKey(entry.getKey())) {
                    return false;
                }
                if (!planEqualsAt(entry.getValue(), other, depth + 1, work, line)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> || a instanceof Set<?> || a instanceof Map<?, ?>
                || b instanceof List<?> || b instanceof Set<?> || b instanceof Map<?, ?>) {
            // a collection of one kind never equals one of another kind, or a scalar
            return false;
        }
        // Booleans, None, functions, semaphores (and a String against a non-String):
        // whatever Object.equals says - all scalar comparisons with nothing to bound
        return a.equals(b);
    }

    private static PlanLimitException compareDepthExceeded(int line) {
        return new PlanLimitException(line, "construction script exceeded the compare depth limit of "
                + PLAN_MAX_COMPARE_DEPTH);
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

    static String typeName(Object v) {
        if (v instanceof Double) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "bool";
        if (v instanceof List) return "list";
        if (v instanceof Set) return "set";
        if (v instanceof Map) return "dict";
        if (v instanceof MicraFunction) return "function";
        if (v instanceof MicraSemaphore) return "semaphore";
        return "None";
    }

    static String stringify(Object v) {
        StringBuilder out = new StringBuilder();
        appendStringified(v, 0, out, null, 0);
        return out.toString();
    }

    /**
     * {@link #stringify(Object)} under the construction string cap: the same rendering, but a plan-mode
     * script stops with {@link PlanLimitException} the moment the output would pass
     * {@link #PLAN_MAX_STRING_CHARS} instead of materialising a heap-filling string first (a list of
     * references to one big string is cheap to build but expensive to print). Farm mode delegates to
     * {@link #stringify(Object)} unchanged.
     */
    private String stringifyValue(Object v, int line) {
        if (planLimits == null) {
            return stringify(v);
        }
        StringBuilder out = new StringBuilder();
        appendStringified(v, 0, out, new StringBudget(), line);
        return out.toString();
    }

    /**
     * Remaining characters a construction script may still append to a string being built. Shared down
     * the stringify recursion so the cap is enforced while building, not after: checking the finished
     * result would still mean constructing (and maybe overflowing the heap on) the whole string first.
     */
    private static final class StringBudget {
        private long remaining = PLAN_MAX_STRING_CHARS;

        void spend(int chars, int line) {
            remaining -= chars;
            if (remaining < 0) {
                throw stringSizeExceeded(line);
            }
        }
    }

    /** Rejects a string that grew past the construction cap; a no-op for farm interpreters. */
    private void checkPlanStringSize(String s, int line) {
        if (planLimits != null && s.length() > PLAN_MAX_STRING_CHARS) {
            throw stringSizeExceeded(line);
        }
    }

    /**
     * Rejects a collection that grew (or was built) past the construction cap; a no-op for farm
     * interpreters. Called at every place plan-mode code can create or enlarge a collection:
     * {@code append}/{@code add}/dict insertion, the literals, {@code list(...)}/{@code set(...)}
     * copies, and the for-loop snapshot - a 1,000,000-character string is legal, but splitting it
     * into characters must not become a 1,000,000-element list.
     */
    private void checkPlanCollectionSize(int size, int line) {
        if (planLimits != null && size > PLAN_MAX_COLLECTION_ELEMENTS) {
            throw collectionSizeExceeded(line);
        }
    }

    /**
     * Counts script-built characters/elements against the run-wide allocation budget; a no-op for
     * farm interpreters. Called where plan-mode code materialises a new string or collection:
     * {@code +} concatenation, {@code str()}/{@code print()}, {@code list()}/{@code set()} copies,
     * {@code keys()}/{@code values()}, the list/dict/set literals, the for-loop snapshot and the
     * {@code min}/{@code max} candidate list. List literal elements weigh {@link
     * #PLAN_LIST_LITERAL_ELEMENT_UNITS} units each; dict entries and set elements weigh
     * {@link #PLAN_HASH_ENTRY_UNITS} units each (a {@code set(x)} copy counts its input, whose
     * size determines the hash table); the one-character strings a string split produces weigh
     * {@link #PLAN_CHAR_ELEMENT_UNITS} units each; every other list-producing site copies
     * existing references and stays at one unit per slot.
     */
    private void chargePlanAllocation(int units, int line) {
        if (planLimits == null) {
            return;
        }
        planAllocatedUnits += units;
        if (planAllocatedUnits > PLAN_MAX_ALLOCATED_UNITS) {
            throw new PlanLimitException(line, "construction script exceeded the total allocation limit of "
                    + PLAN_MAX_ALLOCATED_UNITS + " (characters and collection elements created)");
        }
    }

    private static PlanLimitException stringSizeExceeded(int line) {
        return new PlanLimitException(line, "construction script exceeded the string size limit of "
                + PLAN_MAX_STRING_CHARS + " characters");
    }

    private static PlanLimitException collectionSizeExceeded(int line) {
        return new PlanLimitException(line, "construction script exceeded the collection size limit of "
                + PLAN_MAX_COLLECTION_ELEMENTS + " elements");
    }

    /**
     * Collections print their contents, so {@code print(items)} is actually useful. {@code depth}
     * caps the recursion: a script can build a collection that contains itself
     * ({@code a = []; a.append(a)}), and running off the stack would raise a StackOverflowError -
     * an Error, not an Exception, so it would slip past the runner's {@code catch (RuntimeException)}
     * and kill the script thread with no message at all. Beyond the cap the nested value is shown
     * as an ellipsis instead, the way Python renders the same cycle.
     */
    private static void appendStringified(Object v, int depth, StringBuilder out, StringBudget budget, int line) {
        if (v instanceof Double d) {
            appendPiece(out, (d == Math.floor(d) && !Double.isInfinite(d)) ? String.valueOf((long) (double) d) : String.valueOf(d),
                    budget, line);
            return;
        }
        if (v instanceof Boolean b) {
            appendPiece(out, b ? "True" : "False", budget, line);
            return;
        }
        if (v instanceof String s) {
            appendPiece(out, s, budget, line);
            return;
        }
        if (v instanceof List<?> || v instanceof Set<?> || v instanceof Map<?, ?>) {
            if (depth >= MAX_STRINGIFY_DEPTH) {
                appendPiece(out, "...", budget, line);
                return;
            }
            if (v instanceof Map<?, ?> map) {
                appendPiece(out, "{", budget, line);
                String separator = "";
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    appendPiece(out, separator, budget, line);
                    separator = ", ";
                    appendQuoted(entry.getKey(), depth + 1, out, budget, line);
                    appendPiece(out, ": ", budget, line);
                    appendQuoted(entry.getValue(), depth + 1, out, budget, line);
                }
                appendPiece(out, "}", budget, line);
                return;
            }
            Collection<?> items = (Collection<?>) v;
            appendPiece(out, v instanceof Set<?> ? "{" : "[", budget, line);
            String separator = "";
            for (Object item : items) {
                appendPiece(out, separator, budget, line);
                separator = ", ";
                appendQuoted(item, depth + 1, out, budget, line);
            }
            appendPiece(out, v instanceof Set<?> ? "}" : "]", budget, line);
            return;
        }
        if (v instanceof MicraFunction fn) {
            appendPiece(out, "<function " + fn.name() + ">", budget, line);
            return;
        }
        if (v instanceof MicraSemaphore) {
            appendPiece(out, "<semaphore>", budget, line);
            return;
        }
        appendPiece(out, "None", budget, line);
    }

    /**
     * How a value looks *inside* a collection: strings get quotes there (so an empty string and a
     * missing one are told apart) even though a bare {@code print("hi")} prints them without.
     */
    private static void appendQuoted(Object v, int depth, StringBuilder out, StringBudget budget, int line) {
        if (v instanceof String s) {
            appendPiece(out, "\"", budget, line);
            appendPiece(out, s, budget, line);
            appendPiece(out, "\"", budget, line);
            return;
        }
        appendStringified(v, depth, out, budget, line);
    }

    private static void appendPiece(StringBuilder out, String piece, StringBudget budget, int line) {
        if (budget != null) {
            budget.spend(piece.length(), line);
        }
        out.append(piece);
    }
}
