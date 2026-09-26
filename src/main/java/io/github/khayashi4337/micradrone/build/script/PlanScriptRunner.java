package io.github.khayashi4337.micradrone.build.script;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.lang.Interpreter;
import io.github.khayashi4337.micradrone.lang.Lexer;
import io.github.khayashi4337.micradrone.lang.MicraLangException;
import io.github.khayashi4337.micradrone.lang.Parser;
import io.github.khayashi4337.micradrone.lang.PlanLimitException;
import io.github.khayashi4337.micradrone.lang.PlanRunLimits;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Checks and runs construction scripts, in order, into one {@link PlanPatch}. Any problem means no patch at all. */
public final class PlanScriptRunner {
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

    public static Result run(List<String> scripts, String patchId, int baseRevision, String stageId, PlanRunLimits limits) {
        List<Issue> issues = new ArrayList<>();
        PlanRecorder recorder = new PlanRecorder();
        for (int n = 0; n < scripts.size(); n++) {
            String label = "スクリプト" + (n + 1);
            String source = scripts.get(n);
            if (source.length() > PlanScriptWriter.MAX_SCRIPT_CHARS) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "length:" + n, List.of(), label + "が長すぎます(" + source.length()
                        + "字 > " + PlanScriptWriter.MAX_SCRIPT_CHARS + "字)。複数のスクリプトに分けてください"));
                continue;
            }
            List<Stmt> program;
            try {
                program = new Parser(new Lexer(source).scan()).parseProgram();
            } catch (MicraLangException e) {
                issues.add(Issue.of(IssueCode.E_SCHEMA, "syntax:" + n, List.of(), label + "の構文エラー: " + e.getMessage()));
                continue;
            }
            List<PlanScriptProfile.Violation> violations = PlanScriptProfile.check(program);
            for (PlanScriptProfile.Violation v : violations) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_FORBIDDEN, v.name() + ":" + v.line(), List.of(),
                        label + " " + v.line() + "行目: 建設のスクリプトでは" + v.name() + "は使えません(" + describe(v.reason()) + ")",
                        Map.of("name", v.name(), "reason", v.reason().name(), "line", String.valueOf(v.line())), List.of()));
            }
            if (!violations.isEmpty()) {
                continue;
            }
            try {
                new Interpreter(recorder, limits).run(program);
            } catch (PlanLimitException e) {
                issues.add(Issue.of(IssueCode.E_SCRIPT_LIMIT, "run:" + n, List.of(), label + "が実行の上限を超えました: " + e.getMessage()));
            } catch (MicraLangException e) {
                issues.add(Issue.of(IssueCode.E_SCHEMA, "run:" + n, List.of(), label + "の実行エラー: " + e.getMessage()));
            } catch (RuntimeException e) {
                issues.add(Issue.of(IssueCode.E_SCHEMA, "internal:" + n, List.of(), label + "の実行中に、想定外のエラー: " + e));
            }
        }
        if (issues.stream().anyMatch(Issue::isError)) {
            return new Result(null, issues, recorder.printed());
        }
        return new Result(recorder.toPatch(patchId, baseRevision, stageId), issues, recorder.printed());
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
