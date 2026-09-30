package io.github.khayashi4337.micradrone.lang;

/**
 * A safe short rendering of a script value for an error message. Concatenating the raw value is
 * not safe: {@code toString} on a list that contains itself recurses until a
 * {@link StackOverflowError}, and a string can legitimately be a megabyte of text that does not
 * belong in an issue. Numbers and booleans render the way {@code print} shows them, a string is
 * cut to a short prefix, and anything else becomes just its type name.
 */
public final class PlanValueText {
    /** The longest string fragment an error message may quote from a script value. */
    private static final int MAX_DESCRIBED_CHARS = 60;
    private static final String ELLIPSIS = "...";

    private PlanValueText() {
    }

    public static String describe(Object value) {
        if (value instanceof Double || value instanceof Boolean) {
            return Interpreter.stringify(value);
        }
        if (value instanceof String s) {
            return cut(s, MAX_DESCRIBED_CHARS);
        }
        return Interpreter.typeName(value);
    }

    /**
     * {@code text} unchanged when it fits in {@code maxChars}, else its first {@code maxChars}
     * characters plus an ellipsis. Shared with {@code PlanScriptRunner}'s issue detail so a
     * legitimately huge rendered value can never make an error message huge with it.
     */
    public static String cut(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + ELLIPSIS;
    }
}
