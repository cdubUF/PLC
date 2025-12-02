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

        Type varType;

        // 1) Resolve declared type if present
        if (ast.type().isPresent()) {
            String typeName = ast.type().get();
            varType = Environment.TYPES.get(typeName);

            if (varType == null) {
                throw new AnalyzeException("Unknown type '" + typeName + "'", Optional.of(ast));
            }
        } else {
            varType = Type.DYNAMIC;
        }

        // 2) Evaluate initializer if present
        Optional<Ir.Expr> valueIr = Optional.empty();
        if (ast.value().isPresent()) {
            Ir.Expr init = visit(ast.value().get());
            valueIr = Optional.of(init);

            // If no declared type → infer it from initializer
            if (ast.type().isEmpty()) {
                varType = init.type();
            }

            // Check subtype compatibility
            if (!init.type().isSubtypeOf(varType)) {
                throw new AnalyzeException(
                        "Initializer of type " + init.type() +
                                " is not assignable to variable of type " + varType,
                        Optional.of(ast)
                );
            }
        }

        // 3) Define variable
        try {
            scope.define(ast.name(), varType);
        } catch (IllegalStateException ex) {
            throw new AnalyzeException("Variable '" + ast.name() + "' is already defined.", Optional.of(ast));
        }

        // 4) Return IR
        return new Ir.Stmt.Let(ast.name(), varType, valueIr);
    }



    @Override
    public Ir visit(Ast.Stmt.Def ast) throws AnalyzeException {
        // Resolve parameter types
        ArrayList<Type> paramTypes = new ArrayList<>();
        ArrayList<Ir.Stmt.Def.Parameter> irParams = new ArrayList<>();

        for (int i = 0; i < ast.parameters().size(); i++) {
            String name = ast.parameters().get(i);
            Optional<String> typeName = ast.parameterTypes().get(i);

            Type paramType = typeName.isPresent()
                    ? Optional.ofNullable(Environment.TYPES.get(typeName.get()))
                    .orElseThrow(() -> new AnalyzeException("Unknown parameter type.", Optional.of(ast)))
                    : Type.DYNAMIC;

            paramTypes.add(paramType);
            irParams.add(new Ir.Stmt.Def.Parameter(name, paramType));
        }

        // Resolve return type
        Type returnType = ast.returnType().isPresent()
                ? Optional.ofNullable(Environment.TYPES.get(ast.returnType().get()))
                .orElseThrow(() -> new AnalyzeException("Unknown return type.", Optional.of(ast)))
                : Type.DYNAMIC;

        // Define function in outer scope first (for recursion)
        Type.Function fnType = new Type.Function(paramTypes, returnType);
        scope.define(ast.name(), fnType);

        // Child scope for function body
        Scope previous = scope;
        scope = new Scope(previous);

        // Add parameters
        for (int i = 0; i < ast.parameters().size(); i++) {
            scope.define(ast.parameters().get(i), paramTypes.get(i));
        }

        // Add return binding
        scope.define("$RETURN", returnType);

        ArrayList<Ir.Stmt> bodyIr = new ArrayList<>();
        for (Ast.Stmt stmt : ast.body()) {
            bodyIr.add((Ir.Stmt) visit(stmt));
        }

        scope = previous;

        return new Ir.Stmt.Def(ast.name(), irParams, returnType, bodyIr);
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
    public Ir visit(Ast.Stmt.For ast) throws AnalyzeException {

        // 1. Analyze the iterable expression
        Ir.Expr iterable = (Ir.Expr) visit(ast.expression());

        if (!iterable.type().isSubtypeOf(Type.ITERABLE)) {
            throw new AnalyzeException("FOR requires an Iterable.", Optional.of(ast));
        }

        // 2. Create inner scope
        Scope saved = scope;
        scope = new Scope(saved);

        // 3. Loop variable must always be Integer (per specification)
        scope.define(ast.name(), Type.INTEGER);

        // 4. Analyze body
        var bodyIr = new ArrayList<Ir.Stmt>();
        for (Ast.Stmt stmt : ast.body()) {
            bodyIr.add((Ir.Stmt) visit(stmt));
        }

        // 5. Restore scope
        scope = saved;

        // 6. Return IR — NOTE the IR.For constructor signature:
        //    record For(String name, Type type, Expr expression, List<Stmt> body)
        return new Ir.Stmt.For(
                ast.name(),
                Type.INTEGER,
                iterable,
                bodyIr
        );
    }





    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {

        // 1. Must be inside a function
        var expected = scope.resolve("$RETURN", true);
        if (expected.isEmpty()) {
            throw new AnalyzeException("RETURN not inside function.", Optional.of(ast));
        }
        Type expectedType = expected.get();

        // 2. Check return WITH a value
        if (ast.value().isPresent()) {
            Ir.Expr valueIr = (Ir.Expr) visit(ast.value().get());

            if (!valueIr.type().isSubtypeOf(expectedType)) {
                throw new AnalyzeException("Return type mismatch.", Optional.of(ast));
            }

            return new Ir.Stmt.Return(Optional.of(valueIr));
        }

        // 3. Return WITHOUT a value
        if (expectedType != Type.NIL) {
            throw new AnalyzeException("Return type mismatch.", Optional.of(ast));
        }

        return new Ir.Stmt.Return(Optional.empty());
    }


    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException {

        // Case 1: VARIABLE ASSIGNMENT (core)
        if (ast.expression() instanceof Ast.Expr.Variable varAst) {

            var typeOpt = scope.resolve(varAst.name(), false);
            if (typeOpt.isEmpty()) {
                throw new AnalyzeException("Undefined variable '" + varAst.name() + "'", Optional.of(ast));
            }

            Type varType = typeOpt.get();
            Ir.Expr valueIr = visit(ast.value());

            if (!valueIr.type().isSubtypeOf(varType)) {
                throw new AnalyzeException("Cannot assign type " + valueIr.type()
                        + " to variable '" + varAst.name() + "' of type " + varType, Optional.of(ast));
            }

            return new Ir.Stmt.Assignment.Variable(
                    new Ir.Expr.Variable(varAst.name(), varType),
                    valueIr
            );
        }

        // Case 2: PROPERTY ASSIGNMENT (non-core)
        if (ast.expression() instanceof Ast.Expr.Property propAst) {

            Ir.Expr.Property propIr = visit(propAst);
            Ir.Expr valueIr = visit(ast.value());

            if (!valueIr.type().isSubtypeOf(propIr.type())) {
                throw new AnalyzeException("Property assignment type mismatch", Optional.of(ast));
            }

            return new Ir.Stmt.Assignment.Property(propIr, valueIr);
        }

        // Invalid LHS
        throw new AnalyzeException("Invalid assignment target.", Optional.of(ast));
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
        Type lt = left.type();
        Type rt = right.type();
        String op = ast.operator();
        Type result;

        switch (op) {

            // -------------------- PLUS --------------------
            case "+" -> {

                // dynamic + T => T
                if (lt == Type.DYNAMIC && rt != Type.DYNAMIC) {
                    result = rt;
                    break;
                }

                // T + dynamic => T
                if (rt == Type.DYNAMIC && lt != Type.DYNAMIC) {
                    result = lt;
                    break;
                }

                // either side is String => String concatenation
                if (lt == Type.STRING || rt == Type.STRING) {
                    result = Type.STRING;
                    break;
                }

                // dynamic + dynamic => dynamic
                if (lt == Type.DYNAMIC && rt == Type.DYNAMIC) {
                    result = Type.DYNAMIC;
                    break;
                }

                // numeric: ONLY same-type integer+integer or decimal+decimal
                boolean lNum = (lt == Type.INTEGER || lt == Type.DECIMAL);
                boolean rNum = (rt == Type.INTEGER || rt == Type.DECIMAL);

                if (!lNum || !rNum || lt != rt) {
                    // this is where 1 (Integer) + 1.0 (Decimal) should fail
                    throw new AnalyzeException(
                            "Operator '+' requires matching numeric operands.",
                            Optional.of(ast)
                    );
                }

                // both INTEGER or both DECIMAL
                result = lt; // same as rt
            }

            // -------------------- -, *, / --------------------
            case "-", "*", "/" -> {

                // dynamic + T => T
                if (lt == Type.DYNAMIC && rt != Type.DYNAMIC) {
                    result = rt;
                    break;
                }

                // T + dynamic => T
                if (rt == Type.DYNAMIC && lt != Type.DYNAMIC) {
                    result = lt;
                    break;
                }

                // dynamic / dynamic => dynamic
                if (lt == Type.DYNAMIC && rt == Type.DYNAMIC) {
                    result = Type.DYNAMIC;
                    break;
                }

                boolean lNum = (lt == Type.INTEGER || lt == Type.DECIMAL);
                boolean rNum = (rt == Type.INTEGER || rt == Type.DECIMAL);

                // again, only same-type numeric arithmetic is allowed
                if (!lNum || !rNum || lt != rt) {
                    throw new AnalyzeException(
                            "Operator '" + op + "' requires matching numeric operands.",
                            Optional.of(ast)
                    );
                }

                result = lt;
            }

            // -------------------- COMPARISONS --------------------
            case "<", ">", "<=", ">=" -> {
                boolean comparable =
                        lt.isSubtypeOf(Type.COMPARABLE) &&
                                rt.isSubtypeOf(Type.COMPARABLE);

                if (!comparable) {
                    throw new AnalyzeException(
                            "Operator '" + op + "' requires Comparable operands.",
                            Optional.of(ast)
                    );
                }

                result = Type.BOOLEAN;
            }

            // -------------------- LOGICAL AND / OR --------------------
            // note: tests use uppercase "AND"/"OR"
            case "AND", "OR", "and", "or" -> {
                boolean lOk = lt.isSubtypeOf(Type.BOOLEAN) || lt == Type.DYNAMIC;
                boolean rOk = rt.isSubtypeOf(Type.BOOLEAN) || rt == Type.DYNAMIC;

                if (!lOk || !rOk) {
                    throw new AnalyzeException(
                            "Operator '" + op + "' requires Boolean or Dynamic operands.",
                            Optional.of(ast)
                    );
                }

                result = Type.BOOLEAN;
            }

            // -------------------- EQUALITY --------------------
            case "==", "!=" -> {
                boolean ok =
                        lt.isSubtypeOf(rt) ||
                                rt.isSubtypeOf(lt) ||
                                lt == Type.ANY ||
                                rt == Type.ANY ||
                                lt == Type.DYNAMIC ||
                                rt == Type.DYNAMIC;

                if (!ok) {
                    throw new AnalyzeException(
                            "Operator '" + op + "' cannot compare " + lt + " and " + rt + ".",
                            Optional.of(ast)
                    );
                }

                result = Type.BOOLEAN;
            }

            default -> throw new AnalyzeException(
                    "Unsupported operator '" + op + "'.",
                    Optional.of(ast)
            );
        }

        return new Ir.Expr.Binary(op, left, right, result);
    }


    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var typeOpt = scope.resolve(ast.name(), false);
        if (typeOpt.isEmpty()) {
            throw new AnalyzeException("Undefined variable '" + ast.name() + "'",
                    java.util.Optional.of(ast));
        }
        return new Ir.Expr.Variable(ast.name(), typeOpt.get());
    }


    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        Ir.Expr recv = visit(ast.receiver());
        Type rt = recv.type();

        // Dynamic → dynamic property access
        if (rt == Type.DYNAMIC) {
            return new Ir.Expr.Property(recv, ast.name(), Type.DYNAMIC);
        }

        // Must be object
        if (!(rt instanceof Type.ObjectType objType)) {
            throw new AnalyzeException("Property access on non-object.", Optional.of(ast));
        }

        // Lookup property
        Optional<Type> prop = objType.scope().resolve(ast.name(), false);
        if (prop.isEmpty()) {
            throw new AnalyzeException("Unknown property.", Optional.of(ast));
        }

        return new Ir.Expr.Property(recv, ast.name(), prop.get());
    }




    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        // 1. Lookup function type
        var typeOpt = scope.resolve(ast.name(), false);
        if (typeOpt.isEmpty() || !(typeOpt.get() instanceof Type.Function fnType)) {
            throw new AnalyzeException("Undefined function '" + ast.name() + "'", Optional.of(ast));
        }

        // 2. Visit arguments
        ArrayList<Ir.Expr> argIrs = new ArrayList<>();
        for (Ast.Expr argAst : ast.arguments()) {
            argIrs.add((Ir.Expr) visit(argAst));
        }

        // 3. Arity check
        if (fnType.parameters().size() != argIrs.size()) {
            throw new AnalyzeException(
                    "Function '" + ast.name() + "' expects " +
                            fnType.parameters().size() + " arguments but got " + argIrs.size(),
                    Optional.of(ast)
            );
        }

        // 4. Type check each argument
        for (int i = 0; i < argIrs.size(); i++) {
            Type argType = argIrs.get(i).type();
            Type paramType = fnType.parameters().get(i);

            boolean ok =
                    argType.isSubtypeOf(paramType) ||
                            argType == Type.DYNAMIC ||
                            paramType == Type.ANY ||
                            paramType == Type.DYNAMIC;

            if (!ok) {
                throw new AnalyzeException(
                        "Argument " + i + " of '" + ast.name() + "' has type " +
                                argType + ", expected " + paramType,
                        Optional.of(ast)
                );
            }
        }

        return new Ir.Expr.Function(ast.name(), argIrs, fnType.returns());
    }



    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        Ir.Expr recv = (Ir.Expr) visit(ast.receiver());
        Type recvType = recv.type();

        // Dynamic receiver → Always allowed
        if (recvType == Type.DYNAMIC) {
            var argIrs = new ArrayList<Ir.Expr>();
            for (var arg : ast.arguments()) {
                argIrs.add((Ir.Expr) visit(arg));
            }
            return new Ir.Expr.Method(recv, ast.name(), argIrs, Type.DYNAMIC);
        }

        // Must be object
        if (!(recvType instanceof Type.ObjectType objType)) {
            throw new AnalyzeException("Method call on non-object.", Optional.of(ast));
        }

        // Look up member
        Optional<Type> member = objType.scope().resolve(ast.name(), false);
        if (member.isEmpty() || !(member.get() instanceof Type.Function fnType)) {
            throw new AnalyzeException("Unknown method.", Optional.of(ast));
        }

        // Analyze arguments
        ArrayList<Ir.Expr> argIrs = new ArrayList<>();
        for (Ast.Expr argAst : ast.arguments()) {
            argIrs.add((Ir.Expr) visit(argAst));
        }

        if (fnType.parameters().size() != argIrs.size()) {
            throw new AnalyzeException("Method argument count mismatch.", Optional.of(ast));
        }

        // Check types
        for (int i = 0; i < argIrs.size(); i++) {
            Type argType = argIrs.get(i).type();
            Type paramType = fnType.parameters().get(i);

            // Allow dynamic & Any
            boolean ok = argType.isSubtypeOf(paramType)
                    || paramType == Type.ANY
                    || paramType == Type.DYNAMIC;

            if (!ok) {
                throw new AnalyzeException("Argument type mismatch.", Optional.of(ast));
            }
        }

        return new Ir.Expr.Method(recv, ast.name(), argIrs, fnType.returns());
    }




    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {

        // Create object scope
        Scope objScope = new Scope(null);

        ArrayList<Ir.Stmt.Let> fields = new ArrayList<>();
        ArrayList<Ir.Stmt.Def> methods = new ArrayList<>();

        // ---------- FIELDS ----------
        for (Ast.Stmt.Let f : ast.fields()) {

            // Evaluate field using objScope ONCE
            Scope saved = scope;
            scope = objScope;

            Ir.Stmt.Let fieldIr = (Ir.Stmt.Let) visit(f);

            scope = saved;

            fields.add(fieldIr);
            // DO NOT define again — visit(Let) already defined it
        }

        // ---------- PREDECLARE METHODS ----------
        for (Ast.Stmt.Def d : ast.methods()) {

            // Parameter types
            ArrayList<Type> paramTypes = new ArrayList<>();
            for (int i = 0; i < d.parameters().size(); i++) {
                var t = d.parameterTypes().get(i)
                        .map(Environment.TYPES::get)
                        .orElse(Type.DYNAMIC);
                paramTypes.add(t);
            }

            // DEFAULT RETURN TYPE SHOULD BE DYNAMIC
            Type returnType = d.returnType()
                    .map(Environment.TYPES::get)
                    .orElse(Type.DYNAMIC);

            Type.Function fnType = new Type.Function(paramTypes, returnType);

            // predeclare only ONCE
            objScope.define(d.name(), fnType);
        }

        // ---------- ANALYZE METHOD BODIES (no redefining) ----------
        for (Ast.Stmt.Def d : ast.methods()) {
            Scope saved = scope;

            // Analyze using the object scope as parent
            scope = new Scope(objScope);

            // Add parameters to this new scope
            ArrayList<Type> paramTypes = new ArrayList<>();
            for (int i = 0; i < d.parameters().size(); i++) {
                Type pt = d.parameterTypes().get(i)
                        .map(Environment.TYPES::get)
                        .orElse(Type.DYNAMIC);
                paramTypes.add(pt);
                scope.define(d.parameters().get(i), pt);
            }

            // Add RETURN type
            Type returnType = d.returnType()
                    .map(Environment.TYPES::get)
                    .orElse(Type.DYNAMIC);
            scope.define("$RETURN", returnType);

            // Visit body
            ArrayList<Ir.Stmt> bodyIr = new ArrayList<>();
            for (Ast.Stmt s : d.body()) {
                bodyIr.add((Ir.Stmt) visit(s));
            }

            scope = saved;

            // Add to IR
            methods.add(new Ir.Stmt.Def(
                    d.name(),
                    buildParams(d, paramTypes),
                    returnType,
                    bodyIr
            ));
        }

        // Construct final object type
        Type.ObjectType type = new Type.ObjectType(Optional.empty(), objScope);

        return new Ir.Expr.ObjectExpr(Optional.empty(), fields, methods, type);
    }

    private ArrayList<Ir.Stmt.Def.Parameter> buildParams(
            Ast.Stmt.Def d, ArrayList<Type> paramTypes) {

        ArrayList<Ir.Stmt.Def.Parameter> params = new ArrayList<>();
        for (int i = 0; i < d.parameters().size(); i++) {
            params.add(new Ir.Stmt.Def.Parameter(
                    d.parameters().get(i),
                    paramTypes.get(i)
            ));
        }
        return params;
    }



}
