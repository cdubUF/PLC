package plc.project.parser;

import com.google.common.base.Preconditions;
import plc.project.lexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast parse(String rule) throws ParseException {
        var ast = switch (rule) {
            case "source" -> parseSource();
            case "stmt" -> parseStmt();
            case "expr" -> parseExpr();
            default -> throw new AssertionError(rule);
        };
        if (tokens.has(0)) {
            throw new ParseException("Expected end of input.", tokens.getNext());
        }
        return ast;
    }

    private Ast.Source parseSource() throws ParseException {
        var statements = new ArrayList<Ast.Stmt>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    private Ast.Stmt parseStmt() throws ParseException {
        if (tokens.peek("LET")) return parseLetStmt();
        if (tokens.peek("DEF")) return parseDefStmt();
        if (tokens.peek("IF"))  return parseIfStmt();
        if (tokens.peek("FOR")) return parseForStmt();
        if (tokens.peek("RETURN")) return parseReturnStmt();
        return parseExpressionOrAssignmentStmt();
    }

    private Ast.Stmt parseLetStmt() throws ParseException {
        // LET
        if (!tokens.match("LET")) {
            throw new ParseException("Expected LET.", tokens.getNext());
        }

        // identifier
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier.", tokens.getNext());
        }
        String name = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        // Optional type annotation: ":" TypeIdent  (parse & IGNORE for the AST)
        if (tokens.match(":")) {
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected type name after ':'.", tokens.getNext());
            }
            // Consume the type name but don't store it (AST doesn't carry types)
            tokens.match(Token.Type.IDENTIFIER);
        }

        // Optional initializer: "= expr"
        java.util.Optional<Ast.Expr> value = java.util.Optional.empty();
        if (tokens.match("=")) {
            value = java.util.Optional.of(parseExpr());
        }

        // ";"
        if (!tokens.match(";")) {
            throw new ParseException("Expected ';'.", tokens.getNext());
        }

        // AST.Let only takes (name, value)
        return new Ast.Stmt.Let(name, value);
    }



    private Ast.Stmt parseDefStmt() throws ParseException {
        // DEF
        if (!tokens.match("DEF")) {
            throw new ParseException("Expected DEF.", tokens.getNext());
        }

        // function name
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected function name.", tokens.getNext());
        }
        String name = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        // '('
        if (!tokens.match("(")) {
            throw new ParseException("Expected '('.", tokens.getNext());
        }

        // parameters:  name [: Type]  (, name [: Type])*
        List<String> params = new ArrayList<>();
        List<Optional<String>> paramTypes = new ArrayList<>();

        if (!tokens.peek(")")) {
            while (true) {
                if (!tokens.peek(Token.Type.IDENTIFIER)) {
                    throw new ParseException("Expected parameter name.", tokens.getNext());
                }
                String paramName = tokens.get(0).literal();
                tokens.match(Token.Type.IDENTIFIER);
                params.add(paramName);

                Optional<String> typeName = Optional.empty();
                if (tokens.match(":")) {
                    if (!tokens.peek(Token.Type.IDENTIFIER)) {
                        throw new ParseException("Expected parameter type.", tokens.getNext());
                    }
                    typeName = Optional.of(tokens.get(0).literal());
                    tokens.match(Token.Type.IDENTIFIER);
                }
                paramTypes.add(typeName);

                if (!tokens.match(",")) {
                    break;
                }
            }
        }

        // ')'
        if (!tokens.match(")")) {
            throw new ParseException("Expected ')'.", tokens.getNext());
        }

        // optional return type: ':' Type
        Optional<String> returnType = Optional.empty();
        if (tokens.match(":")) {
            if (!tokens.peek(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected return type.", tokens.getNext());
            }
            returnType = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);
        }

        // DO
        if (!tokens.match("DO")) {
            throw new ParseException("Expected DO.", tokens.getNext());
        }

        // body
        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) {
            body.add(parseStmt());
        }
        tokens.match("END");

        // full constructor: (name, parameters, parameterTypes, returnType, body)
        return new Ast.Stmt.Def(name, params, paramTypes, returnType, body);
    }



    private Ast.Stmt parseIfStmt() throws ParseException {
        if (!tokens.match("IF")) {
            throw new ParseException("Expected IF.", tokens.getNext());
        }
        Ast.Expr condition = parseExpr();

        if (!tokens.match("DO")) {
            throw new ParseException("Expected DO.", tokens.getNext());
        }

        List<Ast.Stmt> thenBody = new ArrayList<>();
        while (!tokens.peek("ELSE") && !tokens.peek("END")) {
            thenBody.add(parseStmt());
        }

        List<Ast.Stmt> elseBody = new ArrayList<>();
        if (tokens.match("ELSE")) {
            while (!tokens.peek("END")) {
                elseBody.add(parseStmt());
            }
        }

        if (!tokens.match("END")) {
            throw new ParseException("Expected END.", tokens.getNext());
        }

        return new Ast.Stmt.If(condition, thenBody, elseBody);
    }


    private Ast.Stmt parseForStmt() throws ParseException {
        if (!tokens.match("FOR")) {
            throw new ParseException("Expected FOR.", tokens.getNext());
        }
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected loop variable name.", tokens.getNext());
        }
        String name = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (!tokens.match("IN")) {
            // tests expect error pointing at the would-be expression token
            throw new ParseException("Expected IN.", tokens.getNext());
        }

        Ast.Expr iterable = parseExpr();

        if (!tokens.match("DO")) {
            throw new ParseException("Expected DO.", tokens.getNext());
        }

        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) {
            body.add(parseStmt());
        }
        tokens.match("END");

        return new Ast.Stmt.For(name, iterable, body);
    }


    private Ast.Stmt parseReturnStmt() throws ParseException {
        if (!tokens.match("RETURN")) {
            throw new ParseException("Expected RETURN.", tokens.getNext());
        }

        // Sugar: RETURN IF cond;
        if (tokens.match("IF")) {
            Ast.Expr condition = parseExpr();
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';'.", tokens.getNext());
            }
            return new Ast.Stmt.If(
                    condition,
                    List.of(new Ast.Stmt.Return(Optional.empty())),
                    List.of()
            );
        }

        // Regular: RETURN [expr] ;
        Optional<Ast.Expr> value = Optional.empty();
        if (!tokens.peek(";")) {
            value = Optional.of(parseExpr());
        }
        if (!tokens.match(";")) {
            throw new ParseException("Expected ';'.", tokens.getNext());
        }
        return new Ast.Stmt.Return(value);
    }



    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        Ast.Expr lhs = parseExpr();

        if (tokens.match("=")) {
            Ast.Expr rhs = parseExpr();
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';' after assignment.", tokens.getNext());
            }
            return new Ast.Stmt.Assignment(lhs, rhs);
        } else {
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';' after expression.", tokens.getNext());
            }
            return new Ast.Stmt.Expression(lhs);
        }
    }


    private Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        Ast.Expr left = parseComparisonExpr();
        while (true) {
            String op = null;
            if (tokens.match("AND")) {
                op = "AND";
            } else if (tokens.match("OR")) {
                op = "OR";
            } else {
                break;
            }
            Ast.Expr right = parseComparisonExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        Ast.Expr left = parseAdditiveExpr();
        while (true) {
            String op = null;
            if (tokens.match("<=")) {
                op = "<=";
            } else if (tokens.match(">=")) {
                op = ">=";
            } else if (tokens.match("==")) {
                op = "==";
            } else if (tokens.match("!=")) {
                op = "!=";
            } else if (tokens.match("<")) {
                op = "<";
            } else if (tokens.match(">")) {
                op = ">";
            } else {
                break;
            }
            Ast.Expr right = parseAdditiveExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        Ast.Expr left = parseMultiplicativeExpr();
        while (true) {
            String op = null;
            if (tokens.match("+")) {
                op = "+";
            } else if (tokens.match("-")) {
                op = "-";
            } else {
                break;
            }
            Ast.Expr right = parseMultiplicativeExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }


    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        Ast.Expr left = parseSecondaryExpr();
        while (true) {
            String op = null;
            if (tokens.match("*")) {
                op = "*";
            } else if (tokens.match("/")) {
                op = "/";
            } else {
                break;
            }
            Ast.Expr right = parseSecondaryExpr();
            left = new Ast.Expr.Binary(op, left, right);
        }
        return left;
    }


    private Ast.Expr parseSecondaryExpr() throws ParseException {
        Ast.Expr expr = parsePrimaryExpr();
        while (tokens.match(".")) {
            expr = parsePropertyOrMethod(expr);
        }
        return expr;
    }


    private Ast.Expr parsePropertyOrMethod(Ast.Expr receiver) throws ParseException {
        // we assume '.' has already been consumed by the caller
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after '.'.", tokens.getNext());
        }
        String name = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        // Method call: name '(' args? ')'
        if (tokens.match("(")) {
            List<Ast.Expr> args = new ArrayList<>();
            if (!tokens.peek(")")) {
                args.add(parseExpr());
                while (tokens.match(",")) {
                    args.add(parseExpr());
                }
            }
            if (!tokens.match(")")) {
                throw new ParseException("Expected ')'.", tokens.getNext());
            }
            return new Ast.Expr.Method(receiver, name, args);
        }

        // Property access: name
        return new Ast.Expr.Property(receiver, name);
    }


    private Ast.Expr parsePrimaryExpr() throws ParseException {
        // ( expr )
        if (tokens.peek("(")) {
            return parseGroupExpr();
        }
        // literals: NIL / TRUE / FALSE / integer / decimal / char / string
        if (tokens.peek("NIL") || tokens.peek("TRUE") || tokens.peek("FALSE")
                || tokens.peek(Token.Type.INTEGER)
                || tokens.peek(Token.Type.DECIMAL)
                || tokens.peek(Token.Type.CHARACTER)
                || tokens.peek(Token.Type.STRING)) {
            return parseLiteralExpr();
        }
        // object literal
        if (tokens.peek("OBJECT")) {
            return parseObjectExpr();
        }
        // identifier → variable OR function call
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            return parseVariableOrFunctionExpr();
        }
        throw new ParseException("Expected expression", tokens.getNext());
    }


    private Ast.Expr parseLiteralExpr() throws ParseException {
        if (tokens.match("NIL")) {
            return new Ast.Expr.Literal(null);
        } else if (tokens.match("TRUE")) {
            return new Ast.Expr.Literal(Boolean.TRUE);
        } else if (tokens.match("FALSE")) {
            return new Ast.Expr.Literal(Boolean.FALSE);
        } else if (tokens.peek(Token.Type.INTEGER)) {
            var tok = tokens.get(0);
            tokens.match(Token.Type.INTEGER);
            return new Ast.Expr.Literal(new java.math.BigInteger(tok.literal()));
        } else if (tokens.peek(Token.Type.DECIMAL)) {
            var tok = tokens.get(0);
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(new java.math.BigDecimal(tok.literal()));
        } else if (tokens.peek(Token.Type.CHARACTER)) {
            var tok = tokens.get(0);
            tokens.match(Token.Type.CHARACTER);
            return new Ast.Expr.Literal(unquoteChar(tok.literal()));
        } else if (tokens.peek(Token.Type.STRING)) {
            var tok = tokens.get(0);
            tokens.match(Token.Type.STRING);
            return new Ast.Expr.Literal(unquoteString(tok.literal()));
        }
        throw new ParseException("Expected literal", tokens.getNext());
    }


    private Ast.Expr parseGroupExpr() throws ParseException{
    // 1) require '('
    if (!tokens.match("(")) {
        throw new ParseException("Expected '('", tokens.getNext());
    }

    // 2) parse inner expression
    Ast.Expr inner = parseExpr();

    // 3) require ')'
    if (!tokens.match(")")) {
        // attach the next token so the error points at where we realized it was missing
        throw new ParseException("Expected ')'", tokens.getNext());
    }

    // 4) wrap it
    return new Ast.Expr.Group(inner);
}


    private Ast.Expr parseObjectExpr() throws ParseException {
        if (!tokens.match("OBJECT")) {
            throw new ParseException("Expected OBJECT.", tokens.getNext());
        }

        // Optional name before DO: OBJECT Name DO
        Optional<String> name = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER, "DO")) {
            name = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);
        }

        if (!tokens.match("DO")) {
            throw new ParseException("Expected DO.", tokens.getNext());
        }

        List<Ast.Stmt.Let> fields = new ArrayList<>();
        List<Ast.Stmt.Def> methods = new ArrayList<>();

        while (!tokens.peek("END")) {
            if (tokens.peek("LET")) {
                Ast.Stmt let = parseLetStmt();
                if (!(let instanceof Ast.Stmt.Let l)) {
                    throw new ParseException("Expected field declaration.", tokens.getNext());
                }
                fields.add(l);
            } else if (tokens.peek("DEF")) {
                Ast.Stmt def = parseDefStmt();
                if (!(def instanceof Ast.Stmt.Def d)) {
                    throw new ParseException("Expected method declaration.", tokens.getNext());
                }
                methods.add(d);
            } else {
                throw new ParseException("Expected LET, DEF, or END.", tokens.getNext());
            }
        }
        tokens.match("END");

        return new Ast.Expr.ObjectExpr(name, fields, methods);
    }


    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        if (!tokens.peek(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier.", tokens.getNext());
        }
        String name = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);

        if (tokens.match("(")) {
            List<Ast.Expr> args = new ArrayList<>();
            if (!tokens.peek(")")) {
                args.add(parseExpr());
                while (tokens.match(",")) {
                    args.add(parseExpr());
                }
            }
            if (!tokens.match(")")) {
                throw new ParseException("Expected ')'.", tokens.getNext());
            }
            return new Ast.Expr.Function(name, args);
        }
        return new Ast.Expr.Variable(name);
    }
    // helper functions:
    private static String unquoteString(String raw) throws ParseException {
        // Expect leading/trailing double quotes
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            // be forgiving if lexer already stripped quotes
            return unescape(raw);
        }
        return unescape(raw.substring(1, raw.length() - 1));
    }

    private static char unquoteChar(String raw) throws ParseException {
        // Expect something like:  'c'   or  '\n'
        if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
            String inner = raw.substring(1, raw.length() - 1);
            String unescaped = unescape(inner);
            if (unescaped.length() != 1) {
                throw new ParseException("Invalid character literal.", Optional.empty());
            }
            return unescaped.charAt(0);
        }
        // If lexer already provided the character as a single-char literal:
        if (raw.length() == 1) return raw.charAt(0);
        String unescaped = unescape(raw);
        if (unescaped.length() != 1) {
            throw new ParseException("Invalid character literal.", Optional.empty());
        }
        return unescaped.charAt(0);
    }

    /** Handles standard escapes: \n \t \r \" \' \\ */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!esc) {
                if (c == '\\') {
                    esc = true;
                } else {
                    out.append(c);
                }
                continue;
            }
            // we are after a backslash
            switch (c) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '"' -> out.append('"');
                case '\'' -> out.append('\'');
                case '\\' -> out.append('\\');
                default -> {
                    // Unknown escape: keep literal char (common classroom convention)
                    out.append(c);
                }
            }
            esc = false;
        }
        if (esc) { // trailing backslash
            out.append('\\');
        }
        return out.toString();
    }


    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            Preconditions.checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns the next token, if present.
         */
        public Optional<Token> getNext() {
            return index < tokens.size() ? Optional.of(tokens.get(index)) : Optional.empty();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                Preconditions.checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
