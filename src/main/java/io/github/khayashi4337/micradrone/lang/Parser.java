package io.github.khayashi4337.micradrone.lang;

import java.util.ArrayList;
import java.util.List;

import io.github.khayashi4337.micradrone.lang.ast.Expr;
import io.github.khayashi4337.micradrone.lang.ast.Stmt;

/** Recursive-descent parser for the Micra Drone script language (MVP subset). */
public final class Parser {
    private final List<Token> tokens;
    private int pos = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public List<Stmt> parseProgram() {
        List<Stmt> stmts = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            stmts.add(statement());
        }
        return stmts;
    }

    // ---- statements ----

    private Stmt statement() {
        if (check(TokenType.IF)) return ifStmt();
        if (check(TokenType.WHILE)) return whileStmt();
        if (check(TokenType.FOR)) return forStmt();
        return simpleStmt();
    }

    /**
     * Parses the left-hand side as an ordinary expression and only then looks for {@code =}, rather
     * than peeking for {@code IDENT '='} up front: that way {@code a[0] = x} and {@code d["k"] = v}
     * reach the same place plain {@code x = 1} does, and anything else in front of an {@code =}
     * fails with a message about what is assignable instead of a confusing parse error further on.
     */
    private Stmt simpleStmt() {
        int line = peek().line();
        Expr expr = expression();
        if (check(TokenType.EQUAL)) {
            advance();
            Expr value = expression();
            expect(TokenType.NEWLINE, "end of line");
            if (expr instanceof Expr.VarRef ref) {
                return new Stmt.AssignStmt(ref.name(), value, line);
            }
            if (expr instanceof Expr.Index index) {
                return new Stmt.IndexAssignStmt(index.target(), index.index(), value, line);
            }
            throw new MicraLangException(line, "cannot assign to this - expected a name or name[index]");
        }
        expect(TokenType.NEWLINE, "end of line");
        return new Stmt.ExprStmt(expr, line);
    }

    private Stmt ifStmt() {
        int line = peek().line();
        List<Stmt.IfStmt.Branch> branches = new ArrayList<>();
        advance(); // if
        Expr cond = expression();
        branches.add(new Stmt.IfStmt.Branch(cond, block()));
        while (check(TokenType.ELIF)) {
            advance();
            Expr elifCond = expression();
            branches.add(new Stmt.IfStmt.Branch(elifCond, block()));
        }
        List<Stmt> elseBlock = null;
        if (check(TokenType.ELSE)) {
            advance();
            elseBlock = block();
        }
        return new Stmt.IfStmt(branches, elseBlock, line);
    }

    private Stmt whileStmt() {
        int line = peek().line();
        advance(); // while
        Expr cond = expression();
        return new Stmt.WhileStmt(cond, block(), line);
    }

    private Stmt forStmt() {
        int line = peek().line();
        advance(); // for
        String varName = expect(TokenType.IDENT, "loop variable name").lexeme();
        expect(TokenType.IN, "'in'");
        Expr rangeExpr = expression();
        return new Stmt.ForStmt(varName, rangeExpr, block(), line);
    }

    /** ':' NEWLINE INDENT stmt+ DEDENT */
    private List<Stmt> block() {
        expect(TokenType.COLON, "':'");
        expect(TokenType.NEWLINE, "end of line");
        expect(TokenType.INDENT, "indented block");
        List<Stmt> stmts = new ArrayList<>();
        while (!check(TokenType.DEDENT) && !check(TokenType.EOF)) {
            stmts.add(statement());
        }
        expect(TokenType.DEDENT, "end of indented block");
        return stmts;
    }

    // ---- expressions (precedence climbing) ----

    private Expr expression() {
        return orTest();
    }

    private Expr orTest() {
        Expr left = andTest();
        while (check(TokenType.OR)) {
            int line = advance().line();
            left = new Expr.Binary("or", left, andTest(), line);
        }
        return left;
    }

    private Expr andTest() {
        Expr left = notTest();
        while (check(TokenType.AND)) {
            int line = advance().line();
            left = new Expr.Binary("and", left, notTest(), line);
        }
        return left;
    }

    private Expr notTest() {
        if (check(TokenType.NOT)) {
            int line = advance().line();
            return new Expr.Unary("not", notTest(), line);
        }
        return comparison();
    }

    /**
     * {@code in} sits here alongside the comparisons, as in Python. There is deliberately no
     * {@code not in}: {@code not} binds looser than this level, so {@code not x in y} already parses
     * as {@code not (x in y)} and means the right thing.
     */
    private Expr comparison() {
        Expr left = arith();
        while (checkAny(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL, TokenType.LESS,
                TokenType.GREATER, TokenType.LESS_EQUAL, TokenType.GREATER_EQUAL, TokenType.IN)) {
            Token op = advance();
            left = new Expr.Binary(op.lexeme(), left, arith(), op.line());
        }
        return left;
    }

    private Expr arith() {
        Expr left = term();
        while (checkAny(TokenType.PLUS, TokenType.MINUS)) {
            Token op = advance();
            left = new Expr.Binary(op.lexeme(), left, term(), op.line());
        }
        return left;
    }

    private Expr term() {
        Expr left = unary();
        while (checkAny(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)) {
            Token op = advance();
            left = new Expr.Binary(op.lexeme(), left, unary(), op.line());
        }
        return left;
    }

    private Expr unary() {
        if (checkAny(TokenType.MINUS, TokenType.PLUS)) {
            Token op = advance();
            return new Expr.Unary(op.lexeme(), unary(), op.line());
        }
        return atomTrailer();
    }

    /**
     * An atom followed by any run of trailers: {@code (args)} calls, {@code [i]} indexes, and
     * {@code .name(args)} method calls, so chains like {@code grid[y].append(x)} parse. Plain calls
     * stay name-only ({@link Expr.Call} carries a name, not a callee), so {@code f()()} is still
     * rejected - a value can only be called through a method.
     */
    private Expr atomTrailer() {
        Expr expr = atom();
        while (true) {
            if (check(TokenType.LPAREN)) {
                int line = peek().line();
                if (!(expr instanceof Expr.VarRef ref)) {
                    throw new MicraLangException(line, "only a name can be called, e.g. move(...)");
                }
                advance(); // (
                expr = new Expr.Call(ref.name(), argList(), line);
            } else if (check(TokenType.LBRACKET)) {
                int line = peek().line();
                advance(); // [
                Expr index = expression();
                expect(TokenType.RBRACKET, "']'");
                expr = new Expr.Index(expr, index, line);
            } else if (check(TokenType.DOT)) {
                int line = peek().line();
                advance(); // .
                String name = expect(TokenType.IDENT, "method name").lexeme();
                expect(TokenType.LPAREN, "'(' - methods must be called, e.g. items.append(1)");
                expr = new Expr.MethodCall(expr, name, argList(), line);
            } else {
                return expr;
            }
        }
    }

    /**
     * A {@code { ... }} literal, which is a dict or a set depending on what follows the first
     * element - a {@code :} makes it a dict. Empty braces are an empty dict, matching Python;
     * an empty set has no literal form and is written {@code set()}.
     */
    private Expr braceLiteral(int line) {
        advance(); // {
        if (check(TokenType.RBRACE)) {
            advance();
            return new Expr.DictLit(new ArrayList<>(), new ArrayList<>(), line);
        }
        Expr first = expression();
        if (check(TokenType.COLON)) {
            advance();
            List<Expr> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            keys.add(first);
            values.add(expression());
            while (check(TokenType.COMMA)) {
                advance();
                keys.add(expression());
                expect(TokenType.COLON, "':'");
                values.add(expression());
            }
            expect(TokenType.RBRACE, "'}'");
            return new Expr.DictLit(keys, values, line);
        }
        List<Expr> elements = new ArrayList<>();
        elements.add(first);
        while (check(TokenType.COMMA)) {
            advance();
            elements.add(expression());
        }
        expect(TokenType.RBRACE, "'}'");
        return new Expr.SetLit(elements, line);
    }

    /** The comma-separated arguments of a call, with the opening '(' already consumed; consumes the ')'. */
    private List<Expr> argList() {
        List<Expr> args = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            args.add(expression());
            while (check(TokenType.COMMA)) {
                advance();
                args.add(expression());
            }
        }
        expect(TokenType.RPAREN, "')'");
        return args;
    }

    private Expr atom() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER -> {
                advance();
                return new Expr.NumberLit((Double) t.literal(), t.line());
            }
            case STRING -> {
                advance();
                return new Expr.StringLit((String) t.literal(), t.line());
            }
            case TRUE -> {
                advance();
                return new Expr.BoolLit(true, t.line());
            }
            case FALSE -> {
                advance();
                return new Expr.BoolLit(false, t.line());
            }
            case NONE -> {
                advance();
                return new Expr.NoneLit(t.line());
            }
            case IDENT -> {
                advance();
                return new Expr.VarRef(t.lexeme(), t.line());
            }
            case LPAREN -> {
                advance();
                Expr inner = expression();
                expect(TokenType.RPAREN, "')'");
                return inner;
            }
            case LBRACKET -> {
                advance();
                List<Expr> elements = new ArrayList<>();
                if (!check(TokenType.RBRACKET)) {
                    elements.add(expression());
                    while (check(TokenType.COMMA)) {
                        advance();
                        elements.add(expression());
                    }
                }
                expect(TokenType.RBRACKET, "']'");
                return new Expr.ListLit(elements, t.line());
            }
            case LBRACE -> {
                return braceLiteral(t.line());
            }
            default -> throw new MicraLangException(t.line(), "unexpected token " + t);
        }
    }

    // ---- token stream helpers ----

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean checkNext(TokenType type) {
        return pos + 1 < tokens.size() && tokens.get(pos + 1).type() == type;
    }

    private boolean checkAny(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) return true;
        }
        return false;
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    private Token expect(TokenType type, String what) {
        if (check(type)) return advance();
        throw new MicraLangException(peek().line(), "expected " + what + " but found " + peek());
    }
}
