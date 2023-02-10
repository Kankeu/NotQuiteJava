package translation;

import analysis.ArrayType;
import analysis.ClassType;
import minillvm.ast.*;
import notquitejava.ast.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static frontend.AstPrinter.print;
import static minillvm.ast.Ast.*;


/**
 * Entry class for the translation phase.
 */
public class Translator {

    private final StmtTranslator stmtTranslator = new StmtTranslator(this);
    private final ExprLValue exprLValue = new ExprLValue(this);
    private final ExprRValue exprRValue = new ExprRValue(this);
    private final Map<NQJFunctionDecl, Proc> functionImpl = new HashMap<>();
    private final Prog prog = Prog(TypeStructList(), GlobalList(), ProcList());
    private final NQJProgram javaProg;
    private final Map<NQJVarDecl, TemporaryVar> localVarLocation = new HashMap<>();
    private final Map<analysis.Type, Type> translatedType = new HashMap<>();
    private final Map<Type, TypeStruct> arrayStruct = new HashMap<>();
    private final Map<Type, Proc> newArrayFuncForType = new HashMap<>();

    // for oop
    private final HashMap<String, Proc> constructors = new HashMap<>();
    private final HashMap<String, TypeStruct> classStructs = new HashMap<>();
    private final HashMap<String, TypeStruct> vmtStructs = new HashMap<>();
    private final HashMap<String, Global> vmts = new HashMap<>();

    // mutable state
    private Proc currentProcedure;
    private BasicBlock currentBlock;

    public Translator(NQJProgram javaProg) {
        this.javaProg = javaProg;
    }

    /**
     * Translates given program into a mini llvm program.
     */
    public Prog translate() {
        // translate all classes
        // has only access to classes
        translateClassTypes();

        // translate functions except main
        // has only access to functions
        translateFunctions();

        // translate main function
        // has access to functions
        translateMainFunction();


        // translate all methods of classes
        // has only access to methods of classes
        translateMethods();

        finishNewArrayProcs();

        return prog;
    }

    TemporaryVar getLocalVarLocation(NQJVarDecl varDecl) {
        return localVarLocation.get(varDecl);
    }

    private void finishNewArrayProcs() {
        for (Type type : newArrayFuncForType.keySet()) {
            finishNewArrayProc(type);
        }
    }

    private void finishNewArrayProc(Type componentType) {
        final Proc newArrayFunc = newArrayFuncForType.get(componentType);
        final Parameter size = newArrayFunc.getParameters().get(0);

        addProcedure(newArrayFunc);
        setCurrentProc(newArrayFunc);

        BasicBlock init = newBasicBlock("init");
        addBasicBlock(init);
        setCurrentBlock(init);
        TemporaryVar sizeLessThanZero = TemporaryVar("sizeLessThanZero");
        addInstruction(BinaryOperation(sizeLessThanZero,
                VarRef(size), Slt(), ConstInt(0)));
        BasicBlock negativeSize = newBasicBlock("negativeSize");
        BasicBlock goodSize = newBasicBlock("goodSize");
        currentBlock.add(Branch(VarRef(sizeLessThanZero), negativeSize, goodSize));

        addBasicBlock(negativeSize);
        negativeSize.add(HaltWithError("Array Size must be positive"));

        addBasicBlock(goodSize);
        setCurrentBlock(goodSize);

        // allocate space for the array

        TemporaryVar arraySizeInBytes = TemporaryVar("arraySizeInBytes");
        addInstruction(BinaryOperation(arraySizeInBytes,
                VarRef(size), Mul(), byteSize(componentType)));

        // 4 bytes for the length
        TemporaryVar arraySizeWithLen = TemporaryVar("arraySizeWitLen");
        addInstruction(BinaryOperation(arraySizeWithLen,
                VarRef(arraySizeInBytes), Add(), ConstInt(4)));

        TemporaryVar mallocResult = TemporaryVar("mallocRes");
        addInstruction(Alloc(mallocResult, VarRef(arraySizeWithLen)));
        TemporaryVar newArray = TemporaryVar("newArray");
        addInstruction(Bitcast(newArray,
                getArrayPointerType(componentType), VarRef(mallocResult)));

        // store the size
        TemporaryVar sizeAddr = TemporaryVar("sizeAddr");
        addInstruction(GetElementPtr(sizeAddr,
                VarRef(newArray), OperandList(ConstInt(0), ConstInt(0))));
        addInstruction(Store(VarRef(sizeAddr), VarRef(size)));

        // initialize Array with zeros:
        final BasicBlock loopStart = newBasicBlock("loopStart");
        final BasicBlock loopBody = newBasicBlock("loopBody");
        final BasicBlock loopEnd = newBasicBlock("loopEnd");
        final TemporaryVar iVar = TemporaryVar("iVar");
        currentBlock.add(Alloca(iVar, TypeInt()));
        currentBlock.add(Store(VarRef(iVar), ConstInt(0)));
        currentBlock.add(Jump(loopStart));

        // loop condition: while i < size
        addBasicBlock(loopStart);
        setCurrentBlock(loopStart);
        final TemporaryVar i = TemporaryVar("i");
        final TemporaryVar nextI = TemporaryVar("nextI");
        loopStart.add(Load(i, VarRef(iVar)));
        TemporaryVar smallerSize = TemporaryVar("smallerSize");
        addInstruction(BinaryOperation(smallerSize,
                VarRef(i), Slt(), VarRef(size)));
        currentBlock.add(Branch(VarRef(smallerSize), loopBody, loopEnd));

        // loop body
        addBasicBlock(loopBody);
        setCurrentBlock(loopBody);
        // ar[i] = 0;
        final TemporaryVar iAddr = TemporaryVar("iAddr");
        addInstruction(GetElementPtr(iAddr,
                VarRef(newArray), OperandList(ConstInt(0), ConstInt(1), VarRef(i))));
        addInstruction(Store(VarRef(iAddr), defaultValue(componentType)));

        // nextI = i + 1;
        addInstruction(BinaryOperation(nextI, VarRef(i), Add(), ConstInt(1)));
        // store new value in i
        addInstruction(Store(VarRef(iVar), VarRef(nextI)));

        loopBody.add(Jump(loopStart));

        addBasicBlock(loopEnd);
        loopEnd.add(ReturnExpr(VarRef(newArray)));
    }

    private void translateFunctions() {
        for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
            if (functionDecl.getName().equals("main")) {
                continue;
            }
            initFunction(functionDecl);
        }
        for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
            if (functionDecl.getName().equals("main")) {
                continue;
            }
            translateFunction(functionDecl);
        }
    }

    private void translateMainFunction() {
        NQJFunctionDecl f = null;
        for (NQJFunctionDecl functionDecl : javaProg.getFunctionDecls()) {
            if (functionDecl.getName().equals("main")) {
                f = functionDecl;
                break;
            }
        }

        if (f == null) {
            throw new IllegalStateException("Main function expected");
        }

        Proc proc = Proc("main", TypeInt(), ParameterList(), BasicBlockList());
        addProcedure(proc);
        functionImpl.put(f, proc);

        setCurrentProc(proc);
        BasicBlock initBlock = newBasicBlock("init");
        addBasicBlock(initBlock);
        setCurrentBlock(initBlock);

        // allocate space for the local variables
        allocaLocalVars(f.getMethodBody());

        // translate
        translateStmt(f.getMethodBody());
    }

    private void initFunction(NQJFunctionDecl f) {
        Type returnType = translateType(f.getReturnType());
        ParameterList params = f.getFormalParameters()
                .stream()
                .map(p -> Parameter(translateType(p.getType()), p.getName()))
                .collect(Collectors.toCollection(Ast::ParameterList));
        Proc proc = Proc(f.getName(), returnType, params, BasicBlockList());
        addProcedure(proc);
        functionImpl.put(f, proc);
    }

    private void translateFunction(NQJFunctionDecl m) {
        Proc proc = functionImpl.get(m);
        setCurrentProc(proc);
        BasicBlock initBlock = newBasicBlock("init");
        addBasicBlock(initBlock);
        setCurrentBlock(initBlock);

        localVarLocation.clear();


        // store copies of the parameters in Allocas, to make uniform read/write access possible
        // if the translated function is a method, then the first parameter (this) is skipped
        int i = proc.getParameters().size() > 0
                && proc.getParameters().get(0).getName().equals("this") ? 1 : 0;
        for (NQJVarDecl param : m.getFormalParameters()) {
            TemporaryVar v = TemporaryVar(param.getName());
            addInstruction(Alloca(v, translateType(param.getType())));
            addInstruction(Store(VarRef(v), VarRef(proc.getParameters().get(i))));
            localVarLocation.put(param, v);
            i++;
        }

        // allocate space for the local variables
        allocaLocalVars(m.getMethodBody());

        translateStmt(m.getMethodBody());
    }

    void translateStmt(NQJStatement s) {
        addInstruction(CommentInstr(sourceLine(s) + " start statement : " + printFirstline(s)));
        s.match(stmtTranslator);
        addInstruction(CommentInstr(sourceLine(s) + " end statement: " + printFirstline(s)));
    }

    int sourceLine(NQJElement e) {
        while (e != null) {
            if (e.getSourcePosition() != null) {
                return e.getSourcePosition().getLine();
            }
            e = e.getParent();
        }
        return 0;
    }

    private String printFirstline(NQJStatement s) {
        String str = print(s);
        str = str.replaceAll("\n.*", "");
        return str;
    }

    BasicBlock newBasicBlock(String name) {
        BasicBlock block = BasicBlock();
        block.setName(name);
        return block;
    }

    void addBasicBlock(BasicBlock block) {
        currentProcedure.getBasicBlocks().add(block);
    }

    BasicBlock getCurrentBlock() {
        return currentBlock;
    }

    void setCurrentBlock(BasicBlock currentBlock) {
        this.currentBlock = currentBlock;
    }


    void addProcedure(Proc proc) {
        prog.getProcedures().add(proc);
    }

    void setCurrentProc(Proc currentProc) {
        if (currentProc == null) {
            throw new RuntimeException("Cannot set proc to null");
        }
        this.currentProcedure = currentProc;
    }

    private void allocaLocalVars(NQJBlock methodBody) {
        methodBody.accept(new NQJElement.DefaultVisitor() {
            @Override
            public void visit(NQJVarDecl localVar) {
                super.visit(localVar);
                TemporaryVar v = TemporaryVar(localVar.getName());
                addInstruction(Alloca(v, translateType(localVar.getType())));
                localVarLocation.put(localVar, v);
            }
        });
    }

    void addInstruction(Instruction instruction) {
        currentBlock.add(instruction);
    }

    Type translateType(NQJType type) {
        return translateType(type.getType());
    }

    Type translateType(analysis.Type t) {
        Type result = translatedType.get(t);
        if (result == null) {
            if (t == analysis.Type.INT) {
                result = TypeInt();
            } else if (t == analysis.Type.BOOL) {
                result = TypeBool();
            } else if (t instanceof ArrayType) {
                ArrayType at = (ArrayType) t;
                result = TypePointer(getArrayStruct(translateType(at.getBaseType())));
            } else if (t instanceof ClassType) {
                result = TypePointer(classStructs.get(((ClassType) t).getName()));
            } else {
                throw new RuntimeException("unhandled case " + t);
            }
            translatedType.put(t, result);
        }
        return result;
    }

    Parameter getThisParameter() {
        // in our case 'this' is always the first parameter
        return currentProcedure.getParameters().get(0);
    }

    Operand exprLvalue(NQJExprL e) {
        return e.match(exprLValue);
    }

    Operand exprRvalue(NQJExpr e) {
        return e.match(exprRValue);
    }

    void addNullcheck(Operand arrayAddr, String errorMessage) {
        TemporaryVar isNull = TemporaryVar("isNull");
        addInstruction(BinaryOperation(isNull, arrayAddr.copy(), Eq(), Nullpointer()));

        BasicBlock whenIsNull = newBasicBlock("whenIsNull");
        BasicBlock notNull = newBasicBlock("notNull");
        currentBlock.add(Branch(VarRef(isNull), whenIsNull, notNull));

        addBasicBlock(whenIsNull);
        whenIsNull.add(HaltWithError(errorMessage));

        addBasicBlock(notNull);
        setCurrentBlock(notNull);
    }

    Operand getArrayLen(Operand arrayAddr) {
        TemporaryVar addr = TemporaryVar("length_addr");
        addInstruction(GetElementPtr(addr,
                arrayAddr.copy(), OperandList(ConstInt(0), ConstInt(0))));
        TemporaryVar len = TemporaryVar("len");
        addInstruction(Load(len, VarRef(addr)));
        return VarRef(len);
    }

    public Operand getNewArrayFunc(Type componentType) {
        Proc proc = newArrayFuncForType.computeIfAbsent(componentType, this::createNewArrayProc);
        return ProcedureRef(proc);
    }

    private Proc createNewArrayProc(Type componentType) {
        Parameter size = Parameter(TypeInt(), "size");
        return Proc("newArray",
                getArrayPointerType(componentType), ParameterList(size), BasicBlockList());
    }

    private Type getArrayPointerType(Type componentType) {
        return TypePointer(getArrayStruct(componentType));
    }

    TypeStruct getArrayStruct(Type type) {
        return arrayStruct.computeIfAbsent(type, t -> {
            TypeStruct struct = TypeStruct("array_" + type, StructFieldList(
                    StructField(TypeInt(), "length"),
                    StructField(TypeArray(type, 0), "data")
            ));
            prog.getStructTypes().add(struct);
            return struct;
        });
    }

    Operand addCastIfNecessary(Operand value, Type expectedType) {
        if (expectedType.equalsType(value.calculateType())) {
            return value;
        }
        TemporaryVar castValue = TemporaryVar("castValue");
        addInstruction(Bitcast(castValue, expectedType, value));
        return VarRef(castValue);
    }

    BasicBlock unreachableBlock() {
        return BasicBlock();
    }

    Type getCurrentReturnType() {
        return currentProcedure.getReturnType();
    }

    public Proc loadFunctionProc(NQJFunctionDecl functionDeclaration) {
        return functionImpl.get(functionDeclaration);
    }

    /**
     * return the number of bytes required by the given type.
     */
    public Operand byteSize(Type type) {
        return type.match(new Type.Matcher<>() {
            @Override
            public Operand case_TypeByte(TypeByte typeByte) {
                return ConstInt(1);
            }

            @Override
            public Operand case_TypeArray(TypeArray typeArray) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeProc(TypeProc typeProc) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeInt(TypeInt typeInt) {
                return ConstInt(4);
            }

            @Override
            public Operand case_TypeStruct(TypeStruct typeStruct) {
                return Sizeof(typeStruct);
            }

            @Override
            public Operand case_TypeNullpointer(TypeNullpointer typeNullpointer) {
                return ConstInt(8);
            }

            @Override
            public Operand case_TypeVoid(TypeVoid typeVoid) {
                return ConstInt(0);
            }

            @Override
            public Operand case_TypeBool(TypeBool typeBool) {
                return ConstInt(1);
            }

            @Override
            public Operand case_TypePointer(TypePointer typePointer) {
                return ConstInt(8);
            }
        });
    }

    private Operand defaultValue(Type componentType) {
        return componentType.match(new Type.Matcher<>() {
            @Override
            public Operand case_TypeByte(TypeByte typeByte) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeArray(TypeArray typeArray) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeProc(TypeProc typeProc) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeInt(TypeInt typeInt) {
                return ConstInt(0);
            }

            @Override
            public Operand case_TypeStruct(TypeStruct typeStruct) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeNullpointer(TypeNullpointer typeNullpointer) {
                return Nullpointer();
            }

            @Override
            public Operand case_TypeVoid(TypeVoid typeVoid) {
                throw new RuntimeException("TODO implement");
            }

            @Override
            public Operand case_TypeBool(TypeBool typeBool) {
                return ConstBool(false);
            }

            @Override
            public Operand case_TypePointer(TypePointer typePointer) {
                return Nullpointer();
            }
        });
    }

    private void translateClassTypes() {
        // translate classes
        for (NQJClassDecl classDecl : javaProg.getClassDecls()) {
            translateClassType(classDecl);
        }
        // add fields to classes and methods to virtual method tables
        translateFieldsAndVirtualMethodTables();
    }

    private void translateClassType(NQJClassDecl classDecl) {
        TypeStruct vmtStruct = TypeStruct(classDecl.getName() + "_vmt", StructFieldList(

        ));
        vmtStructs.put(classDecl.getName(), vmtStruct);

        TypeStruct classStruct = TypeStruct(classDecl.getName(), StructFieldList(
                StructField(TypePointer(
                        vmtStruct
                ), "vmt")
        ));
        classStructs.put(classDecl.getName(), classStruct);


        prog.getStructTypes().add(vmtStruct);
        // start of the construction the virtual method table
        var global = Global(vmtStruct,
                classDecl.getName() + "_vmt",
                true, ConstStruct(vmtStruct, ConstList()));
        vmts.put(classDecl.getName(), global);
        prog.getGlobals().add(global);

        prog.getStructTypes().add(classStruct);
    }

    // add fields to class structures and add methods in virtual method table structures
    private void translateFieldsAndVirtualMethodTables() {
        for (var classDecl : javaProg.getClassDecls()) {
            var current = classDecl;
            var vmtStruct = vmtStructs.get(classDecl.getName());
            var classStruct = classStructs.get(classDecl.getName());
            var shouldOverrideMethods = new HashMap<String, Integer>();
            int i = 0;
            while (current != null) {
                // initialise methods and add them to the virtual method table structure
                for (var method : current.getMethods()) {
                    // override the current method if a method with the same name exists in subclass
                    if (shouldOverrideMethods.containsKey(method.getName())) {
                        var overrider = vmtStruct.getFields()
                                .remove(i - shouldOverrideMethods.get(method.getName()) - 1);
                        vmtStruct.getFields().add(0, overrider);
                        shouldOverrideMethods.put(method.getName(), i - 1);
                        continue;
                    }
                    shouldOverrideMethods.put(method.getName(), i);
                    i++;

                    // change add the class name behind the function name
                    String name = method.getName();
                    method.setName(current.getName() + "_" + name);
                    // initialize method if it's not already initialized
                    if (loadFunctionProc(method) == null) {
                        initFunction(method);
                        Proc proc = loadFunctionProc(method);
                        // pass reference to current object as first parameter
                        proc.getParameters().add(0,
                                Parameter(TypePointer(classStructs.get(current.getName())),
                                        "this"));
                    }
                    method.setName(name);

                    var args = TypeRefList();
                    // add a reference to the actual object as first parameter
                    args.add(TypePointer(classStructs.get(current.getName())));
                    // add the rest of parameters
                    for (var arg : method.getFormalParameters()) {
                        args.add(translateType(arg.getType()));
                    }
                    // add a reference to the method in virtual method table
                    vmtStruct.getFields().add(0, StructField(TypePointer(TypeProc(args,
                            translateType(method.getReturnType()))),
                            current.getName() + "_" + method.getName()));
                }
                // add fields to the class structure
                for (var field : current.getFields()) {
                    classStruct.getFields().add(1, StructField(translateType(field.getType()),
                            current.getName() + "_" + field.getName()));
                }
                // go to the superclass
                current = current.getDirectSuperClass();
            }
            createConstructor(classDecl);
        }
    }

    /**
     * find the index of the field in the class structure.
     *
     * @param classStruct class structure.
     * @param name        the name of the field.
     * @return the index of the field inside the class structure.
     */
    public Integer getFieldIndex(TypeStruct classStruct, String name) {
        name = "_" + name;
        Integer index = null;
        for (int i = classStruct.getFields().size() - 1; i >= 0; i--) {
            if (classStruct.getFields().get(i).getName().contains(name)) {
                index = i;
                break;
            }
        }
        return index;
    }

    /**
     * find the index of the method in the virtual method table structure.
     *
     * @param classStruct class structure.
     * @param name        the name of the method.
     * @return the index of the method inside the virtual method table structure.
     */
    public Integer getMethodIndex(TypeStruct classStruct, String name) {
        name = "_" + name;
        Integer index = null;
        var vmtStruct = vmtStructs.get(classStruct.getName());
        for (int i = vmtStruct.getFields().size() - 1; i >= 0; i--) {
            if (vmtStruct.getFields().get(i).getName().contains(name)) {
                index = i;
                break;
            }
        }
        return index;
    }

    /*
     * translate the methods of classes.
     */
    private void translateMethods() {
        for (NQJClassDecl classDecl : javaProg.getClassDecls()) {
            var current = classDecl;
            ConstStruct cs = (ConstStruct) vmts.get(classDecl.getName())
                    .getInitialValue();
            var shouldOverrideMethods = new HashMap<String, Integer>();
            int i = 0;
            while (current != null) {
                // translate methods and add methods to the construction of virtual method table
                for (var method : current.getMethods()) {
                    // override the current method if a method with the same name exists in subclass
                    if (shouldOverrideMethods.containsKey(method.getName())) {
                        var overrider = cs.getValues()
                                .remove(i - shouldOverrideMethods.get(method.getName()) - 1);
                        cs.getValues().add(0, overrider);
                        shouldOverrideMethods.put(method.getName(), i - 1);
                        continue;
                    }
                    shouldOverrideMethods.put(method.getName(), i);
                    i++;
                    Proc proc = loadFunctionProc(method);
                    // add procedure to the construction of virtual method table structure
                    cs.getValues().add(0, ProcedureRef(proc));
                    translateFunction(method);
                }
                current = current.getDirectSuperClass();
            }
        }
    }

    // create a constructor function for the giving class
    private void createConstructor(NQJClassDecl classDecl) {
        Proc proc = Proc(classDecl.getName() + "_constructor",
                TypePointer(classStructs.get(classDecl.getName())),
                ParameterList(), BasicBlockList());
        BasicBlock basicBlock = newBasicBlock("init");
        proc.getBasicBlocks().add(basicBlock);
        // allocate space for object
        TemporaryVar obj = TemporaryVar("address_this");
        basicBlock.add(Alloc(obj, Sizeof(classStructs.get(classDecl.getName()))));
        // cast i8* to object struct
        TemporaryVar currentThis = TemporaryVar("this");
        basicBlock.add(Bitcast(currentThis,
                TypePointer(classStructs.get(classDecl.getName())), VarRef(obj)));


        // set reference to the virtual method table
        TemporaryVar ptr = TemporaryVar("ptr");
        basicBlock.add(GetElementPtr(ptr, VarRef(currentThis),
                OperandList(ConstInt(0), ConstInt(0))));
        basicBlock.add(Store(VarRef(ptr),
                GlobalRef(vmts.get(classDecl.getName()))));
        if (classStructs.get(classDecl.getName()).getFields().size() > 1) {
            // initialise fields with default values
            for (int i = 1; i < classStructs.get(classDecl.getName()).getFields().size(); i++) {
                TemporaryVar fieldPtr = TemporaryVar("field_ptr");
                basicBlock.add(GetElementPtr(fieldPtr, VarRef(currentThis),
                        OperandList(ConstInt(0), ConstInt(i))));
                basicBlock.add(Store(VarRef(fieldPtr),
                        defaultValue(classStructs.get(classDecl.getName())
                                .getFields().get(i).getType())));
            }
        }

        basicBlock.add(ReturnExpr(VarRef(currentThis)));

        addProcedure(proc);
        constructors.put(classDecl.getName() + "_constructor", proc);
    }

    public Proc getConstructorProc(String name) {
        return constructors.get(name);
    }

}
