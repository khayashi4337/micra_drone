package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits script source into colored regions for the in-game editor's syntax highlighting. Returns
 * contiguous {@link Span}s covering the whole input, so a renderer can walk them in order and draw
 * each run in its own color.
 *
 * <p>Deliberately separate from {@link Lexer} rather than reusing it: the lexer parses the whole
 * script at once and throws on the first syntax error (an unterminated string, a stray character),
 * which is exactly the state a half-typed script is in most of the time - highlighting has to keep
 * working while the code is still being written. Its {@link Token}s also only carry a line number,
 * not the column offsets a renderer needs. So this scanner mirrors the lexer's character rules
 * exactly (identifiers, numbers, strings and {@code #} comments all follow {@code Lexer}'s own
 * definitions) but never throws: anything malformed just ends at the line break or the end of the
 * text and the rest of the script still colors normally.
 */
public final class SyntaxHighlighter {
    /** What a run of characters is, for coloring purposes. */
    public enum Kind {
        /** Whitespace, variable names, and anything with no special meaning. */
        DEFAULT,
        /** {@code if}/{@code while}/{@code for}/... - see {@link Lexer#keywords}. */
        KEYWORD,
        /** {@code True}/{@code False}/{@code None}. */
        CONSTANT,
        NUMBER,
        STRING,
        COMMENT,
        /** A call to one of the drone's own commands - see {@link CommandNames#ALL}. */
        BUILTIN,
        /** A call to anything else (an unknown/misspelled command). */
        CALL,
        OPERATOR
    }

    /** A run of {@code [start, end)} characters that all share one {@link Kind}. */
    public record Span(int start, int end, Kind kind) {
    }

    /** The keywords that are values rather than control flow - colored apart from the rest. */
    private static final Set<String> CONSTANTS = Set.of("True", "False", "None");

    private SyntaxHighlighter() {
    }

    /** Scans {@code source} into contiguous spans covering {@code [0, source.length())}. Never throws. */
    public static List<Span> highlight(String source) {
        List<Span> spans = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            int start = i;
            Kind kind;
            if (c == '#') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
                kind = Kind.COMMENT;
            } else if (c == '"' || c == '\'') {
                i = scanString(source, i, c);
                kind = Kind.STRING;
            } else if (Character.isDigit(c)) {
                i = scanNumber(source, i);
                kind = Kind.NUMBER;
            } else if (Character.isLetter(c) || c == '_') {
                while (i < source.length() && isWordChar(source.charAt(i))) {
                    i++;
                }
                kind = classifyWord(source, source.substring(start, i), i);
            } else if (isSpace(c)) {
                while (i < source.length() && isSpace(source.charAt(i))) {
                    i++;
                }
                kind = Kind.DEFAULT;
            } else {
                i++;
                kind = Kind.OPERATOR;
            }
            append(spans, start, i, kind);
        }
        return spans;
    }

    /**
     * Consumes a string literal starting at its opening quote, mirroring {@link Lexer}'s own rules
     * (backslash escapes, no line breaks inside). An unterminated one simply ends at the line break
     * or the end of the text instead of throwing, so the half-typed {@code "} a player is in the
     * middle of doesn't break the rest of the script's colors.
     */
    private static int scanString(String source, int from, char quote) {
        int i = from + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\n') {
                break;
            }
            if (c == '\\' && i + 1 < source.length() && source.charAt(i + 1) != '\n') {
                i += 2;
                continue;
            }
            i++;
            if (c == quote) {
                break;
            }
        }
        return i;
    }

    /** Digits, then optionally one decimal point that is itself followed by a digit - matches {@code Lexer#number}. */
    private static int scanNumber(String source, int from) {
        int i = from;
        while (i < source.length() && Character.isDigit(source.charAt(i))) {
            i++;
        }
        if (i + 1 < source.length() && source.charAt(i) == '.' && Character.isDigit(source.charAt(i + 1))) {
            i++;
            while (i < source.length() && Character.isDigit(source.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    /**
     * A word is a keyword, a constant, a call (if the next non-blank character opens a paren -
     * {@link Kind#BUILTIN} when it names one of the drone's commands, {@link Kind#CALL} otherwise),
     * or else a plain variable name.
     */
    private static Kind classifyWord(String source, String word, int wordEnd) {
        if (CONSTANTS.contains(word)) {
            return Kind.CONSTANT;
        }
        if (Lexer.keywords().contains(word)) {
            return Kind.KEYWORD;
        }
        int i = wordEnd;
        while (i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) {
            i++;
        }
        if (i < source.length() && source.charAt(i) == '(') {
            return CommandNames.ALL.contains(word) ? Kind.BUILTIN : Kind.CALL;
        }
        return Kind.DEFAULT;
    }

    /** Appends {@code [start, end)}, merging into the previous span when it's the same kind, so runs stay whole. */
    private static void append(List<Span> spans, int start, int end, Kind kind) {
        if (start >= end) {
            return;
        }
        if (!spans.isEmpty()) {
            Span last = spans.get(spans.size() - 1);
            if (last.kind() == kind && last.end() == start) {
                spans.set(spans.size() - 1, new Span(last.start(), end, kind));
                return;
            }
        }
        spans.add(new Span(start, end, kind));
    }

    /** Matches {@code Lexer#identifier}'s continuation rule. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
