package analysis;

import java.util.*;

import notquitejava.ast.*;

/**
 * Name table for analysis class hierarchies.
 */
public class NameTable {
    private final Map<Type, ArrayType> arrayTypes = new HashMap<>();

    private final Map<String, Type> classTypes = new HashMap<>();
    private final Map<String, NQJClassDecl> globalClasses = new HashMap<>();

    private final Map<String, NQJFunctionDecl> globalFunctions = new HashMap<>();


    NameTable(Analysis analysis, NQJProgram prog) {
        globalFunctions.put("printInt", NQJ.FunctionDecl(NQJ.TypeInt(), "main",
                NQJ.VarDeclList(NQJ.VarDecl(NQJ.TypeInt(), "elem")), NQJ.Block()));
        for (NQJFunctionDecl f : prog.getFunctionDecls()) {
            var old = globalFunctions.put(f.getName(), f);
            if (old != null) {
                analysis.addError(f, "There already is a global function with name " + f.getName()
                        + " defined in " + old.getSourcePosition());
            }
        }
        var graph = new HashMap<String, String>();
        /*
        * build the graph of subtype relation, and
        * create "ClassType" instances for classes without superclasses
        */
        for (NQJClassDecl c : prog.getClassDecls()) {
            var old = globalClasses.put(c.getName(), c);
            if (old != null) {
                analysis.addError(c, "There already is a class with name " + c.getName()
                        + " defined in " + old.getSourcePosition());
            }
            if (c.getExtended() instanceof NQJExtendsClass) {
                graph.put(c.getName(), ((NQJExtendsClass) c.getExtended()).getName());
            } else {
                classTypes.put(c.getName(),
                        new ClassType(analysis, globalClasses.get(c.getName()))
                );
            }
        }
        //  create "ClassType" instances for classes with superclasses
        for (var node : graph.keySet()) {
            var current = globalClasses.get(node);
            /* true if a cyclic inheritance is found */
            if (isInvolvedInCycle(node, graph)) {
                // "ANY" because we want to ignore all the statements, which use this class
                classTypes.put(node, Type.ANY);
                analysis.addError(current, "Cyclic inheritance involving " + node + ".");
            } else {
                var extendedName = ((NQJExtendsClass) current.getExtended()).getName();
                var extendedClass = globalClasses.get(extendedName);
                if (extendedClass == null) {
                    analysis.addError(current, "Class " + extendedName + " is not defined.");
                }
                current.setDirectSuperClass(extendedClass);
                classTypes.put(node, new ClassType(analysis, globalClasses.get(node)));
            }
        }
    }

    private boolean isInvolvedInCycle(String start, HashMap<String, String> graph) {
        var current = start;
        var path = new ArrayList<String>();
        while (current != null) {
            current = graph.get(current);
            if (current == null) {
                return false; // no superclass, hence no cycle detected
            } else if (current.equals(start)) {
                return true; // cycle detected which involves the "start" class
            } else if (path.contains(current)) {
                return false; // cycle detected but doesn't involves the "start" class
            }
            path.add(current);

        }
        return false;
    }

    public NQJFunctionDecl lookupFunction(String functionName) {
        return globalFunctions.get(functionName);
    }

    /**
     * Transform base type to array type.
     */
    public ArrayType getArrayType(Type baseType) {
        if (!arrayTypes.containsKey(baseType)) {
            arrayTypes.put(baseType, new ArrayType(baseType));
        }
        return arrayTypes.get(baseType);
    }

    /**
     * get class type by identifier.
     */
    public Type getClassType(String name) {
        return classTypes.get(name);
    }


}
