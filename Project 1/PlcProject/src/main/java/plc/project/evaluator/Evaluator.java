package plc.project.evaluator;

import plc.project.parser.Ast;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        try {
            for (Ast.Stmt stmt : ast.statements()) {   // <-- record accessor
                value = visit(stmt);
            }
        } catch (ReturnValue rv) {                     // <-- this class already exists in your Evaluator
            throw new EvaluateException("RETURN outside of a function.",
                    java.util.Optional.of(ast));       // <-- attach Source AST
        }
        return value;
    }



    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        // 1) check if name is in current scope
        if (scope.resolve(ast.name(), /*current=*/true).isPresent()) {
            throw new EvaluateException("Variable already defined in current scope: " + ast.name(),
                    java.util.Optional.of(ast));
        }

        // 2) evaluate value if present; otherwise use NIL.
        RuntimeValue value = ast.value().isPresent()
                ? visit(ast.value().get())
                : NIL();

        // 3) Define in current scope and return the value we stored.
        scope.define(ast.name(), value);
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        Scope definingScope = this.scope;                   // capture for static scoping

        RuntimeValue.Function fn = new RuntimeValue.Function(ast.name(), args -> {
            // (Keep your current arity check here if you have one)
            // Create scope rooted at definition point
            Scope saved = this.scope;
            this.scope = new Scope(definingScope);
            try {
                // Bind parameters
                for (int i = 0; i < ast.parameters().size(); i++) {   // <-- record accessor
                    this.scope.define(ast.parameters().get(i), args.get(i));
                }

                // Execute body; RETURN is signaled by ReturnValue
                try {
                    for (Ast.Stmt s : ast.body()) {                   // <-- record accessor
                        visit(s);
                    }
                } catch (ReturnValue rv) {
                    return rv.value;                                  // explicit RETURN
                }

                // No RETURN encountered → functions return NIL
                return new RuntimeValue.Primitive(null);

            } finally {
                this.scope = saved;
            }
        });

        scope.define(ast.name(), fn);
        return fn;
    }






    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
        // 1) Evaluate condition and require BOOLEAN.
        RuntimeValue condRv = visit(ast.condition());
        Boolean cond = requireType(condRv, Boolean.class).orElseThrow(() ->
                new EvaluateException("IF condition must be BOOLEAN.", java.util.Optional.of(ast)));

        // 2) Choose branch, run its statements in a NEW scope, return last value (or NIL).
        if (cond) {
            return evalBlockInNewScope(ast.thenBody());
        } else {
            // elseBody is a List<Stmt> (possibly empty) per your Ast.java
            return evalBlockInNewScope(ast.elseBody());
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        // 1) Evaluate the iterable expression
        RuntimeValue iterableValue = visit(ast.expression());

        // 2. Ensure it’s a List (our language's Iterable)
        List iterable = requireType(iterableValue, List.class)
                .orElseThrow(() -> new EvaluateException("FOR expression must evaluate to a list.", Optional.of(ast)));

        // 3. Iterate over each element
        for (Object element : iterable) {
            Scope outerSaved = this.scope;
            try {
                // Scope for variable binding (per iteration)
                Scope iterationScope = new Scope(outerSaved);
                this.scope = iterationScope;

                // Define loop variable (like `x`)
                this.scope.define(ast.name(), (RuntimeValue) element);

                // New scope for body to allow shadowing
                RuntimeValue last = new RuntimeValue.Primitive(null);
                Scope bodyScope = new Scope(iterationScope);
                this.scope = bodyScope;

                for (Ast.Stmt stmt : ast.body()) {
                    last = visit(stmt);
                }

            } catch (ReturnValue rv) {
                // In a for-loop, a return propagates immediately
                throw rv;
            } finally {
                this.scope = outerSaved; // Always restore parent scope
            }
        }

        // 4. After loop ends, return NIL
        return new RuntimeValue.Primitive(null);
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        RuntimeValue value = ast.value().isPresent()
                ? visit(ast.value().get())
                : new RuntimeValue.Primitive(null);
        throw new ReturnValue(value);
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {

        // Variable assignment
        if (ast.expression() instanceof Ast.Expr.Variable v) {
            String name = v.name();
            // Must already exist (any visible scope)
            if (scope.resolve(name, false).isEmpty()) {
                throw new EvaluateException("Variable not defined: " + name,
                        java.util.Optional.of(ast.expression()));  // <-- point at LHS expr
            }
            // Only now evaluate RHS
            RuntimeValue value = visit(ast.value());
            scope.assign(name, value);
            return value;
        }

        // Property assignment
        if (ast.expression() instanceof Ast.Expr.Property p) {
            RuntimeValue recv = visit(p.receiver());
            RuntimeValue.ObjectValue obj = requireType(recv, RuntimeValue.ObjectValue.class)
                    .orElseThrow(() -> new EvaluateException("Assignment receiver must be an object.",
                            java.util.Optional.of(ast.expression()))); // <-- LHS expr

            RuntimeValue value = visit(ast.value());

            // assign or define on object (not prototype)
            if (obj.scope().resolve(p.name(), true).isPresent()) {
                obj.scope().assign(p.name(), value);
            } else {
                // If property exists only on prototype chain, that’s illegal to assign into
                var proto = obj.scope().resolve("prototype", true);
                if (proto.isPresent()) {
                    RuntimeValue.ObjectValue protoObj = requireType(proto.get(), RuntimeValue.ObjectValue.class)
                            .orElse(null);
                    if (protoObj != null && protoObj.scope().resolve(p.name(), false).isPresent()) {
                        throw new EvaluateException("Cannot assign to prototype field: " + p.name(),
                                java.util.Optional.of(ast.expression())); // <-- LHS expr
                    }
                }
                obj.scope().define(p.name(), value);
            }
            return value;
        }

        // Anything else: invalid target; blame LHS expr
        throw new EvaluateException("Invalid assignment target.", java.util.Optional.of(ast.expression()));
    }



    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        // Parentheses don't change meaning; just evaluate the inner expression.
        return visit(ast.expression());
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        final String op = ast.operator().toUpperCase(java.util.Locale.ROOT);


        if ("AND".equals(op) || "OR".equals(op)) {
            // Always evaluate left; that’s where log(true) happens.
            RuntimeValue leftRv = visit(ast.left());
            Boolean leftBool = requireType(leftRv, Boolean.class).orElseThrow(() ->
                    new EvaluateException("Left operand must be BOOLEAN for " + op, Optional.of(ast.left()))
            );

            if ("AND".equals(op)) {
                if (!leftBool) {
                    // short-circuit: return the evaluated left value itself
                    return leftRv;
                }
                // must evaluate right only if left was true
                RuntimeValue rightRv = visit(ast.right());
                Boolean rightBool = requireType(rightRv, Boolean.class).orElseThrow(() ->
                        new EvaluateException("Right operand must be BOOLEAN for AND", Optional.of(ast.right()))
                );
                return new RuntimeValue.Primitive(leftBool && rightBool);
            } else { // "OR"
                if (leftBool) {
                    // short-circuit: return leftRv directly (don’t wrap new Primitive)
                    return leftRv;
                }
                RuntimeValue rightRv = visit(ast.right());
                Boolean rightBool = requireType(rightRv, Boolean.class).orElseThrow(() ->
                        new EvaluateException("Right operand must be BOOLEAN for OR", Optional.of(ast.right()))
                );
                return new RuntimeValue.Primitive(rightBool);
            }
        }


        RuntimeValue left = visit(ast.left());

// For arithmetic, short-circuit before evaluating right if left is invalid
        if ("-".equals(op) || "*".equals(op) || "/".equals(op)) {
            boolean leftIsValid =
                    requireType(left, java.math.BigInteger.class).isPresent() ||
                            requireType(left, java.math.BigDecimal.class).isPresent() ||
                            ("+".equals(op) && requireType(left, String.class).isPresent());

            if (!leftIsValid) {
                throw new EvaluateException("Invalid operand types for " + op, Optional.of(ast.left()));
            }
        }


        // only evaluate right once left is valid
        RuntimeValue right = visit(ast.right());


        // Helper lambdas to unwrap primitives
        java.util.function.Function<RuntimeValue, Object> raw = rv ->
                (rv instanceof RuntimeValue.Primitive p) ? p.value() : rv;

        // --- Plus: string concat OR numeric add ---
        if ("+".equals(op)) {
            boolean lIsStr = requireType(left, String.class).isPresent();
            boolean rIsStr = requireType(right, String.class).isPresent();

            if (lIsStr || rIsStr) {
                return new RuntimeValue.Primitive(left.print() + right.print());
            }

            var lInt = requireType(left, BigInteger.class);
            var rInt = requireType(right, BigInteger.class);
            if (lInt.isPresent() && rInt.isPresent()) {
                return new RuntimeValue.Primitive(lInt.get().add(rInt.get()));
            }

            var lDec = requireType(left, java.math.BigDecimal.class);
            var rDec = requireType(right, java.math.BigDecimal.class);
            if (lDec.isPresent() && rDec.isPresent()) {
                return new RuntimeValue.Primitive(lDec.get().add(rDec.get()));
            }

            // if types invalid → attach the left literal
            throw new EvaluateException("Invalid operand types for +", Optional.of(ast.left()));
        }


        // --- -, *, / : numeric only (both BigInteger or both BigDecimal) ---
        if ("-".equals(op) || "*".equals(op) || "/".equals(op)) {
            var lInt = requireType(left, java.math.BigInteger.class);
            var rInt = requireType(right, java.math.BigInteger.class);
            if (lInt.isPresent() && rInt.isPresent()) {
                var a = lInt.get();
                var b = rInt.get();
                return switch (op) {
                    case "-" -> new RuntimeValue.Primitive(a.subtract(b));
                    case "*" -> new RuntimeValue.Primitive(a.multiply(b));
                    case "/" -> {
                        if (java.math.BigInteger.ZERO.equals(b)) {
                            throw new EvaluateException("Division by zero.", java.util.Optional.of(ast.right()));
                        }
                        // Integer division like Java/BigInteger.
                        yield new RuntimeValue.Primitive(a.divide(b));
                    }
                    default -> throw new IllegalStateException();
                };
            }

            var lDec = requireType(left, java.math.BigDecimal.class);
            var rDec = requireType(right, java.math.BigDecimal.class);
            if (lDec.isPresent() && rDec.isPresent()) {
                var a = lDec.get();
                var b = rDec.get();
                return switch (op) {
                    case "-" -> new RuntimeValue.Primitive(a.subtract(b));
                    case "*" -> new RuntimeValue.Primitive(a.multiply(b));
                    case "/" -> {
                        if (b.compareTo(java.math.BigDecimal.ZERO) == 0) {
                            throw new EvaluateException("Division by zero.", java.util.Optional.of(ast.right()));
                        }
                        yield new RuntimeValue.Primitive(a.divide(b, java.math.RoundingMode.HALF_EVEN));
                    }
                    default -> throw new IllegalStateException();
                };
            }

            if (ast.left() instanceof Ast.Expr.Literal) {
                throw new EvaluateException("Invalid operand types for " + op, java.util.Optional.of(ast));
            } else if (ast.right() instanceof Ast.Expr.Literal) {
                throw new EvaluateException("Invalid operand types for " + op, java.util.Optional.of(ast));
            } else {
                throw new EvaluateException("Invalid operand types for " + op, java.util.Optional.of(ast));
            }

        }

        // --- Equality: Objects.equals on the underlying values ---
        if ("==".equals(op) || "!=".equals(op)) {
            Object lObj = raw.apply(left);
            Object rObj = raw.apply(right);
            boolean eq = java.util.Objects.equals(lObj, rObj);
            return new RuntimeValue.Primitive("==".equals(op) ? eq : !eq);
        }

        // --- Relational: same type & Comparable ---
        if ("<".equals(op) || "<=".equals(op) || ">".equals(op) || ">=".equals(op)) {
            Object lObj = raw.apply(left);
            Object rObj = raw.apply(right);
            if (lObj == null || rObj == null ||
                    !lObj.getClass().equals(rObj.getClass()) ||
                    !(lObj instanceof Comparable<?>)) {
                throw new EvaluateException("Invalid operand types for " + op, java.util.Optional.of(ast));
            }
            @SuppressWarnings({"unchecked","rawtypes"})
            int cmp = ((Comparable) lObj).compareTo(rObj);

            return switch (op) {
                case "<"  -> new RuntimeValue.Primitive(cmp < 0);
                case "<=" -> new RuntimeValue.Primitive(cmp <= 0);
                case ">"  -> new RuntimeValue.Primitive(cmp > 0);
                case ">=" -> new RuntimeValue.Primitive(cmp >= 0);
                default   -> throw new IllegalStateException();
            };
        }

        throw new EvaluateException("Unknown operator: " + op, java.util.Optional.of(ast));
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        // Try to find the variable/function in any visible scope (current + parents).
        return scope.resolve(ast.name(), false)
                .orElseThrow(() ->
                        new EvaluateException("Undefined variable: " + ast.name(), java.util.Optional.of(ast))
                );
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        // Evaluate the receiver and require it to be an object
        var recv = visit(ast.receiver());
        var obj  = requireType(recv, RuntimeValue.ObjectValue.class)
                .orElseThrow(() -> new EvaluateException("Property receiver must be an object.",
                        java.util.Optional.of(ast)));

        // Resolve property name in object or its prototype chain
        return resolveProperty(obj, ast.name(), java.util.Optional.of(ast));
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
        RuntimeValue callee = scope.resolve(ast.name(), false)
                .orElseThrow(() -> new EvaluateException("Undefined function: " + ast.name(),
                        java.util.Optional.of(ast)));

        RuntimeValue.Function fn = requireType(callee, RuntimeValue.Function.class).orElseThrow(() ->
                new EvaluateException("Not a function: " + ast.name(), java.util.Optional.of(ast)));

        // Evaluate args left-to-right
        java.util.List<RuntimeValue> args = new java.util.ArrayList<>(ast.arguments().size());
        for (Ast.Expr e : ast.arguments()) {
            args.add(visit(e));
        }

        try {
            return fn.definition().invoke(args);
        } catch (EvaluateException e) {
            // If the function's own arity/type checks threw without an AST, pin it on THIS call
            if (e.getAst().isEmpty()) {
                throw new EvaluateException(e.getMessage(), java.util.Optional.of(ast));
            }
            throw e;
        }
    }



    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        // 1) Receiver must be an object
        var recvRv = visit(ast.receiver());
        var obj    = requireType(recvRv, RuntimeValue.ObjectValue.class)
                .orElseThrow(() -> new EvaluateException("Method receiver must be an object.",
                        java.util.Optional.of(ast)));

        // 2) Lookup method (same rules as property), but must be a Function
        var value = resolveProperty(obj, ast.name(), java.util.Optional.of(ast));
        var fn    = requireType(value, RuntimeValue.Function.class)
                .orElseThrow(() -> new EvaluateException("Property '" + ast.name() + "' is not a method.",
                        java.util.Optional.of(ast)));

        // 3) Evaluate call arguments left-to-right
        var args = new java.util.ArrayList<RuntimeValue>(ast.arguments().size() + 1);
        args.add(obj); // per spec: pass the object first
        for (var a : ast.arguments()) {
            args.add(visit(a));
        }

        // 4) Invoke and return
        return fn.definition().invoke(args);
    }


    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        // 1) Create an empty object with its own scope
        var object = new RuntimeValue.ObjectValue(ast.name(), new Scope(null));

        // === Define fields (like LET semantics) ===
        for (Ast.Stmt.Let field : ast.fields()) {
            // name must not already exist in object scope
            if (object.scope().resolve(field.name(), /*current=*/true).isPresent()) {
                throw new EvaluateException("Duplicate field in object: " + field.name(),
                        java.util.Optional.of(field));
            }
            // value is evaluated in the CURRENT evaluator scope
            RuntimeValue value = field.value().isPresent() ? visit(field.value().get()) : NIL();
            object.scope().define(field.name(), value);
        }

        // === Define methods (like DEF semantics, with 'this') ===
        for (Ast.Stmt.Def method : ast.methods()) {
            // 'this' cannot be a parameter name
            if (method.parameters().stream().anyMatch("this"::equals)) {
                throw new EvaluateException("'this' cannot be a method parameter.",
                        java.util.Optional.of(method));
            }
            // name must not already exist in object scope
            if (object.scope().resolve(method.name(), /*current=*/true).isPresent()) {
                throw new EvaluateException("Duplicate method in object: " + method.name(),
                        java.util.Optional.of(method));
            }

            // Capture the defining scope for static scoping (spec)
            Scope definingScope = this.scope;

            // Build the function; when called, it will receive the receiver as arg 0
            RuntimeValue.Function fn = new RuntimeValue.Function(method.name(), args -> {
                // Expect 1 (this) + |parameters|
                if (args.size() != 1 + method.parameters().size()) {
                    throw new EvaluateException("Argument count mismatch in method " + method.name(),
                            java.util.Optional.empty());
                }

                // Create a new scope rooted at the defining scope (static scoping)
                Scope saved = this.scope;
                this.scope  = new Scope(definingScope);
                try {
                    // Bind implicit 'this'
                    this.scope.define("this", args.get(0));

                    // Bind explicit parameters
                    for (int i = 0; i < method.parameters().size(); i++) {
                        this.scope.define(method.parameters().get(i), args.get(i + 1));
                    }

                    // Evaluate body in another new scope (allows shadowing)
                    RuntimeValue last = NIL();
                    Scope bodySaved = this.scope;
                    this.scope = new Scope(bodySaved);
                    try {
                        for (Ast.Stmt stmt : method.body()) {
                            visit(stmt);
                        }
                    } catch (ReturnValue rv) {
                        return rv.value;
                    } finally {
                        this.scope = bodySaved;
                    }
                    return new RuntimeValue.Primitive(null);
                } finally {
                    this.scope = saved;
                }
            });

            // Install the method into the object's scope
            object.scope().define(method.name(), fn);
        }

        // 3) Return the new object
        return object;
    }


    /**
     * Helper function for extracting RuntimeValues of specific types. If type
     * is a subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value must be a {@link RuntimeValue.Primitive} and
     * the check applies to the primitive value.
     */
    static <T> Optional<T> requireType(RuntimeValue value, Class<T> type) {
        //To be discussed in lecture
        Optional<Object> unwrapped = RuntimeValue.class.isAssignableFrom(type)
            ? Optional.of(value)
            : requireType(value, RuntimeValue.Primitive.class).map(RuntimeValue.Primitive::value);
        return (Optional<T>) unwrapped.filter(type::isInstance); //cast checked by isInstance
    }

    /**


     HELPER FUNTIONS!

     */

    // A convenient NIL constructor.
    private static RuntimeValue NIL() {
        return new RuntimeValue.Primitive(null);
    }

    private RuntimeValue evalBlockInNewScope(java.util.List<Ast.Stmt> stmts) throws EvaluateException {
        var saved = this.scope;
        try {
            this.scope = new Scope(saved);               // new child scope
            RuntimeValue last = NIL();
            for (var s : stmts) {
                last = visit(s);
            }
            return last;
        } finally {
            this.scope = saved;                          // IMPORTANT: always restore scope appearantly
        }
    }

    private RuntimeValue resolveProperty(RuntimeValue.ObjectValue object, String name, Optional<Ast> at)
            throws EvaluateException {
        // 1) In this object's own scope?
        var here = object.scope().resolve(name, /*current=*/true);
        if (here.isPresent()) return here.get();

        // 2) Follow prototype chain: look for a field literally named "prototype"
        var proto = object.scope().resolve("prototype", /*current=*/true);
        if (proto.isPresent()) {
            var protoObj = requireType(proto.get(), RuntimeValue.ObjectValue.class)
                    .orElseThrow(() -> new EvaluateException(
                            "prototype is not an object.", at));
            return resolveProperty(protoObj, name, at); // recursive search
        }

        throw new EvaluateException("Undefined property: " + name, at);
    }


    private static class ReturnValue extends RuntimeException {
        final RuntimeValue value;
        ReturnValue(RuntimeValue value) {
            super(null, null, false, false); // no stack trace
            this.value = value;
        }
    }


}
