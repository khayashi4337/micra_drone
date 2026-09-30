package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Issue;
import java.util.List;
import java.util.Objects;

/** The safety envelope's verdict: the issues found, and the replacement summary the approval screen shows. */
public record SafetyReport(List<Issue> issues, ReplacementSummary replacements) {
    public SafetyReport {
        issues = List.copyOf(issues);
        Objects.requireNonNull(replacements, "replacements");
    }
}
