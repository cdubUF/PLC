package plc.project.generator;

import plc.project.analyzer.Ir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

public final class Generator implements Ir.Visitor<StringBuilder, RuntimeException> {

    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    private void newline(int indent) {
        builder.append("\n");
        builder.append("    ".repeat(indent));
    }

    @Override
    public StringBuilder visit(Ir.Source ir) {
        builder.append(Environment.imports()).append("\n\n");
        builder.append("public final class Main {").append("\n\n");
        builder.append(Environment.definitions()).append("\n");
        //Java doesn't allow for nested functions, but we will pretend it does.
        //To support simple programs involving functions, we will "hoist" any
        //variable/function declaration at the start of the program to allow
        //these functions to be used as valid Java.
        indent = 1;
        boolean main = false;
        for (var statement : ir.statements()) {
            newline(indent);
            if (!main) {
                if (statement instanceof Ir.Stmt.Let || statement instanceof Ir.Stmt.Def) {
                    builder.append("static ");
                } else {
                    builder.append("public static void main(String[] args) {");
                    main = true;
                    indent = 2;
                    newline(indent);
                }
            }
            visit(statement);
        }
        if (main) {
            builder.append("\n").append("    }");
        }
        indent = 0;
        builder.append("\n\n").append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Let ir) {
        // indent is already positioned correctly before calling visit()
        // Format:
        // <type> <name>;
        // <type> <name> = <value>;

        String typeName = ir.type().jvmName();   // e.g., "BigInteger", "String", "var"

        builder.append(typeName)
                .append(" ")
                .append(ir.name());

        if (ir.value().isPresent()) {
            builder.append(" = ");
            visit(ir.value().get());
        }

        builder.append(";");
        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Stmt.Def ir) {

        // <ReturnType> name(paramType paramName, ...)
        builder.append(ir.returns().jvmName())
                .append(" ")
                .append(ir.name())
                .append("(");

        // parameters
        for (int i = 0; i < ir.parameters().size(); i++) {
            var param = ir.parameters().get(i);
            builder.append(param.type().jvmName())
                    .append(" ")
                    .append(param.name());
            if (i < ir.parameters().size() - 1) {
                builder.append(", ");
            }
        }

        builder.append(") {");

        // body
        indent++;
        for (var stmt : ir.body()) {
            newline(indent);
            visit(stmt);
        }
        indent--;

        newline(indent);
        builder.append("}");

        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {

        // if (<condition>) {
        builder.append("if (");
        visit(ir.condition());
        builder.append(") {");

        // THEN body
        indent++;
        for (var stmt : ir.thenBody()) {
            newline(indent);
            visit(stmt);
        }
        indent--;

        newline(indent);
        builder.append("}");

        // ONLY generate else if elseBody is NOT empty
        if (!ir.elseBody().isEmpty()) {
            builder.append(" else {");

            indent++;
            for (var stmt : ir.elseBody()) {
                newline(indent);
                visit(stmt);
            }
            indent--;

            newline(indent);
            builder.append("}");
        }

        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Stmt.For ir) {

        // for (<Type> <name> : <expression>) {
        builder.append("for (")
                .append(ir.type().jvmName())
                .append(" ")
                .append(ir.name())
                .append(" : ");
        visit(ir.expression());
        builder.append(") {");

        // body
        indent++;
        for (var stmt : ir.body()) {
            newline(indent);
            visit(stmt);
        }
        indent--;

        newline(indent);
        builder.append("}");

        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Return ir) {

        builder.append("return");

        if (ir.value().isPresent()) {
            builder.append(" ");
            visit(ir.value().get());
            builder.append(";");
        } else {
            // NIL corresponds to Void in JVM, but Java still requires: return null;
            builder.append(" null;");
        }

        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Stmt.Expression ir) {
        visit(ir.expression());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Variable ir) {

        // <variable> = <value>;
        visit(ir.variable());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");

        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Property ir) {

        // <receiver>.<name> = <value>;
        visit(ir.property());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");

        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Literal ir) {
        var literal = switch (ir.value()) {
            case null -> "null";
            case Boolean b -> b.toString();
            case BigInteger i -> "new BigInteger(\"" + i + "\")";
            case BigDecimal d -> "new BigDecimal(\"" + d + "\")";
            case Character c -> "\'" + c + "\'"; //Limitation: escapes unsupported
            case String s -> "\"" + s + "\""; //Limitation: escapes unsupported
            default -> throw new AssertionError(ir.value());
        };
        return builder.append(literal);
    }

    @Override
    public StringBuilder visit(Ir.Expr.Group ir) {
        builder.append("(");
        visit(ir.expression());
        builder.append(")");
        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Expr.Binary ir) {

        String op = ir.operator();
        Ir.Expr left = ir.left();
        Ir.Expr right = ir.right();

        // STRING CONCAT: if either side is String, use native Java "+"
        if (op.equals("+") &&
                (left.type() == plc.project.analyzer.Type.STRING ||
                        right.type() == plc.project.analyzer.Type.STRING)) {

            visit(left);
            builder.append(" + ");
            visit(right);
            return builder;
        }

        // EQUALITY: Objects.equals(left, right) / !Objects.equals(left, right)
        if (op.equals("==")) {
            builder.append("Objects.equals(");
            visit(left);
            builder.append(", ");
            visit(right);
            builder.append(")");
            return builder;
        }

        if (op.equals("!=")) {
            builder.append("!Objects.equals(");
            visit(left);
            builder.append(", ");
            visit(right);
            builder.append(")");
            return builder;
        }

        // ----------------------------------------------------------
        // LOGICAL
        // ----------------------------------------------------------

        // OR
        // Always wrapped as (left || right)
        if (op.equalsIgnoreCase("or")) {
            builder.append("(");
            visit(left);
            builder.append(" || ");
            visit(right);
            builder.append(")");
            return builder;
        }

        // ---------- AND ----------
        // Wrap left ONLY IF it is an OR — but OR is already wrapped with parentheses above,
        // so do NOT add parentheses a second time.
        if (op.equalsIgnoreCase("and")) {

            boolean leftIsOr = left instanceof Ir.Expr.Binary
                    && ((Ir.Expr.Binary) left).operator().equalsIgnoreCase("or");

            if (leftIsOr) {
                visit(left);
            } else {
                visit(left);
            }

            builder.append(" && ");
            visit(right);
            return builder;
        }



        // 4. RELATIONAL: < > <= >= via compareTo
        if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {

            // (left).compareTo(right) < 0   etc.
            builder.append("(");
            visit(left);
            builder.append(").compareTo(");
            visit(right);
            builder.append(") ");

            switch (op) {
                case "<"  -> builder.append("< 0");
                case ">"  -> builder.append("> 0");
                case "<=" -> builder.append("<= 0");
                case ">=" -> builder.append(">= 0");
            }

            return builder;
        }

        // 5. NUMERIC ARITHMETIC: +, -, *, / on Integer/Decimal
        // We always emit:  (left).<op>(right)
        builder.append("(");
        visit(left);
        builder.append(")");

        switch (op) {
            case "+" -> {
                // (left).add(right)
                builder.append(".add(");
                visit(right);
                builder.append(")");
            }
            case "-" -> {
                // (left).subtract(right)
                builder.append(".subtract(");
                visit(right);
                builder.append(")");
            }
            case "*" -> {
                // (left).multiply(right)
                builder.append(".multiply(");
                visit(right);
                builder.append(")");
            }
            case "/" -> {
                // Decimal: .divide(right, RoundingMode.HALF_EVEN)
                // Integer: .divide(right)
                builder.append(".divide(");
                visit(right);
                if (left.type() == plc.project.analyzer.Type.DECIMAL ||
                        right.type() == plc.project.analyzer.Type.DECIMAL) {
                    builder.append(", RoundingMode.HALF_EVEN");
                }
                builder.append(")");
            }
            default -> throw new RuntimeException("Unsupported operator: " + op);
        }

        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Expr.Variable ir) {
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Property ir) {
        visit(ir.receiver());
        builder.append(".").append(ir.name());
        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Expr.Function ir) {

        // <name>(arg1, arg2, ...)
        builder.append(ir.name()).append("(");

        for (int i = 0; i < ir.arguments().size(); i++) {
            visit(ir.arguments().get(i));
            if (i < ir.arguments().size() - 1) {
                builder.append(", ");
            }
        }

        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Method ir) {

        // <receiver>.<method>(args...)
        visit(ir.receiver());
        builder.append(".").append(ir.name()).append("(");

        for (int i = 0; i < ir.arguments().size(); i++) {
            visit(ir.arguments().get(i));
            if (i < ir.arguments().size() - 1) {
                builder.append(", ");
            }
        }

        builder.append(")");
        return builder;
    }


    @Override
    public StringBuilder visit(Ir.Expr.ObjectExpr ir) {

        builder.append("new Object() {");
        indent++;

        //
        // FIELDS — print directly, NO initializer block
        //
        for (var field : ir.fields()) {
            newline(indent);
            visit(field);   // Let stmt already prints `<type> name = value;`
        }

        //
        // METHODS — print directly after fields
        //
        for (var method : ir.methods()) {
            newline(indent);
            visit(method);
        }

        indent--;
        newline(indent);
        builder.append("}");

        return builder;
    }

}
