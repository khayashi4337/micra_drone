package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.khayashi4337.micradrone.lang.SyntaxHighlighter.Kind;
import io.github.khayashi4337.micradrone.lang.SyntaxHighlighter.Span;

class SyntaxHighlighterTest {

    /** The kind covering {@code index} - robust to adjacent same-kind runs having been merged. */
    private static Kind kindAt(String source, int index) {
        for (Span span : SyntaxHighlighter.highlight(source)) {
            if (index >= span.start() && index < span.end()) {
                return span.kind();
            }
        }
        throw new AssertionError("no span covers index " + index + " of '" + source + "'");
    }

    /** The kind covering the first occurrence of {@code text}. */
    private static Kind kindOf(String source, String text) {
        int index = source.indexOf(text);
        assertTrue(index >= 0, "'" + text + "' not found in '" + source + "'");
        return kindAt(source, index);
    }

    /**
     * The whole point of returning contiguous spans: a renderer walks them in order and draws every
     * character exactly once, so they must tile [0, length) with no gap and no overlap.
     */
    private static void assertCoversEverything(String source) {
        List<Span> spans = SyntaxHighlighter.highlight(source);
        int expectedStart = 0;
        for (Span span : spans) {
            assertEquals(expectedStart, span.start(), "gap or overlap before " + span + " in '" + source + "'");
            assertTrue(span.end() > span.start(), "empty span " + span);
            expectedStart = span.end();
        }
        assertEquals(source.length(), expectedStart, "spans stop short of the end of '" + source + "'");
    }

    @Test
    void spansTileTheWholeSource() {
        assertCoversEverything("""
                # plant everything
                size = get_world_size()
                while size > 0:
                    till()
                    plant("wheat")
                    size = size - 1.5
                """);
    }

    @Test
    void emptySourceHasNoSpans() {
        assertEquals(List.of(), SyntaxHighlighter.highlight(""));
    }

    @Test
    void keywordsAndConstantsAreDistinct() {
        String source = "if x == True:";
        assertEquals(Kind.KEYWORD, kindOf(source, "if"));
        assertEquals(Kind.CONSTANT, kindOf(source, "True"));
    }

    @Test
    void everyKeywordIsRecognized() {
        for (String keyword : Lexer.keywords()) {
            Kind kind = kindAt(keyword, 0);
            assertTrue(kind == Kind.KEYWORD || kind == Kind.CONSTANT,
                    "'" + keyword + "' should highlight as a keyword or constant but was " + kind);
        }
    }

    @Test
    void knownCommandsAreBuiltinsAndUnknownCallsAreNot() {
        String source = "harvest()";
        assertEquals(Kind.BUILTIN, kindOf(source, "harvest"));
        assertEquals(Kind.CALL, kindOf("hravest()", "hravest"));
    }

    @Test
    void everyCommandNameIsRecognizedAsABuiltin() {
        for (String command : CommandNames.ALL) {
            assertEquals(Kind.BUILTIN, kindAt(command + "()", 0), command + "() should highlight as a builtin");
        }
    }

    @Test
    void aWordIsOnlyACallWhenAParenFollows() {
        assertEquals(Kind.DEFAULT, kindOf("harvest = 1", "harvest"));
        // Blanks between the name and the paren still make it a call - matches how the lexer reads it.
        assertEquals(Kind.BUILTIN, kindOf("harvest ()", "harvest"));
    }

    @Test
    void variableNamesAreDefault() {
        assertEquals(Kind.DEFAULT, kindOf("total = 0", "total"));
    }

    @Test
    void numbersCoverTheirDecimalPoint() {
        String source = "x = 1.5";
        assertEquals(Kind.NUMBER, kindOf(source, "1.5"));
        assertEquals(Kind.NUMBER, kindAt(source, source.indexOf('.')));
        // A trailing dot isn't part of the number (the lexer needs a digit after it).
        assertEquals(Kind.OPERATOR, kindAt("x = 1.", 5));
    }

    @Test
    void stringsCoverTheirQuotesAndEscapes() {
        String source = "plant(\"wheat\")";
        assertEquals(Kind.STRING, kindAt(source, source.indexOf('"')));
        assertEquals(Kind.STRING, kindOf(source, "wheat"));
        assertEquals(Kind.STRING, kindAt(source, source.lastIndexOf('"')));
        assertEquals(Kind.OPERATOR, kindAt(source, source.length() - 1)); // the closing paren

        String escaped = "print(\"a\\\"b\")";
        assertEquals(Kind.STRING, kindAt(escaped, escaped.indexOf('a')));
        assertEquals(Kind.STRING, kindAt(escaped, escaped.indexOf('b')));
    }

    @Test
    void singleQuotedStringsWork() {
        assertEquals(Kind.STRING, kindOf("plant('wheat')", "'wheat'"));
    }

    /** A half-typed string must not swallow the rest of the script - the next line still colors normally. */
    @Test
    void unterminatedStringStopsAtTheLineBreak() {
        String source = "print(\"oops\nharvest()";
        assertEquals(Kind.STRING, kindAt(source, source.indexOf("oops")));
        assertEquals(Kind.BUILTIN, kindOf(source, "harvest"));
        assertCoversEverything(source);
    }

    @Test
    void unterminatedStringAtEndOfTextDoesNotThrow() {
        assertCoversEverything("print(\"oops");
    }

    @Test
    void commentsRunToTheEndOfTheirLineOnly() {
        String source = "# note\nharvest()";
        assertEquals(Kind.COMMENT, kindAt(source, 0));
        assertEquals(Kind.COMMENT, kindOf(source, "note"));
        assertEquals(Kind.BUILTIN, kindOf(source, "harvest"));
    }

    @Test
    void aHashInsideAStringIsNotAComment() {
        String source = "print(\"# not a comment\")";
        assertEquals(Kind.STRING, kindOf(source, "#"));
        assertEquals(Kind.OPERATOR, kindAt(source, source.length() - 1));
    }

    @Test
    void operatorsAreTheirOwnKind() {
        String source = "x = 1 + 2";
        assertEquals(Kind.OPERATOR, kindAt(source, source.indexOf('=')));
        assertEquals(Kind.OPERATOR, kindAt(source, source.indexOf('+')));
    }

    @Test
    void indentationAndBlankLinesAreDefault() {
        String source = "if x:\n\n    harvest()";
        assertEquals(Kind.DEFAULT, kindAt(source, source.indexOf('\n')));
        assertEquals(Kind.BUILTIN, kindOf(source, "harvest"));
        assertCoversEverything(source);
    }
}
