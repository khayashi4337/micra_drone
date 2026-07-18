package io.github.khayashi4337.micradrone.lang;

public record Token(TokenType type, String lexeme, Object literal, int line) {
    @Override
    public String toString() {
        return type + (lexeme.isEmpty() ? "" : "(" + lexeme + ")");
    }
}
