package plc.project.evaluator;

import java.util.List;
import java.util.Optional;

public final class Environment {

    public static Scope scope() {
        var scope = new Scope(null);
        //"Native" functions for printing and creating lists.
        scope.define("debug", new RuntimeValue.Function("debug", Environment::debug));
        scope.define("print", new RuntimeValue.Function("print", Environment::print));
        scope.define("log", new RuntimeValue.Function("log", Environment::log));
        scope.define("list", new RuntimeValue.Function("list", Environment::list));
        scope.define("range", new RuntimeValue.Function("range", Environment::range));
        //Helper functions for testing variables, functions, and objects.
        scope.define("variable", new RuntimeValue.Primitive("variable"));
        scope.define("function", new RuntimeValue.Function("function", Environment::function));
        var prototype = new RuntimeValue.ObjectValue(Optional.of("Prototype"), new Scope(null));
        prototype.scope().define("inherited_property", new RuntimeValue.Primitive("inherited_property"));
        prototype.scope().define("inherited_method", new RuntimeValue.Function("inherited_method", Environment::method));
        var object = new RuntimeValue.ObjectValue(Optional.of("Object"), new Scope(null));
        scope.define("object", object);
        object.scope().define("prototype", prototype);
        object.scope().define("property", new RuntimeValue.Primitive("property"));
        object.scope().define("method", new RuntimeValue.Function("method", Environment::method));
        return scope;
    }

    /**
     * Prints the raw RuntimeValue.toString() result.
     */
    private static RuntimeValue debug(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected debug to be called with 1 argument.", Optional.empty());
        }
        System.out.println(arguments.getFirst());
        return new RuntimeValue.Primitive(null);
    }

    /**
     * Prints a formatted RuntimeValue.
     */
    public static RuntimeValue print(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected print to be called with 1 argument.", Optional.empty());
        }
        System.out.println(arguments.getFirst().print());
        return new RuntimeValue.Primitive(null);
    }

    /**
     * Logs a formatted RuntimeValue and returns it.
     */
    static RuntimeValue log(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected log to be called with 1 argument.", Optional.empty());
        }
        System.out.println("log: " + arguments.getFirst().print());
        return arguments.getFirst();
    }

    /**
     * Returns a List value containing all arguments.
     */
    private static RuntimeValue list(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments);
    }

    /**
     * Takes two integer arguments (start, end) and returns a List containing
     * all integers in that range (inclusive, exclusive).
     */
    private static RuntimeValue range(java.util.List<RuntimeValue> arguments) throws EvaluateException {
        int argCount = arguments.size();
        if (argCount < 1 || argCount > 3) {
            throw new EvaluateException("range() takes 1 to 3 integer arguments.", java.util.Optional.empty());
        }

        java.math.BigInteger start, stop, step;

        if (argCount == 1) {
            start = java.math.BigInteger.ZERO;
            stop  = requireType(arguments.get(0), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(n): n must be an integer.", java.util.Optional.empty()));
            step  = java.math.BigInteger.ONE;
        } else if (argCount == 2) {
            start = requireType(arguments.get(0), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(start, stop): start must be integer.", java.util.Optional.empty()));
            stop  = requireType(arguments.get(1), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(start, stop): stop must be integer.", java.util.Optional.empty()));
            step  = java.math.BigInteger.ONE;
        } else {
            start = requireType(arguments.get(0), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(start, stop, step): start must be integer.", java.util.Optional.empty()));
            stop  = requireType(arguments.get(1), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(start, stop, step): stop must be integer.", java.util.Optional.empty()));
            step  = requireType(arguments.get(2), java.math.BigInteger.class)
                    .orElseThrow(() -> new EvaluateException("range(start, stop, step): step must be integer.", java.util.Optional.empty()));

            if (java.math.BigInteger.ZERO.equals(step)) {
                throw new EvaluateException("range() step cannot be zero.", java.util.Optional.empty());
            }
        }

        java.util.List<RuntimeValue> result = new java.util.ArrayList<>();

        java.math.BigInteger i = start;
        // positive step → increasing; negative step → decreasing
        while ((step.signum() > 0 && i.compareTo(stop) < 0) ||
                (step.signum() < 0 && i.compareTo(stop) > 0)) {
            result.add(new RuntimeValue.Primitive(i));
            i = i.add(step);
        }

        // Wrap the Java list in a Primitive (the runtime convention)
        return new RuntimeValue.Primitive(result);
    }


    /**
     * Returns a list of all function arguments.
     */
    private static RuntimeValue function(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments);
    }

    /**
     * Returns a list of all method arguments. Question: why the difference?
     */
    private static RuntimeValue method(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments.subList(1, arguments.size()));
    }

    @SuppressWarnings("unchecked")
    private static <T> java.util.Optional<T> requireType(RuntimeValue value, Class<T> type) {
        if (value instanceof RuntimeValue.Primitive primitive && type.isInstance(primitive.value())) {
            return java.util.Optional.of((T) primitive.value());
        }
        return java.util.Optional.empty();
    }

}
