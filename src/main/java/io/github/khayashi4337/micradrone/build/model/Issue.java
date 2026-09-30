package io.github.khayashi4337.micradrone.build.model;

import java.util.List;
import java.util.Map;

/**
 * A problem found by a deterministic check. It always points at design nodes or connections by their stable
 * ids. {@code acceptable} is fixed per code: whether the user may accept the risk and go on. Null policy:
 * {@code subjects} and {@code hints} are required ({@code null} fails), while a {@code null} {@code data} map
 * reads as empty.
 */
public record Issue(String id, IssueCode code, Severity severity, boolean acceptable, List<String> subjects,
                    String message, Map<String, String> data, List<FixHint> hints) {
    private static final String ID_LABEL_SEPARATOR = ":";
    private static final String ID_SUBJECT_LIST_SEPARATOR = ",";
    private static final String ID_KEY_MARKER = "#";

    public Issue {
        if (severity != code.severity()) {
            throw new IllegalArgumentException(
                "severity mismatch for " + code.label() + ": expected " + code.severity() + ", got " + severity);
        }
        if (acceptable != code.acceptable()) {
            throw new IllegalArgumentException(
                "acceptable mismatch for " + code.label() + ": expected " + code.acceptable() + ", got " + acceptable);
        }
        subjects = List.copyOf(subjects);
        data = SortedCopies.map(data);
        hints = List.copyOf(hints);
    }

    /** {@code key} tells apart several issues of one code on one subject (e.g. the parameter name); may be empty. */
    public static Issue of(IssueCode code, String key, List<String> subjects, String message,
                           Map<String, String> data, List<FixHint> hints) {
        String id = code.label() + ID_LABEL_SEPARATOR + String.join(ID_SUBJECT_LIST_SEPARATOR, subjects)
                    + (key == null || key.isEmpty() ? "" : ID_KEY_MARKER + key);
        return new Issue(id, code, code.severity(), code.acceptable(), subjects, message, data, hints);
    }

    public static Issue of(IssueCode code, String key, List<String> subjects, String message) {
        return of(code, key, subjects, message, Map.of(), List.of());
    }

    public static Issue of(IssueCode code, List<String> subjects, String message) {
        return of(code, "", subjects, message, Map.of(), List.of());
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
