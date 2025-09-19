package plc.project;

import plc.project.lexer.LexException;
import plc.project.lexer.Lexer;

import java.util.Scanner;

/**
 * Provides an entry point to a REPL (Read-Eval-Print-Loop) for each part of our
 * project, building up to fully evaluating input programs.
 */
public final class Main {

    private interface Repl {
        void evaluate(String input) throws LexException;
    }

    private static final Repl REPL = Main::lexer; //edit for manual testing

    public static void main(String[] args) {
        System.out.println("\ttab\\ttabescapeinvalid\\\\backslash".translateEscapes());
        while (true) {
            var input = readInput();
            try {
                REPL.evaluate(input);
            } catch (LexException e) {
                System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                e.printStackTrace(System.err);
            }
        }
    }

    private static void lexer(String input) throws LexException {
        var tokens = new Lexer(input).lex();
        System.out.println("List<Token>[size=" + tokens.size() + "]" + (tokens.isEmpty() ? "" : ":"));
        for (var token : tokens) {
            System.out.println(" - " + token);
        }
    }

    private static final Scanner SCANNER = new Scanner(System.in);

    public static String readInput() {
        var input = SCANNER.nextLine();
        if (!input.isEmpty()) {
            return input;
        }
        System.out.println("Multiline input - enter empty line to submit:");
        var builder = new StringBuilder();
        var next = SCANNER.nextLine();
        while (!next.isEmpty()) {
            builder.append(next).append("\n");
            next = SCANNER.nextLine();
        }
        return builder.toString();
    }

}
