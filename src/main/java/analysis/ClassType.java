package analysis;

import notquitejava.ast.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Class extension for types.
 */
public class ClassType extends Type {
    private String name;
    private Analysis analysis;
    private String extendedName;
    private NQJClassDecl classDecl;
    private Map<String, NQJVarDecl> fields = new HashMap<String, NQJVarDecl>();
    private Map<String, NQJFunctionDecl> methods = new HashMap<String, NQJFunctionDecl>();

    /**
     * Class constructor specifying class declaration to translate.
     */
    public ClassType(Analysis analysis, NQJClassDecl classDecl) {
        this.analysis = analysis;
        this.name = classDecl.getName();
        if (classDecl.getExtended() instanceof NQJExtendsClass) {
            extendedName = ((NQJExtendsClass) classDecl.getExtended()).getName();
        }
        this.classDecl = classDecl;
    }

    @Override
    boolean isSubtypeOf(Type other) {
        if (other instanceof ClassType) {
            ClassType ct = (ClassType) other;
            /* true if they are of same type or if "ct" is a subtype of superclass  */
            return getName().equals(ct.getName())
                    || (getExtendedType() != null && getExtendedType().isSubtypeOf(ct));
        }
        return other == ANY;
    }

    @Override
    public String toString() {
        return name;
    }

    public String getName() {
        return name;
    }

    public Type getExtendedType() {
        return extendedName == null ? null : analysis.getNameTable().getClassType(extendedName);
    }

    public NQJVarDecl getField(String name) {
        this.load();
        return fields.get(name);
    }

    public Collection<NQJVarDecl> getFields() {
        this.load();
        return fields.values();
    }

    public NQJFunctionDecl getMethod(String name) {
        this.load();
        return methods.get(name);
    }

    public Collection<NQJFunctionDecl> getMethods() {
        this.load();
        return methods.values();
    }

    private boolean loaded = false;

    private void load() {
        if (loaded) {
            return;
        }
        this.loaded = true;
        var current = this.classDecl;
        boolean isNotSuperClass = true; // true if current is this.classDecl
        while (current != null) {
            if (!(analysis.getNameTable().getClassType(current.getName()) instanceof ClassType)) {
                break; // The class declaration is invalid, because of cyclic inheritance
            }
            for (int i = current.getFields().size() - 1; i >= 0; i--) {
                NQJVarDecl v = current.getFields().get(i);
                // check if the current class already contains a field with the same name
                if (isNotSuperClass && fields.containsKey(v.getName())) {
                    analysis.addError(v,
                            "Attribute with name " + v.getName() + " already exists.");
                }
                /*
                * override the attributes of superclass,
                * because they aren't important for type analysis
                */
                if (!(!isNotSuperClass && fields.containsKey(v.getName()))) {
                    fields.put(v.getName(), v);
                }
            }
            for (int i = current.getMethods().size() - 1; i >= 0; i--) {
                NQJFunctionDecl v = current.getMethods().get(i);
                // check if the current class already contains a method with the same name
                if (isNotSuperClass && methods.containsKey(v.getName())) {
                    analysis.addError(v, "Method with name " + v.getName() + " already exists.");
                }
                // check if a method with same name already exists in a subclass
                if (!isNotSuperClass && methods.containsKey(v.getName())) {
                    // check if the signatures match
                    if (!overridable(methods.get(v.getName()), v)) {
                        analysis.addError(methods.get(v.getName()),
                                "Signature is not compatible with "
                                        + "methods in superclasses with the same name");
                    }
                }
                if (!(!isNotSuperClass && methods.containsKey(v.getName()))) {
                    methods.put(v.getName(), v);
                }
            }
            isNotSuperClass = false;
            current = current.getDirectSuperClass();
        }
    }

    private boolean overridable(NQJFunctionDecl functionDecl1, NQJFunctionDecl functionDecl2) {
        if (!analysis.type(functionDecl1.getReturnType())
                .isSubtypeOf(analysis.type(functionDecl2.getReturnType()))) {
            return false;
        }
        if (functionDecl1.getFormalParameters().size()
                != functionDecl2.getFormalParameters().size()) {
            return false;
        }
        for (int i = 0; i < functionDecl1.getFormalParameters().size(); i++) {
            if (!analysis.type(functionDecl1.getFormalParameters().get(i).getType())
                    .isEqualToType(analysis.type(
                            functionDecl2.getFormalParameters().get(i).getType())
                    )) {
                return false;
            }
        }
        return true;
    }
}

