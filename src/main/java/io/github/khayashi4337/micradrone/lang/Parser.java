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

    private Stmt simpleStmt() {
        int line = peek().line();
        if (check(TokenType.IDENT) && checkNext(TokenType.EQUAL)) {
            String name = advance().lexeme();
            expect(TokenType.EQUAL, "'='");
            Expr value = expression();
            expect(TokenType.NEWLINE, "end of line");
            return new Stmt.AssignStmt(name, value, line);
        }
        Expr expr = expression();
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

    private Expr comparison() {
        Expr left = arith();
        while (checkAny(TokenType.EQUAL_EQUAL, TokenType.BANG_EQUAL, TokenType.LESS,
                TokenType.GREATER, TokenType.LESS_EQUAL, TokenType.GREATER_EQUAL)) {
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

    private Expr atomTrailer() {
        Expr expr = atom();
        while (check(TokenType.LPAREN)) {
            int line = peek().line();
            if (!(expr instanceof Expr.VarRef ref)) {
                throw new MicraLangException(line, "only a name can be called, e.g. move(...)");
            }
            advance(); // (
            List<Expr> args = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                args.add(expression());
                while (check(TokenType.COMMA)) {
                    advance();
                    args.add(expression());
                }
            }
            expect(TokenType.RPAREN, "')'");
            expr = new Expr.Call(ref.name(), args, line);
        }
        return expr;
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
