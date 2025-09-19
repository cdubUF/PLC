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
            tokens.add(lexToken());
        }
        return tokens;
    }

    private void lexWhitespace() {
        while(chars.match("[\\s]")) { }
        chars.emit(); // discard
    }

    private void lexComment() {
        Preconditions.checkState(chars.match("/", "/"));
        while (chars.has(0) && !chars.peek("[\\n]") && !chars.peek("[\\r]")) {
            chars.match("."); // eat until newline
        }
        chars.emit(); // discard comment
    }

    private Token lexToken() throws LexException {
        // token ::= identifier | number | character | string | operator

        // identifier starts with letter or underscore
        if (chars.peek("[A-Za-z_]")) return lexIdentifier();

        // number: optional sign only if a digit follows, or starts with a digit
        if (chars.peek("[+\\-]", "[0-9]") || chars.peek("[0-9]")) return lexNumber();

        // character
        if (chars.peek("'")) return lexCharacter();

        // string
        if (chars.peek("\"")) return lexString();

        // fallback: operator (catch-all)
        return lexOperator();
    }

    private Token lexIdentifier() {
        Preconditions.checkState(chars.match("[A-Za-z_]"));
        while(chars.match("[A-Za-z0-9_-]")) {}
        return new Token(Token.Type.IDENTIFIER, chars.emit());
        // identifier ::= [A-Za-z] [A-Za-z0-9_-]*
    }

    private Token lexNumber() throws LexException{
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
        Token.Type type = hasDot ? Token.Type.DECIMAL : Token.Type.INTEGER;
        return new Token(type, chars.emit());
    }

    private Token lexCharacter() throws LexException {
        // character ::= ['] ([^'\n\r\\] | escape) [']

        // check opening quote
        Preconditions.checkState(chars.match("'"));

        // inside the quotes
        if (chars.peek("\\\\")) {
            lexEscape();
        } else if (chars.peek("[^'\\n\\r\\\\]")) {
            chars.match(".");
        } else {
            throw new LexException("Invalid or empty character literal ", chars.getIndex());
        }

        // Step 3: closing quote
        if (!chars.match("'")) {
            throw new LexException("Unterminated character literal ", chars.getIndex());
        }
        return new Token(Token.Type.CHARACTER, chars.emit());

    }

    private Token lexString() throws LexException {
        // string ::= '"' ([^"\n\r\\] | escape)* '"'

        // Opening quote
        if (!chars.match("\"")) {
            throw new LexException("String must start with quote", chars.getIndex());
        }

        // Loop for the content
        while (true) {
            // End of string?
            if (chars.peek("\"")) {
                chars.match("\""); // consume closing
                return new Token(Token.Type.STRING, chars.emit());
            }

            // Escape sequence?
            if (chars.peek("\\\\")) {
                lexEscape(); // consumes '\' + valid escape char
                continue;
            }

            // Normal char?
            if (chars.peek("[^\"\\n\\r\\\\]")) {
                chars.match("."); // consume one safe char
                continue;
            }

            // edge cases
            if (!chars.has(0)) {
                throw new LexException("Unterminated string literal ", chars.getIndex());
            }
            if (chars.peek("[\\n]") || chars.peek("[\\r]")) {
                throw new LexException("String literal cannot contain raw newline ", chars.getIndex());
            }

            // Any other invalid character
            throw new LexException("Invalid character in string literal ", chars.getIndex());
        }
    }

    private void lexEscape() throws LexException{
        // escape ::= '\' [bnrt'"\]

        Preconditions.checkState(chars.match("\\\\"));
        if (!chars.match("[bnrt'\"\\\\]")) { throw new LexException("Invalid escape sequence", chars.getIndex()); }

    }

    public Token lexOperator() throws LexException{
        // operator ::= [<>!=] '='? | [^A-Za-z_0-9'" \b\n\r\t]

        // case 1: one of < > ! =, optionally followed by '='
        if (chars.peek("[<>!=]")) {
            chars.match("[<>!=]");        // consume the first operator char
            chars.match("=");             // optionally consume '=' for <= >= == !=
            return new Token(Token.Type.OPERATOR, chars.emit());
        }

        // Case 2: any single char NOT in [A-Za-z_0-9'" space backspace newline carriage tab]
        // Backspace is \u0008 (since \b outside a char class is word-boundary in regex).
        if (chars.match("[^A-Za-z_0-9'\" \\n\\r\\t\\u0008]")) {
            return new Token(Token.Type.OPERATOR, chars.emit());
        }

        // something slipped passed the checks but it still wrong
        throw new LexException("Unexpected character; not a valid operator", chars.getIndex());
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
        public int getIndex() { return index; }

    }

}
