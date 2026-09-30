package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.PlanJson;
import io.github.khayashi4337.micradrone.build.model.PlanPatch;
import io.github.khayashi4337.micradrone.build.model.SemanticPlan;
import io.github.khayashi4337.micradrone.build.parts.PartTypeRegistry;
import io.github.khayashi4337.micradrone.build.plan.PatchResult;
import io.github.khayashi4337.micradrone.build.plan.PlanPatcher;
import io.github.khayashi4337.micradrone.build.plan.TemplateBundle;
import io.github.khayashi4337.micradrone.chat.MiniJson;
import java.util.List;
import java.util.Map;

/**
 * Turns a plan file's JSON into a {@link PlanSubmission} for {@link JobService} (Task 17, F-3/F-22): a tree with an
 * {@code ops} field is read as a {@link PlanPatch} applied to an empty plan, any other tree as a whole
 * {@link SemanticPlan} that {@link PlanPatcher#normalize} types against the registry. Parameters come in untyped
 * (01, section 2: the JSON reader guesses number kinds), so without normalize the same design would hash differently.
 * A bad file is reported in the record, never thrown.
 */
public final class PlanFileReader {
    private PlanFileReader() {
    }

    /**
     * One read's outcome. On success {@code submission} is set and the other two are empty; {@code issues} carries a
     * patch or normalize run that found problems; {@code error} carries a one-line parse or shape failure.
     */
    public record Result(PlanSubmission submission, List<Issue> issues, String error) {
    }

    public static Result read(String json, PartTypeRegistry registry) {
        Object tree;
        try {
            tree = MiniJson.parse(json);
        } catch (RuntimeException e) {
            // MiniJson raises IllegalArgumentException on bad tokens but IndexOutOfBounds on cut-off input
            return new Result(null, List.of(), e.getMessage());
        }
        try {
            PlanPatcher patcher = new PlanPatcher(registry, TemplateBundle.EMPTY);
            PatchResult r;
            if (tree instanceof Map<?, ?> m && m.containsKey(PlanJson.KEY_OPS)) {
                PlanPatch patch = PlanJson.patchFromTree(tree);
                r = patcher.apply(SemanticPlan.empty(patch.patchId()), patch);
            } else {
                r = patcher.normalize(PlanJson.planFromTree(tree));
            }
            if (!r.ok()) {
                return new Result(null, r.issues(), null);
            }
            return new Result(new PlanSubmission(r.plan(), TemplateBundle.EMPTY, JobKind.BUILD, null, null),
                    List.of(), null);
        } catch (RuntimeException e) {
            // PlanJsonException and IllegalArgumentException are the documented ones; a malformed tree can
            // also trip ClassCastException or IndexOutOfBounds, and a bad file must never crash the caller
            return new Result(null, List.of(), e.getMessage());
        }
    }
}
