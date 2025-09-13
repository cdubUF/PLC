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
            //TODO: Skip whitespace/comments
            tokens.add(lexToken());
        }
        return tokens;
    }

    private void lexWhitespace() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private void lexComment() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Token lexToken() {
        // if a letter, then consum a letter
        // then call lexIdentifier with half an identifier built
        // indentifer
        // token :: = identifier | number | character | string

        if (chars.peek("[A-Za-z]")) { // .peek() looks at the next character and doesnt advance the iterable
            return lexIdentifier();
        }
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Token lexIdentifier() {
        while(chars.match("[A-Za-z0-9_-]")) {}
        return new Token(Token.Type.IDENTIFIER, chars.emit());
        // identifier ::= [A-Za-z] [A-Za-z0-9_-]*
    }

    private Token lexNumber() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Token lexCharacter() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Token lexString() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private void lexEscape() {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    public Token lexOperator() {
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
