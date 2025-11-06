package plc.project.analyzer;

import plc.project.parser.Ast;

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
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
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
        throw new UnsupportedOperationException("TODO"); //TODO
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
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        throw new UnsupportedOperationException("TODO"); //TODO
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
