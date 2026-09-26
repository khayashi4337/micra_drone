package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.lang.AstDepth;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.PlanLimitException;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import io.github.khayashi4337.micradrone.lang.PlanValueText;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Checks and runs construction scripts, in order, into one {@link PlanPatch}. Any problem means no patch at all. */
public final class PlanScriptRunner {
    /**
     * The longest interpreter message quoted inside an issue. A script error can legitimately
     * embed a megabyte of rendered value; the issue only keeps a short prefix.
     */
    private static final int MAX_ISSUE_DETAIL_CHARS = 400;
    /** Issue-key prefix for a script that failed while it was running. */
    private static final String RUN_ISSUE_KEY = "run:";

    /**
     * Deepest parser nesting a construction script may use (see {@link Parser#Parser(List, int)}):
     * below this the recursive-descent parse stays shallow enough that the parser can never be
     * what overflows a stack. Left-associative operator chains do not nest while parsing;
     * {@link #PLAN_MAX_AST_DEPTH} is what bounds them.
     */
    public static final int PLAN_MAX_PARSE_NESTING = 100;

    /**
     * Deepest AST a construction script's program may have. The interpreter and
     * {@link PlanScriptProfile} recurse over the tree, so this bound is what keeps them inside
     * the worker thread's stack. A {@code 1+1+...+1} chain of n terms under {@code x = ...}
     * measures n + 1 deep (the AssignStmt counts one level) and parses without ever nesting,
     * so it can only be refused by measuring the finished tree, not by the parse limit.
     */
    public static final int PLAN_MAX_AST_DEPTH = 200;

    /**
     * The fixed stack of the dedicated worker thread one whole run happens on. Sized by
     * measurement, not guesswork: the deepest program the limits allow is
     * {@code def f(n): return f(n + 1) + 1 + ... + 1} at 196 terms - the Call node and its
     * {@code n + 1} argument add 2 levels, so the body measures exactly
     * {@link #PLAN_MAX_AST_DEPTH} - and at the interpreter's own call cap of 200 every
     * Micra-level frame keeps roughly 400 Java frames of pending binary evals live. The sweep
     * and bisection in
     * {@code PlanScriptRunnerTest.theWorkerStackCoversTheWorstCaseWithAFourFoldMargin} (JDK 21,
     * Windows x64) measured the first stack size where that script ends with the interpreter's
     * "too much recursion" rather than a StackOverflowError at 17,039,360 bytes (~16.25 MiB;
     * 16 MiB still overflowed); this constant is ~7.7x that measurement, above the required 4x.
     */
    static final long PLAN_RUN_STACK_BYTES = 128L * 1024 * 1024;

    public record Result(PlanPatch patch, List<Issue> issues, List<String> printed) {
        public Result {
            issues = List.copyOf(issues);
            printed = List.copyOf(printed);
        }

        public boolean ok() {
            return patch != null;
        }
    }

    private PlanScriptRunner() {
    }

    /**
     * Runs the whole batch - every script's parse, depth check, static profile and interpret -
     * through {@link #runOnWorker}: none of it touches the caller's own stack.
     */
    public static Result run(List<String> scripts, String patchId, int baseRevision, String stageId,
            PlanRunLimits limits) {
        return runOnWorker(() -> runScripts(scripts, patchId, baseRevision, stageId, limits));
    }

    /**
     * Runs {@code body} on ONE dedicated worker thread with the fixed stack of
     * {@link #PLAN_RUN_STACK_BYTES} and hands back what it produced: a script's outcome must not
     * depend on how much stack the CALLER happened to have left, and the depth limits are only
     * meaningful if the stack they were sized against is the stack actually used. The caller
     * waits on {@code join()} in a loop so an interrupt poked at it mid-run does not lose the
     * result; the flag is restored on the way out. A {@link RuntimeException} or {@link Error}
     * thrown inside the worker is rethrown unchanged on the caller thread - the run's own
     * per-script catches turn script failures into issues before they can reach this boundary.
     * Package-private so tests can push arbitrary work across this same thread boundary.
     */
    static <T> T runOnWorker(Supplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            try {
                result.set(body.get());
            } catch (RuntimeException | Error e) {
                failure.set(e);
            }
        }, "micra-construction-script", PLAN_RUN_STACK_BYTES);
        worker.start();
        boolean interrupted = false;
        while (true) {
            try {
                worker.join();
                break;
            } catch (InterruptedException e) {
                // keep waiting - the result still matters; the flag is restored below
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable thrown = failure.get();
        if (thrown instanceof RuntimeException e) {
            throw e;
        }
        if (thrown != null) {
            throw (Error) thrown;
        }
        return result.get();
    }

    /**
     * The batch body that {@link #run} executes on its worker thread: parse, depth-check,
     * profile and run every script against one shared recorder. Package-private so tests can
     * measure the same work on stacks of a chosen size.
     */
    static Result runScripts(List<String> scripts, String patchId, int baseRevision, String stageId,
            PlanRunLimits limits) {
        List<Issue> issues = new ArrayList<>();
        PlanRecorder recorder = new PlanRecorder();
        for (int n = 0; n < scripts.size(); n++) {
            int number = n + 1;
            String label = "スクリプト" + number;
            try {
                runScript(scripts.get(n), number, label, recorder, limits, issues);
            } catch (StackOverflowError e) {
                // last-resort backstop: the parse-nesting limit, the AST-depth limit and the
                // worker stack are sized so a script's own work never gets this far - but a
                // stack overflow anywhere in one script still becomes a limit issue rather
                // than an Error killing the whole run
                issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "stack:" + number, List.of(),
                        label + "は入れ子が深すぎて処理できませんでした"));
            }
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new Result(null, issues, recorder.printed());
        }
        return new Result(recorder.toPatch(patchId, baseRevision, stageId), issues, recorder.printed());
    }

    /**
     * Checks and runs one script, appending its issues to {@code issues}. Every failure mode is a
     * recorded issue and the method returns; only a {@link StackOverflowError} propagates, to the
     * caller's catch (it is an Error, and it can come from the parse or the run alike).
     */
    private static void runScript(String source, int number, String label, PlanRecorder recorder,
            PlanRunLimits limits, List<Issue> issues) {
        if (source.length() > PlanScriptWriter.MAX_SCRIPT_CHARS) {
            issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "length:" + number, List.of(), label + "が長すぎます(" + source.length()
                    + "字 > " + PlanScriptWriter.MAX_SCRIPT_CHARS + "字)。複数のスクリプトに分けてください"));
            return;
        }
        List<Stmt> program;
        try {
            program = new Parser(new Lexer(source).scan(), PLAN_MAX_PARSE_NESTING).parseProgram();
        } catch (MicraLangException e) {
            issues.add(Issue.of(IssueCode.E_SCHEMA, "syntax:" + number, List.of(), label + "の構文エラー: " + detail(e.getMessage())));
            return;
        }
        // a left-associative chain parses without nesting, so the parse limit cannot see it;
        // measure the finished tree instead. This is iterative (see AstDepth) and runs before
        // the profile's and the interpreter's own recursion
        int astDepth = AstDepth.of(program);
        if (astDepth > PLAN_MAX_AST_DEPTH) {
            issues.add(Issue.of(IssueCode.E_SCHEMA, "syntax:" + number, List.of(),
                    label + "の構文木が深すぎます(深さ " + astDepth + " > 上限 " + PLAN_MAX_AST_DEPTH + ")"));
            return;
        }
        List<PlanScriptProfile.Violation> violations = PlanScriptProfile.check(program);
        for (int k = 0; k < violations.size(); k++) {
            PlanScriptProfile.Violation v = violations.get(k);
            issues.add(Issue.of(IssueCode.E_SCRIPT_FORBIDDEN,
                    "forbidden:" + number + ":" + v.line() + ":" + v.name() + ":" + k, List.of(),
                    label + " " + v.line() + "行目: 建設のスクリプトでは" + v.name() + "は使えません(" + describe(v.reason()) + ")",
                    Map.of("name", v.name(), "reason", v.reason().name(), "line", String.valueOf(v.line())), List.of()));
        }
        if (!violations.isEmpty()) {
            return;
        }
        try {
            new Interpreter(recorder, limits).run(program);
        } catch (PlanLimitException e) {
            issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, RUN_ISSUE_KEY + number, List.of(),
                    label + "が実行の上限を超えました: " + detail(e.getMessage())));
        } catch (MicraLangException e) {
            issues.add(Issue.of(IssueCode.E_SCHEMA, RUN_ISSUE_KEY + number, List.of(),
                    label + "の実行エラー: " + detail(e.getMessage())));
        } catch (RuntimeException e) {
            issues.add(Issue.of(IssueCode.E_SCHEMA, "internal:" + number, List.of(),
                    label + "の実行中に、想定外のエラー: " + detail(String.valueOf(e))));
        }
    }

    /** An interpreter/parser message quoted inside an issue, cut so a huge rendered value cannot fill it. */
    private static String detail(String text) {
        return PlanValueText.cut(text, MAX_ISSUE_DETAIL_CHARS);
    }

    private static String describe(PlanScriptProfile.Reason reason) {
        return switch (reason) {
            case NONDETERMINISTIC -> "実行のたびに結果が変わるため";
            case FARM_COMMAND -> "畑の命令のため。畑と建設は同じスクリプトに混ぜられません";
            case UNKNOWN -> "知らない命令です";
            case RESERVED_NAME -> "組み込みの命令と同じ名前の関数は、定義できません";
        };
    }
}
