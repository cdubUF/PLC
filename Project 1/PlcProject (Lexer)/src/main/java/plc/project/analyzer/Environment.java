package plc.project.analyzer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Environment {

    public static final Map<String, Type> TYPES = Stream.of(
        Type.ANY,
        Type.NIL,
        Type.DYNAMIC,
        Type.BOOLEAN,
        Type.INTEGER,
        Type.DECIMAL,
        Type.CHARACTER,
        Type.STRING,
        Type.EQUATABLE,
        Type.COMPARABLE,
        Type.ITERABLE
    ).collect(Collectors.toMap(Type.Primitive::name, t -> t));

    public static Scope scope() {
        var scope = new Scope(null);
        //Helper variables for testing non-literal types;
        scope.define("any", Type.ANY);
        scope.define("dynamic", Type.DYNAMIC);
        scope.define("equatable", Type.EQUATABLE);
        scope.define("comparable", Type.COMPARABLE);
        scope.define("iterable", Type.ITERABLE);
        //"Native" functions for printing and creating lists.
        scope.define("log", new Type.Function(List.of(Type.ANY), Type.DYNAMIC)); //note Dynamic!
        //"list" has been removed, since our type system can't represent is (why?)
        scope.define("debug", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("print", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("range", new Type.Function(List.of(Type.INTEGER, Type.INTEGER), Type.ITERABLE));
        //Helpers for testing variables, functions, and objects.
        scope.define("variable", Type.STRING);
        scope.define("function", new Type.Function(List.of(), Type.NIL));
        scope.define("functionAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        scope.define("functionString", new Type.Function(List.of(Type.STRING), Type.STRING));
        var prototype = new Type.ObjectType(Optional.of("Prototype"), new Scope(null));
        prototype.scope().define("inherited_property", Type.STRING);
        prototype.scope().define("inherited_method", new Type.Function(List.of(), Type.NIL));
        var object = new Type.ObjectType(Optional.of("Object"), new Scope(null));
        scope.define("object", object);
        object.scope().define("prototype", prototype);
        object.scope().define("method", new Type.Function(List.of(), Type.NIL));
        object.scope().define("methodAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        object.scope().define("methodString", new Type.Function(List.of(Type.STRING), Type.STRING));
        return scope;
    }

    public static boolean isSubtypeOf(Type subtype, Type supertype) {
        // 1) All types are subtypes of Any.
        if (supertype == Type.ANY) {
            return true;
        }

        // 2) All types are subtypes AND supertypes of Dynamic.
        if (subtype == Type.DYNAMIC || supertype == Type.DYNAMIC) {
            return true;
        }

        // 3) All types are subtypes of themselves.
        if (subtype.equals(supertype)) {
            return true;
        }

        // 4) Primitive subtype relationships.
        // Nil, Comparable, and Iterable are subtypes of Equatable.
        if (supertype == Type.EQUATABLE &&
                (subtype == Type.NIL || subtype == Type.COMPARABLE || subtype == Type.ITERABLE)) {
            return true;
        }

        // Boolean, Integer, Decimal, Character, and String are subtypes of Comparable.
        if (supertype == Type.COMPARABLE &&
                (subtype == Type.BOOLEAN ||
                        subtype == Type.INTEGER ||
                        subtype == Type.DECIMAL ||
                        subtype == Type.CHARACTER ||
                        subtype == Type.STRING)) {
            return true;
        }

        // 5) ObjectType prototype chaining (bonus / for object + prototype tests).
        if (subtype instanceof Type.ObjectType subObj && supertype instanceof Type.ObjectType superObj) {
            // Same "shape"/name already handled by equals earlier, so here we only consider prototypes.
            // Look for a "prototype" property on the subtype.
            var prototypeOpt = subObj.scope().get("prototype", false);
            if (prototypeOpt.isPresent()) {
                var protoType = prototypeOpt.get();

                // If the prototype itself is Dynamic, treat this object as a subtype of any other object,

                if (protoType == Type.DYNAMIC) {
                    return true;
                }

                // If the prototype is an ObjectType, check it recursively.
                if (protoType instanceof Type.ObjectType protoObj) {
                    return isSubtypeOf(protoObj, supertype);
                }
            }
        }

        // 6) Function types – only identical signatures are considered subtypes.
        if (subtype instanceof Type.Function subFn && supertype instanceof Type.Function superFn) {
            if (subFn.parameters().size() != superFn.parameters().size()) {
                return false;
            }
            for (int i = 0; i < subFn.parameters().size(); i++) {
                if (!subFn.parameters().get(i).equals(superFn.parameters().get(i))) {
                    return false;
                }
            }
            return subFn.returns().equals(superFn.returns());
        }

        // otherwise, no subtype relationship.
        return false;
    }


}
