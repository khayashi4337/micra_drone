package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tokenizes Micra Drone script source into a flat token stream, synthesizing
 * INDENT/DEDENT/NEWLINE tokens the same way CPython's tokenizer does.
 */
public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("if", TokenType.IF),
            Map.entry("elif", TokenType.ELIF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("for", TokenType.FOR),
            Map.entry("in", TokenType.IN),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("True", TokenType.TRUE),
            Map.entry("False", TokenType.FALSE),
            Map.entry("None", TokenType.NONE));

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final Deque<Integer> indents = new ArrayDeque<>();

    private int pos = 0;
    private int line = 1;
    /**
     * Indentation of the first line of code, taken as the script's "column zero". CPython
     * rejects a script whose first statement is indented at all ({@code IndentationError:
     * unexpected indent}); here that is deliberately relaxed, because the way scripts reach this
     * lexer - pasted into the in-game editor from a chat window, a wiki, a terminal - routinely
     * arrives with every line shifted right by the same few spaces, and "line 1: unexpected token
     * INDENT" on a script that looks perfectly fine was a real-machine stumbling block. Only the
     * uniform shift is absorbed: relative indentation (block structure) is judged exactly as
     * before, and a later line indented LESS than the first statement is still an error, same as
     * Python's "unindent does not match any outer indentation level".
     */
    private int baseIndent = 0;
    private boolean seenCode = false;

    public Lexer(String source) {
        this.source = source;
        this.indents.push(0);
    }

    /** Every reserved word the language recognizes - the editor autocomplete popup's other candidate source. */
    public static Set<String> keywords() {
        return KEYWORDS.keySet();
    }

    public List<Token> scan() {
        while (pos < source.length()) {
            startOfLine();
        }
        while (indents.peek() > baseIndent) {
            indents.pop();
            tokens.add(new Token(TokenType.DEDENT, "", null, line));
        }
        tokens.add(new Token(TokenType.EOF, "", null, line));
        return tokens;
    }

    private void startOfLine() {
        int indent = 0;
        while (pos < source.length() && (peekChar() == ' ' || peekChar() == '\t')) {
            if (peekChar() == '\t') {
                throw new MicraLangException(line, "tabs are not allowed for indentation; use spaces");
            }
            indent++;
            pos++;
        }

        if (pos >= source.length()) {
            return;
        }
        char c = peekChar();
        if (c == '\n') {
            pos++;
            line++;
            return; // blank line: doesn't affect indent stack
        }
        if (c == '#') {
            skipToEndOfLine();
            return; // comment-only line
        }

        if (!seenCode) {
            // The first line of code defines column zero (see baseIndent) - the indent stack's
            // floor moves up to it instead of this line opening a block nobody asked for.
            seenCode = true;
            baseIndent = indent;
            indents.pop();
            indents.push(baseIndent);
        }

        if (indent > indents.peek()) {
            indents.push(indent);
            tokens.add(new Token(TokenType.INDENT, "", null, line));
        } else if (indent < indents.peek()) {
            if (indent < baseIndent) {
                // Left of the first statement: nothing on the stack can match, and popping the
                // base level itself would leave the stack empty.
                throw new MicraLangException(line, "unindent does not match any outer indentation level");
            }
            while (indents.peek() > indent) {
                indents.pop();
                tokens.add(new Token(TokenType.DEDENT, "", null, line));
            }
            if (indents.peek() != indent) {
                throw new MicraLangException(line, "unindent does not match any outer indentation level");
            }
        }

        scanLineBody();
    }

    private void scanLineBody() {
        while (pos < source.length() && peekChar() != '\n') {
            char c = peekChar();
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '#') {
                skipToEndOfLine();
                break;
            } else if (Character.isDigit(c)) {
                number();
            } else if (c == '"' || c == '\'') {
                string(c);
            } else if (Character.isLetter(c) || c == '_') {
                identifier();
            } else {
                symbol();
            }
        }
        tokens.add(new Token(TokenType.NEWLINE, "", null, line));
        if (pos < source.length() && peekChar() == '\n') {
            pos++;
            line++;
        }
    }

    private void skipToEndOfLine() {
        while (pos < source.length() && peekChar() != '\n') {
            pos++;
        }
    }

    private void number() {
        int start = pos;
        while (pos < source.length() && Character.isDigit(peekChar())) {
            pos++;
        }
        if (pos < source.length() && peekChar() == '.' && pos + 1 < source.length() && Character.isDigit(source.charAt(pos + 1))) {
            pos++;
            while (pos < source.length() && Character.isDigit(peekChar())) {
                pos++;
            }
        }
        String text = source.substring(start, pos);
        tokens.add(new Token(TokenType.NUMBER, text, Double.parseDouble(text), line));
    }

    private void string(char quote) {
        int startLine = line;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < source.length() && peekChar() != quote) {
            char c = peekChar();
            if (c == '\n') {
                throw new MicraLangException(startLine, "unterminated string literal");
            }
            if (c == '\\' && pos + 1 < source.length()) {
                pos++;
                char esc = source.charAt(pos);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case '"' -> '"';
                    case '\'' -> '\'';
                    default -> esc;
                });
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        if (pos >= source.length()) {
            throw new MicraLangException(startLine, "unterminated string literal");
        }
        pos++; // closing quote
        tokens.add(new Token(TokenType.STRING, sb.toString(), sb.toString(), startLine));
    }

    private void identifier() {
        int start = pos;
        while (pos < source.length() && (Character.isLetterOrDigit(peekChar()) || peekChar() == '_')) {
            pos++;
        }
        String text = source.substring(start, pos);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENT);
        tokens.add(new Token(type, text, null, line));
    }

    private void symbol() {
        char c = source.charAt(pos);
        switch (c) {
            case '=' -> addTwoCharOr('=', TokenType.EQUAL_EQUAL, TokenType.EQUAL);
            case '!' -> {
                if (peekNext() == '=') {
                    tokens.add(new Token(TokenType.BANG_EQUAL, "!=", null, line));
                    pos += 2;
                } else {
                    throw new MicraLangException(line, "unexpected character '!'");
                }
            }
            case '<' -> addTwoCharOr('=', TokenType.LESS_EQUAL, TokenType.LESS);
            case '>' -> addTwoCharOr('=', TokenType.GREATER_EQUAL, TokenType.GREATER);
            case '+' -> addOneChar(TokenType.PLUS);
            case '-' -> addOneChar(TokenType.MINUS);
            case '*' -> addOneChar(TokenType.STAR);
            case '/' -> addOneChar(TokenType.SLASH);
            case '%' -> addOneChar(TokenType.PERCENT);
            case '(' -> addOneChar(TokenType.LPAREN);
            case ')' -> addOneChar(TokenType.RPAREN);
            case ':' -> addOneChar(TokenType.COLON);
            case ',' -> addOneChar(TokenType.COMMA);
            case '[' -> addOneChar(TokenType.LBRACKET);
            case ']' -> addOneChar(TokenType.RBRACKET);
            case '{' -> addOneChar(TokenType.LBRACE);
            case '}' -> addOneChar(TokenType.RBRACE);
            // Only ever reached for a lone '.', since number() consumes the decimal point of "1.5"
            // itself (it requires a digit after the dot) before symbol() ever sees it.
            case '.' -> addOneChar(TokenType.DOT);
            default -> throw new MicraLangException(line, "unexpected character '" + c + "'");
        }
    }

    private void addOneChar(TokenType type) {
        tokens.add(new Token(type, String.valueOf(source.charAt(pos)), null, line));
        pos++;
    }

    private void addTwoCharOr(char second, TokenType ifMatch, TokenType otherwise) {
        char c = source.charAt(pos);
        if (peekNext() == second) {
            tokens.add(new Token(ifMatch, "" + c + second, null, line));
            pos += 2;
        } else {
            tokens.add(new Token(otherwise, String.valueOf(c), null, line));
            pos += 1;
        }
    }

    private char peekChar() {
        return source.charAt(pos);
    }

    private char peekNext() {
        return pos + 1 < source.length() ? source.charAt(pos + 1) : '\0';
    }
}
