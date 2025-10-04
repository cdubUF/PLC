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
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseLetStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseDefStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseIfStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseForStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseReturnStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parsePropertyOrMethod(Ast.Expr receiver) throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException {
        // ( expr )
        if (tokens.peek("(")) {
            return parseGroupExpr();
        }
        // literals: NIL / TRUE / FALSE / integer / decimal / char / string
        if (tokens.peek("NIL", "TRUE", "FALSE") ||
                tokens.peek(Token.Type.INTEGER) ||
                tokens.peek(Token.Type.DECIMAL) ||
                tokens.peek(Token.Type.CHARACTER) ||
                tokens.peek(Token.Type.STRING)) {
            return parseLiteralExpr();
        }
        // object literal
        if (tokens.peek("OBJECT")) {
            return parseObjectExpr();
        }
        // identifier → variable OR function call (decided by following "(")
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            return parseVariableOrFunctionExpr();
        }
        // nothing matched → not a valid primary
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
            // literal is something like "'c'" → lexer should have stripped quotes
            return new Ast.Expr.Literal(tok.literal().charAt(0));
        } else if (tokens.peek(Token.Type.STRING)) {
            var tok = tokens.get(0);
            tokens.match(Token.Type.STRING);
            return new Ast.Expr.Literal(tok.literal()); // already unescaped by lexer
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
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
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
