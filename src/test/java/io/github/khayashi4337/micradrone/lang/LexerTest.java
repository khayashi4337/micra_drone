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
    void tabIndentationIsRejected() {
        assertThrows(MicraLangException.class, () -> types("if True:\n\tx = 1\n"));
    }

    @Test
    void mismatchedDedentIsRejected() {
        // 4-space then 2-space: 2 doesn't match any outer level (0 or 4)
        assertThrows(MicraLangException.class, () -> types("if True:\n    if True:\n        x = 1\n  y = 2\n"));
    }
}
