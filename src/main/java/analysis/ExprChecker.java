package analysis;

import analysis.TypeContext.VarRef;
import notquitejava.ast.*;

/**
 * Matcher implementation for expressions returning a NQJ type.
 */
public class ExprChecker implements NQJExpr.Matcher<Type>, NQJExprL.Matcher<Type> {
    private final Analysis analysis;
    private final TypeContext ctxt;

    public ExprChecker(Analysis analysis, TypeContext ctxt) {
        this.analysis = analysis;
        this.ctxt = ctxt;
    }

    Type check(NQJExpr e) {
        return e.match(this);
    }

    Type check(NQJExprL e) {
        return e.match(this);
    }

    void expect(NQJExpr e, Type expected) {
        Type actual = check(e);
        if (!actual.isSubtypeOf(expected)) {
            analysis.addError(e, "Expected expression of type " + expected
                    + " but found " + actual + ".");
        }
    }

    Type expectArray(NQJExpr e) {
        Type actual = check(e);
        if (!(actual instanceof ArrayType)) {
            analysis.addError(e, "Expected expression of array type,  but found " + actual + ".");
            return Type.ANY;
        } else {
            return actual;
        }
    }

    @Override
    public Type case_ExprUnary(NQJExprUnary exprUnary) {
        Type t = check(exprUnary.getExpr());
        return exprUnary.getUnaryOperator().match(new NQJUnaryOperator.Matcher<Type>() {

            @Override
            public Type case_UnaryMinus(NQJUnaryMinus unaryMinus) {
                expect(exprUnary.getExpr(), Type.INT);
                return Type.INT;
            }

            @Override
            public Type case_Negate(NQJNegate negate) {
                expect(exprUnary.getExpr(), Type.BOOL);
                return Type.BOOL;
            }
        });
    }

    @Override
    public Type case_MethodCall(NQJMethodCall methodCall) {
        var classType = methodCall.getReceiver().match(this);

        if (Type.ANY == classType) {
            return Type.ANY; // Class invalid declared due to cyclic inheritance
        } else if (!(classType instanceof ClassType)) {
            analysis.addError(methodCall.getReceiver(),
                    "Expected an object for method call, but found " + classType);
            return Type.ANY;
        }
        var ct = (ClassType) classType;
        var method = ct.getMethod(methodCall.getMethodName());
        if (method == null) {
            analysis.addError(methodCall,
                    "Method " + methodCall.getMethodName()
                            + " not found on receiver " + ct.getName() + ".");
            return Type.ANY;
        }

        NQJExprList args = methodCall.getArguments();
        NQJVarDeclList params = method.getFormalParameters();
        if (args.size() < params.size()) {
            analysis.addError(methodCall, "Not enough arguments.");
        } else if (args.size() > params.size()) {
            analysis.addError(methodCall, "Too many arguments.");
        } else {
            for (int i = 0; i < params.size(); i++) {
                expect(args.get(i), analysis.type(params.get(i).getType()));
            }
        }

        methodCall.setFunctionDeclaration(method);
        return analysis.type(method.getReturnType());
    }


    @Override
    public Type case_ArrayLength(NQJArrayLength arrayLength) {
        expectArray(arrayLength.getArrayExpr());
        return Type.INT;
    }

    @Override
    public Type case_ExprThis(NQJExprThis exprThis) {
        //throw new IllegalStateException("NYI");
        var classType = analysis.getCtxt().peek().getThisType();
        if (classType == null) {
            analysis.addError(exprThis, "Variable this not found.");
            return Type.ANY;
        }
        return classType;
    }

    @Override
    public Type case_ExprBinary(NQJExprBinary exprBinary) {
        return exprBinary.getOperator().match(new NQJOperator.Matcher<>() {
            @Override
            public Type case_And(NQJAnd and) {
                expect(exprBinary.getLeft(), Type.BOOL);
                expect(exprBinary.getRight(), Type.BOOL);
                return Type.BOOL;
            }

            @Override
            public Type case_Times(NQJTimes times) {
                return case_intOperation();
            }

            @Override
            public Type case_Div(NQJDiv div) {
                return case_intOperation();
            }

            @Override
            public Type case_Plus(NQJPlus plus) {
                return case_intOperation();
            }

            @Override
            public Type case_Minus(NQJMinus minus) {
                return case_intOperation();
            }

            private Type case_intOperation() {
                expect(exprBinary.getLeft(), Type.INT);
                expect(exprBinary.getRight(), Type.INT);
                return Type.INT;
            }

            @Override
            public Type case_Equals(NQJEquals equals) {
                Type l = check(exprBinary.getLeft());
                Type r = check(exprBinary.getRight());
                if (!l.isSubtypeOf(r) && !r.isSubtypeOf(l)) {
                    analysis.addError(exprBinary, "Cannot compare types " + l + " and " + r + ".");
                }
                return Type.BOOL;
            }

            @Override
            public Type case_Less(NQJLess less) {
                expect(exprBinary.getLeft(), Type.INT);
                expect(exprBinary.getRight(), Type.INT);
                return Type.BOOL;
            }
        });
    }

    @Override
    public Type case_ExprNull(NQJExprNull exprNull) {
        return Type.NULL;
    }

    @Override
    public Type case_FunctionCall(NQJFunctionCall functionCall) {
        NQJFunctionDecl m = analysis.getNameTable().lookupFunction(functionCall.getMethodName());
        if (m == null) {
            analysis.addError(functionCall, "Function " + functionCall.getMethodName()
                    + " does not exists.");
            return Type.ANY;
        }
        NQJExprList args = functionCall.getArguments();
        NQJVarDeclList params = m.getFormalParameters();
        if (args.size() < params.size()) {
            analysis.addError(functionCall, "Not enough arguments.");
        } else if (args.size() > params.size()) {
            analysis.addError(functionCall, "Too many arguments.");
        } else {
            for (int i = 0; i < params.size(); i++) {
                expect(args.get(i), analysis.type(params.get(i).getType()));
            }
        }
        functionCall.setFunctionDeclaration(m);
        return analysis.type(m.getReturnType());
    }

    @Override
    public Type case_Number(NQJNumber number) {
        return Type.INT;
    }

    @Override
    public Type case_NewArray(NQJNewArray newArray) {
        expect(newArray.getArraySize(), Type.INT);
        ArrayType t = new ArrayType(analysis.type(newArray.getBaseType()));
        newArray.setArrayType(t);
        return t;
    }

    @Override
    public Type case_NewObject(NQJNewObject newObject) {
        var classType = analysis.getNameTable().getClassType(newObject.getClassName());
        if (classType == null) {
            analysis.addError(newObject, "Class " + newObject.getClassName() + " is not defined.");
            return Type.ANY;
        }
        return classType;
    }

    @Override
    public Type case_BoolConst(NQJBoolConst boolConst) {
        return Type.BOOL;
    }

    @Override
    public Type case_Read(NQJRead read) {
        return read.getAddress().match(this);
    }

    @Override
    public Type case_FieldAccess(NQJFieldAccess fieldAccess) {
        var classType = fieldAccess.getReceiver().match(this);

        if (Type.ANY == classType) {
            return Type.ANY; // Class invalid declared due to cyclic inheritance
        } else if (!(classType instanceof ClassType)) {
            analysis.addError(fieldAccess.getReceiver(),
                    "Expected an object for field access, but found " + classType);
            return Type.ANY;
        }
        var ct = (ClassType) classType;
        var field = ct.getField(fieldAccess.getFieldName());
        if (field == null) {
            analysis.addError(fieldAccess,
                    "Field " + fieldAccess.getFieldName()
                            + " not found on receiver " + ct.getName() + ".");
            return Type.ANY;
        }
        return analysis.type(field.getType());
    }

    @Override
    public Type case_VarUse(NQJVarUse varUse) {
        VarRef ref = ctxt.lookupVar(varUse.getVarName());
        if (ref == null) {
            var varDecl = ((ClassType) ctxt.getThisType()).getField(varUse.getVarName());
            ref = varDecl == null ? null : new VarRef(analysis.type(varDecl.getType()), varDecl);
        }

        if (ref == null) {
            analysis.addError(varUse, "Variable " + varUse.getVarName() + " is not defined.");
            return Type.ANY;
        }
        varUse.setVariableDeclaration(ref.decl);
        return ref.type;
    }

    @Override
    public Type case_ArrayLookup(NQJArrayLookup arrayLookup) {
        Type type = analysis.checkExpr(ctxt, arrayLookup.getArrayExpr());
        expect(arrayLookup.getArrayIndex(), Type.INT);
        if (type instanceof ArrayType) {
            ArrayType arrayType = (ArrayType) type;
            arrayLookup.setArrayType(arrayType);
            return arrayType.getBaseType();
        }
        analysis.addError(arrayLookup, "Expected an array for array-lookup, but found " + type);
        return Type.ANY;
    }
}
