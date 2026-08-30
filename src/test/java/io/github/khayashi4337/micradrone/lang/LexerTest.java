package io.github.khayashi4337.micradrone.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class LexerTest {

    private List<TokenType> types(String source) {
        return new Lexer(source).scan().stream().map(Token::type).toList();
    }

    @Test
    void indentAndDedentAreEmittedAroundNestedBlock() {
        List<TokenType> types = types("""
                if True:
                    x = 1
                y = 2
                """);
        assertEquals(List.of(
                TokenType.IF, TokenType.TRUE, TokenType.COLON, TokenType.NEWLINE,
                TokenType.INDENT,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.DEDENT,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.EOF
        ), types);
    }

    @Test
    void uniformLeadingIndentIsAbsorbedNotTreatedAsABlock() {
        // A whole script shifted right (the shape a paste from a chat window or wiki arrives in):
        // tokenizes exactly like the same script flush left - no INDENT on line 1, no stray DEDENT
        // at EOF, and the nested block inside is still an INDENT/DEDENT pair.
        List<TokenType> shifted = types("""
                  if True:
                      x = 1
                  y = 2
                """);
        List<TokenType> flush = types("""
                if True:
                    x = 1
                y = 2
                """);
        assertEquals(flush, shifted);
        assertEquals(List.of(
                TokenType.IF, TokenType.TRUE, TokenType.COLON, TokenType.NEWLINE,
                TokenType.INDENT,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.DEDENT,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.EOF
        ), shifted);
    }

    @Test
    void commentsBeforeTheFirstStatementDoNotSetTheBaseIndent() {
        // The leading comment sits at column zero while the code is shifted - the comment must not
        // pin the base at 0 and turn the first statement into a spurious block.
        List<TokenType> types = types("""
                # pasted with a comment header
                    x = 1
                    y = 2
                """);
        assertEquals(List.of(
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.EOF
        ), types);
    }

    @Test
    void dedentingBelowTheFirstStatementIsStillRejected() {
        // Only a UNIFORM shift is forgiven: a later line left of the first statement is the same
        // error it always was (and the same one Python gives).
        MicraLangException e = assertThrows(MicraLangException.class, () -> types("""
                    x = 1
                y = 2
                """));
        assertEquals(2, e.line());
    }

    @Test
    void blankLinesAndCommentsAreIgnoredForIndentation() {
        List<TokenType> types = types("""
                x = 1

                # a comment
                y = 2
                """);
        assertEquals(List.of(
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.IDENT, TokenType.EQUAL, TokenType.NUMBER, TokenType.NEWLINE,
                TokenType.EOF
        ), types);
    }

    @Test
    void bracketsAndDotAreTokenized() {
        assertEquals(List.of(
                TokenType.IDENT, TokenType.LBRACKET, TokenType.NUMBER, TokenType.RBRACKET,
                TokenType.NEWLINE, TokenType.EOF
        ), types("a[0]\n"));
        assertEquals(List.of(
                TokenType.LBRACE, TokenType.RBRACE, TokenType.NEWLINE, TokenType.EOF
        ), types("{}\n"));
        assertEquals(List.of(
                TokenType.IDENT, TokenType.DOT, TokenType.IDENT, TokenType.LPAREN, TokenType.RPAREN,
                TokenType.NEWLINE, TokenType.EOF
        ), types("a.append()\n"));
    }

    /** The lexer must keep reading "1.5" as one number rather than splitting it on the new DOT token. */
    @Test
    void decimalPointStillBelongsToItsNumber() {
        assertEquals(List.of(
                TokenType.NUMBER, TokenType.NEWLINE, TokenType.EOF
        ), types("1.5\n"));
    }

    @Test
    void tabIndentationIsRejected() {
        assertThrows(MicraLangException.class, () -> types("if True:\n\tx = 1\n"));
    }

    @Test
    void mismatchedDedentIsRejected() {
        // 4-space then 2-space: 2 doesn't match any outer level (0 or 4)
        assertThrows(MicraLangException.class, () -> types("if True:\n    if True:\n        x = 1\n  y = 2\n"));
    }
}
