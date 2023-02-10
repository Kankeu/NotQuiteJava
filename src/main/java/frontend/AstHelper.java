package frontend;

import java.util.List;
import notquitejava.ast.*;


/**
 * Helper methods to be used inside CUP grammar rules.
 */
public class AstHelper {
    /**
     * Parsing members of classes into a class declaration.
     */
    public static NQJClassDecl classDecl(String name, String ext, List<NQJMemberDecl> members) {
        NQJFunctionDeclList methods = NQJ.FunctionDeclList();
        NQJVarDeclList fields = NQJ.VarDeclList();
        NQJExtended extended;
        if (ext == null) {
            extended = NQJ.ExtendsNothing();
        } else {
            extended = NQJ.ExtendsClass(ext);
        }

        for (NQJMemberDecl member : members) {
            member.match(new NQJMemberDecl.MatcherVoid() {

                @Override
                public void case_FunctionDecl(NQJFunctionDecl methodDecl) {
                    methods.add(methodDecl.copy());
                }

                @Override
                public void case_VarDecl(NQJVarDecl varDecl) {
                    fields.add(varDecl.copy());
                }
            });
        }

        return NQJ.ClassDecl(name, extended, fields, methods);
    }

    /** Parsing top level delcaration into a program. */
    public static NQJProgram program(List<NQJTopLevelDecl> decls) {
        NQJFunctionDeclList functions = NQJ.FunctionDeclList();
        NQJClassDeclList classDecls = NQJ.ClassDeclList();

        for (NQJTopLevelDecl decl : decls) {
            decl.match(new NQJTopLevelDecl.MatcherVoid() {
                @Override
                public void case_FunctionDecl(NQJFunctionDecl functionDecl) {
                    functions.add(functionDecl.copy());
                }

                @Override
                public void case_ClassDecl(NQJClassDecl classDecl) {
                    classDecls.add(classDecl.copy());
                }
            });
        }

        return NQJ.Program(classDecls, functions);
    }

    /**
     * Create an array type out of a type and dimensions.
     */
    public static NQJType buildArrayType(NQJType t, int dimensions) {
        for (int i = 0; i < dimensions; i++) {
            t = NQJ.TypeArray(t);
        }
        return t;
    }

    /**
     * Create an array type out of a L expression and dimensions.
     */
    public static NQJType buildArrayType(NQJExprL e, int dimensions) {
        NQJType t;
        if (e instanceof NQJVarUse) {
            NQJVarUse vu = (NQJVarUse) e;
            t = NQJ.TypeClass(vu.getVarName());
        } else {
            t = NQJ.TypeClass("unknown type");
        }
        for (int i = 0; i < dimensions; i++) {
            t = NQJ.TypeArray(t);
        }
        return t;
    }

    /**
     * Creates an array out of the type, the size expression, and the dimensions.
     */
    public static NQJExpr newArray(NQJType t, NQJExpr size, int dimensions) {
        for (int i = 0; i < dimensions; i++) {
            t = NQJ.TypeArray(t);
        }
        return NQJ.NewArray(t, size);
    }
}

