package analysis;

import java.util.*;

import notquitejava.ast.*;


/**
 * Analysis visitor to handle most of the type rules specified to NQJ.
 */
public class Analysis extends NQJElement.DefaultVisitor {

    private final NQJProgram prog;
    private final List<TypeError> typeErrors = new ArrayList<>();
    private NameTable nameTable;
    private final LinkedList<TypeContext> ctxt = new LinkedList<>();

    public void addError(NQJElement element, String message) {
        typeErrors.add(new TypeError(element, message));
    }

    public Analysis(NQJProgram prog) {
        this.prog = prog;
    }

    /**
     * Checks the saves NQJProgram for type errors.
     * Main entry point for type checking.
     */
    public void check() {
        nameTable = new NameTable(this, prog);

        verifyMainMethod();

        prog.accept(this);
    }

    private void verifyMainMethod() {
        var main = nameTable.lookupFunction("main");
        if (main == null) {
            typeErrors.add(new TypeError(prog, "Method int main() must be present"));
            return;
        }
        if (!(main.getReturnType() instanceof NQJTypeInt)) {
            typeErrors.add(new TypeError(main.getReturnType(),
                    "Return type of the main method must be int"));
        }
        if (!(main.getFormalParameters().isEmpty())) {
            typeErrors.add(new TypeError(main.getFormalParameters(),
                    "Main method does not take parameters"));
        }
        // Check if return statement is there as the last statement
        NQJStatement last = null;
        for (NQJStatement nqjStatement : main.getMethodBody()) {
            last = nqjStatement;
        }
        // TODO this forces the main method to have a single return at the end
        //      instead check for all possible paths to end in a return statement
        if (!(last instanceof NQJStmtReturn)) {
            typeErrors.add(new TypeError(main.getFormalParameters(),
                    "Main method does not have a return statement as the last statement"));
        }
    }

    public LinkedList<TypeContext> getCtxt() {
        return ctxt;
    }

    @Override
    public void visit(NQJFunctionDecl m) {
        // parameter names are unique, build context
        TypeContext mctxt = this.ctxt.isEmpty()
                ? new TypeContextImpl(null, Type.INVALID)
                : this.ctxt.peek().copy();
        Set<String> paramNames = new HashSet<>();
        for (NQJVarDecl v : m.getFormalParameters()) {
            if (!paramNames.add(v.getName())) {
                addError(m, "Parameter with name " + v.getName() + " already exists.");
            }
            mctxt.putVar(v.getName(), type(v.getType()), v);
        }
        mctxt.setReturnType(type(m.getReturnType()));
        // enter method context
        ctxt.push(mctxt);

        m.getMethodBody().accept(this);
        // exit method context
        ctxt.pop();
    }

    @Override
    public void visit(NQJStmtReturn stmtReturn) {
        Type actualReturn = checkExpr(ctxt.peek(), stmtReturn.getResult());
        Type expectedReturn = ctxt.peek().getReturnType();
        if (!actualReturn.isSubtypeOf(expectedReturn)) {
            addError(stmtReturn, "Should return value of type " + expectedReturn
                    + ", but found " + actualReturn + ".");
        }
    }

    @Override
    public void visit(NQJStmtAssign stmtAssign) {
        Type lt = checkExpr(ctxt.peek(), stmtAssign.getAddress());
        Type rt = checkExpr(ctxt.peek(), stmtAssign.getValue());
        if (!rt.isSubtypeOf(lt)) {
            addError(stmtAssign.getValue(), "Cannot assign value of type " + rt
                    + " to " + lt + ".");
        }
    }

    @Override
    public void visit(NQJStmtExpr stmtExpr) {
        checkExpr(ctxt.peek(), stmtExpr.getExpr());
    }

    @Override
    public void visit(NQJStmtWhile stmtWhile) {
        Type ct = checkExpr(ctxt.peek(), stmtWhile.getCondition());
        if (!ct.isSubtypeOf(Type.BOOL)) {
            addError(stmtWhile.getCondition(),
                    "Condition of while-statement must be of type boolean, but this is of type "
                            + ct + ".");
        }
        super.visit(stmtWhile);
    }

    @Override
    public void visit(NQJStmtIf stmtIf) {
        Type ct = checkExpr(ctxt.peek(), stmtIf.getCondition());
        if (!ct.isSubtypeOf(Type.BOOL)) {
            addError(stmtIf.getCondition(),
                    "Condition of if-statement must be of type boolean, but this is of type "
                            + ct + ".");
        }
        super.visit(stmtIf);
    }

    @Override
    public void visit(NQJBlock block) {
        TypeContext bctxt = this.ctxt.peek().copy();
        for (NQJStatement s : block) {
            // could also be integrated into the visitor run
            if (s instanceof NQJVarDecl) {
                NQJVarDecl varDecl = (NQJVarDecl) s;
                TypeContextImpl.VarRef ref = bctxt.lookupVar(varDecl.getName());
                if (ref != null) {
                    addError(varDecl, "A variable with name " + varDecl.getName()
                            + " is already defined.");
                }
                bctxt.putVar(varDecl.getName(), type(varDecl.getType()), varDecl);
            } else {
                // enter block context
                ctxt.push(bctxt);
                s.accept(this);
                // exit block context
                ctxt.pop();
            }
        }
    }

    @Override
    public void visit(NQJVarDecl varDecl) {
        throw new RuntimeException(); // var decls already handled by NQJBlock and NQJFunctionDecl
    }

    @Override
    public void visit(NQJClassDecl classDecl) {
        var classType = nameTable.getClassType(classDecl.getName());
        // only classes, which aren't affected by cyclic inheritance, are visited
        if (classType instanceof ClassType) {
            TypeContext mctxt = this.ctxt.isEmpty()
                    ? new TypeContextImpl(Type.INVALID, null)
                    : this.ctxt.peek().copy();
            // set matched type to NQJ node type
            for (NQJVarDecl v : ((ClassType) classType).getFields()) {
                type(v.getType());
            }
            mctxt.setThisType(classType);
            // enter class context
            ctxt.push(mctxt);
            for (var m : classDecl.getMethods()) {
                m.accept(this);
            }
            // exit class context
            ctxt.pop();
        }
    }

    public Type checkExpr(TypeContext ctxt, NQJExpr e) {
        return e.match(new ExprChecker(this, ctxt));
    }

    public Type checkExpr(TypeContext ctxt, NQJExprL e) {
        return e.match(new ExprChecker(this, ctxt));
    }

    /**
     * NQJ AST element to Type converter.
     */
    public Type type(NQJType type) {
        Type result = type.match(new NQJType.Matcher<>() {


            @Override
            public Type case_TypeBool(NQJTypeBool typeBool) {
                return Type.BOOL;
            }

            @Override
            public Type case_TypeClass(NQJTypeClass typeClass) {
                return nameTable.getClassType(typeClass.getName());
            }

            @Override
            public Type case_TypeArray(NQJTypeArray typeArray) {
                return nameTable.getArrayType(type(typeArray.getComponentType()));
            }

            @Override
            public Type case_TypeInt(NQJTypeInt typeInt) {
                return Type.INT;
            }

        });

        type.setType(result);
        return result;
    }

    public NameTable getNameTable() {
        return nameTable;
    }

    public List<TypeError> getTypeErrors() {
        return new ArrayList<>(typeErrors);
    }
}
