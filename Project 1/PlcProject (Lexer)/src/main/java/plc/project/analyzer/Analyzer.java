package plc.project.analyzer;

import plc.project.parser.Ast;
import java.util.Optional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;

public final class Analyzer implements Ast.Visitor<Ir, AnalyzeException> {

    private Scope scope;

    public Analyzer(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }


    @Override
    public Ir.Source visit(Ast.Source ast) throws AnalyzeException {
        var statements = new ArrayList<Ir.Stmt>();
        for (var statement : ast.statements()) {
            statements.add(visit(statement));
        }
        return new Ir.Source(statements);
    }

    private Ir.Stmt visit(Ast.Stmt ast) throws AnalyzeException {
        return (Ir.Stmt) visit((Ast) ast); //helper to cast visit(Ast.Stmt) to Ir.Stmt
    }

    @Override
    public Ir.Stmt.Let visit(Ast.Stmt.Let ast) throws AnalyzeException {
        // Analyze initializer
        java.util.Optional<Ir.Expr> valueIrOpt = java.util.Optional.empty();
        Type valueType = null;
        if (ast.value().isPresent()) {
            Ir.Expr valueIr = visit(ast.value().get());
            valueIrOpt = java.util.Optional.of(valueIr);
            valueType = valueIr.type();
        }

        /*
         - value type if present
         - otherwise Dynamic
         */
        Type variableType;
        if (valueType != null) {
            variableType = valueType;
        } else {
            variableType = Type.DYNAMIC;
        }

        // Define variable in current scope
        try {
            scope.define(ast.name(), variableType);
        } catch (IllegalStateException ex) {
            throw new AnalyzeException("Variable '" + ast.name() + "' is already defined.",
                    java.util.Optional.of(ast));
        }

        return new Ir.Stmt.Let(ast.name(), variableType, valueIrOpt);
    }


    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        // Condition
        Ir.Expr condition = visit(ast.condition());
        if (!condition.type().isSubtypeOf(Type.BOOLEAN)) {
            throw new AnalyzeException(
                    "IF condition must be Boolean, got " + condition.type(),
                    java.util.Optional.of(ast)
            );
        }

        // Save outer scope
        Scope outer = scope;

        // THEN body
        scope = new Scope(outer);
        var thenBody = new ArrayList<Ir.Stmt>();
        for (var stmtAst : ast.thenBody()) {
            thenBody.add(visit(stmtAst));
        }

        // ELSE body
        scope = new Scope(outer);
        var elseBody = new ArrayList<Ir.Stmt>();
        for (var stmtAst : ast.elseBody()) {
            elseBody.add(visit(stmtAst));
        }

        // Restore outer scope
        scope = outer;

        return new Ir.Stmt.If(condition, thenBody, elseBody);
    }


    @Override
    public Ir.Stmt.For visit(Ast.Stmt.For ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException {
        // LHS must be a variable for core functionality
        if (!(ast.expression() instanceof Ast.Expr.Variable varAst)) {
            throw new AnalyzeException("Invalid assignment target.", Optional.of(ast));
        }

        // RHS expression must be analyzed
        Ir.Expr valueIr = visit(ast.value());
        Type valueType = valueIr.type();

        // Variable must already be defined
        var typeOpt = scope.get(varAst.name(), false);
        if (typeOpt.isEmpty()) {
            throw new AnalyzeException("Undefined variable '" + varAst.name() + "'", Optional.of(ast));
        }

        Type varType = typeOpt.get();

        // Check subtype rule
        if (!valueType.isSubtypeOf(varType)) {
            throw new AnalyzeException(
                    "Cannot assign value of type " + valueType +
                            " to variable '" + varAst.name() + "' of type " + varType,
                    Optional.of(ast)
            );
        }

        // Construct IR variable reference
        Ir.Expr.Variable irVar = new Ir.Expr.Variable(varAst.name(), varType);

        return new Ir.Stmt.Assignment.Variable(irVar, valueIr);
    }

    private Ir.Expr visit(Ast.Expr ast) throws AnalyzeException {
        return (Ir.Expr) visit((Ast) ast); //helper to cast visit(Ast.Expr) to Ir.Expr
    }

    @Override
    public Ir.Expr.Literal visit(Ast.Expr.Literal ast) throws AnalyzeException {
        var type = switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case Character _ -> Type.CHARACTER;
            case String _ -> Type.STRING;
            default -> throw new AssertionError(ast.value().getClass());
        };
        return new Ir.Expr.Literal(ast.value(), type);
    }

    @Override
    public Ir.Expr.Group visit(Ast.Expr.Group ast) throws AnalyzeException {
        Ir.Expr inner = visit(ast.expression());
        return new Ir.Expr.Group(inner);
    }


    @Override
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        Ir.Expr left = visit(ast.left());
        Ir.Expr right = visit(ast.right());
        Type leftType = left.type();
        Type rightType = right.type();
        String op = ast.operator();

        Type resultType;

        switch (op) {
            case "+", "-", "*", "/" -> {
                // Both Dynamic → result is Dynamic.
                if (leftType == Type.DYNAMIC && rightType == Type.DYNAMIC) {
                    resultType = Type.DYNAMIC;
                } else {
                    boolean leftNumeric = leftType == Type.INTEGER || leftType == Type.DECIMAL;
                    boolean rightNumeric = rightType == Type.INTEGER || rightType == Type.DECIMAL;

                    if (!leftNumeric || !rightNumeric) {
                        throw new AnalyzeException(
                                "Operator '" + op + "' requires numeric operands.",
                                java.util.Optional.of(ast)
                        );
                    }

                    // If either is Decimal → Decimal; else Integer.
                    if (leftType == Type.DECIMAL || rightType == Type.DECIMAL) {
                        resultType = Type.DECIMAL;
                    } else {
                        resultType = Type.INTEGER;
                    }
                }
            }
            case "==", "!=" -> {
                boolean leftSubRight = leftType.isSubtypeOf(rightType);
                boolean rightSubLeft = rightType.isSubtypeOf(leftType);
                if (!leftSubRight && !rightSubLeft) {
                    throw new AnalyzeException(
                            "Cannot compare " + leftType + " and " + rightType + " using '" + op + "'.",
                            java.util.Optional.of(ast)
                    );
                }
                resultType = Type.BOOLEAN;
            }
            default -> {
                // For core, we only guarantee the above operators.
                throw new AnalyzeException("Unsupported operator '" + op + "' in Analyzer.",
                        java.util.Optional.of(ast));
            }
        }

        return new Ir.Expr.Binary(op, left, right, resultType);
    }


    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var typeOpt = scope.get(ast.name(), false);
        if (typeOpt.isEmpty()) {
            throw new AnalyzeException("Undefined variable '" + ast.name() + "'",
                    java.util.Optional.of(ast));
        }
        return new Ir.Expr.Variable(ast.name(), typeOpt.get());
    }


    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        // Look up function type in scope
        var typeOpt = scope.get(ast.name(), false);
        if (typeOpt.isEmpty() || !(typeOpt.get() instanceof Type.Function fnType)) {
            throw new AnalyzeException("Undefined function '" + ast.name() + "'",
                    java.util.Optional.of(ast));
        }

        // Analyze arguments
        var argIrs = new ArrayList<Ir.Expr>();
        for (var argAst : ast.arguments()) {
            argIrs.add(visit(argAst));
        }

        // Arity check
        if (fnType.parameters().size() != argIrs.size()) {
            throw new AnalyzeException(
                    "Function '" + ast.name() + "' expects " + fnType.parameters().size() +
                            " arguments but got " + argIrs.size(),
                    java.util.Optional.of(ast)
            );
        }

        // Type-check each argument
        for (int i = 0; i < argIrs.size(); i++) {
            Type argType = argIrs.get(i).type();
            Type paramType = fnType.parameters().get(i);
            if (!argType.isSubtypeOf(paramType)) {
                throw new AnalyzeException(
                        "Argument " + i + " of '" + ast.name() + "' has type " + argType +
                                ", expected " + paramType,
                        java.util.Optional.of(ast)
                );
            }
        }

        // Expression’s type is the function's return type
        return new Ir.Expr.Function(ast.name(), argIrs, fnType.returns());
    }


    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

}
