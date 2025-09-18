package plc.project.lexer;

import com.google.common.base.Preconditions;

import javax.naming.ldap.PagedResultsControl;
import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through a combination of {@link #lex()}, which repeatedly
 * calls {@link #lexToken()} and skips over whitespace/comments, and
 * {@link #lexToken()}, which determines the type of the next token and
 * delegates to the corresponding lex method.
 *
 * <p>Additionally, {@link CharStream} manages the lexer state and contains
 * {@link CharStream#peek} and {@link CharStream#match}. These are helpful
 * utilities for working with character state and building tokens.
 */
public final class Lexer {

    private final CharStream chars;

    public Lexer(String input) {
        chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        var tokens = new ArrayList<Token>();
        // tokens ::= (skipped* token)* skipped*
        while (chars.has(0)) {

            // consuming whitespace
            if (chars.peek("[\\s]")) {
                lexWhitespace();
                continue;
            }
            // comment
            if (chars.peek("/", "/")) {
                lexComment();
                continue;
            }
        }
        return tokens;
    }

    private void lexWhitespace() {
        while(chars.match("[\\s]")) { }
        chars.emit(); // discard
    }

    private void lexComment() {
        Preconditions.checkState(chars.match("/", "/"));
        while (chars.has(0) && !chars.peek("[\\n]", "[\\r]")) {
            chars.match("."); // eat until newline
        }
        chars.emit(); // discard comment
    }

    private Token lexToken() {
        // token :: = identifier | number | character | string

        // identifier
        if (chars.peek("[A-Za-z]")) { // .peek() looks at the next character and doesnt advance the iterable
            return lexIdentifier();
        }
        // number
        if (chars.peek("[0-9]")) {
            return lexNumber();
        }
        // character & escape
        if (chars.peek("'")) {
            return lexCharacter();
        }
        // string
        if (chars.peek("\"")) {
            return lexString();
        }
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Token lexIdentifier() {
        Preconditions.checkState(chars.match("[A-Za-z_]"));
        while(chars.match("[A-Za-z0-9_-]")) {}
        return new Token(Token.Type.IDENTIFIER, chars.emit());
        // identifier ::= [A-Za-z] [A-Za-z0-9_-]*
    }

    private Token lexNumber() {
        // number ::= [+-]? [0-9]+ ('.' [0-9]+)? ('e' [+-]? [0-9]+)?

        // check for leading + or - sign
        if (chars.peek("[+\\-]", "[0-9]")) {
            chars.match("[+\\-]");
        }
        // counting digits
        while (chars.match("[0-9]")) {}

        boolean hasDot = false;
        boolean hasExp = false;

        // decimal
        if (chars.peek("\\.", "[0-9]")) {
            chars.match("\\.");
            hasDot = true;
            while (chars.match("[0-9]")) {}
        }
        // exponent
        if (chars.peek("[eE]", "[+\\-]", "[0-9]") || chars.peek("[eE]", "[0-9]")) {
            hasExp = true;
            chars.match("[eE]");
            chars.match("[+\\-]");        // optional sign
            while (chars.match("[0-9]")) { /* eat */ }
        }
        Token.Type type = (hasDot || hasExp) ? Token.Type.DECIMAL : Token.Type.INTEGER;
        return new Token(type, chars.emit());
    }

    private Token lexCharacter() {
        // character ::= ['] ([^'\n\r\\] | escape) [']

        // check opening quote
        Preconditions.checkState(chars.match("'"));

        // inside the quotes
        if (chars.peek("\\\\")) {
            lexEscape();
        } else if (chars.peek("[^'\\n\\r\\\\]")) {
            chars.match(".");
        }
        return new Token(Token.Type.CHARACTER, chars.emit());

    }

    private Token lexString() {
        // string ::= '"' ([^"\n\r\\] | escape)* '"'
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private void lexEscape() {
        // escape ::= '\' [bnrt'"\]
        Preconditions.checkState(chars.match("\\\\"));
        if (!chars.match("[bnrt'\"\\\\]")) {
            System.err.println("Invalid escape sequence");
        }

    }

    public Token lexOperator() {
        // operator ::= [<>!=] '='? | [^A-Za-z_0-9'" \b\n\r\t]
        Preconditions.checkState(chars.match("\""));
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    /**
     * A helper class for maintaining the state of the character stream (input)
     * and methods for building up token literals.
     */
    private static final class CharStream {

        private final String input;
        private int index = 0;
        private int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        /**
         * Returns true if the next character(s) match their corresponding
         * pattern(s). Each pattern is a regex matching ONE character, e.g.:
         *  - peek("/") is valid and will match the next character
         *  - peek("/", "/") is valid and will match the next two characters
         *  - peek("/+") is conceptually invalid, but will match one character
         *  - peek("//") is strictly invalid as it can never match one character
         */
        public boolean peek(String... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var character = input.charAt(index + offset);
                if (!String.valueOf(character).matches(patterns[offset])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the character stream.
         */
        public boolean match(String... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
                length += patterns.length;
            }
            return peek;
        }

        /**
         * Returns the literal built by all characters matched since the last
         * call to emit(); also resetting the length for subsequent tokens.
         */
        public String emit() {
            var literal = input.substring(index - length, index);
            length = 0;
            return literal;
        }

    }

}
