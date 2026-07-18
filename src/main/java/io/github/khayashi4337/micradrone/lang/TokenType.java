package io.github.khayashi4337.micradrone.lang;

public enum TokenType {
    // literals & identifiers
    NUMBER, STRING, IDENT,

    // keywords
    IF, ELIF, ELSE, WHILE, FOR, IN, AND, OR, NOT, TRUE, FALSE, NONE,

    // operators & punctuation
    EQUAL, EQUAL_EQUAL, BANG_EQUAL, LESS, GREATER, LESS_EQUAL, GREATER_EQUAL,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    LPAREN, RPAREN, COLON, COMMA,

    // structure
    NEWLINE, INDENT, DEDENT, EOF
}
