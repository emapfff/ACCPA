package org.stella.typecheck

import org.syntax.stella.Absyn.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class TypeContext(
    val contextHolder: HashMap<String, Type>,
    var expectedType: Type? = null,
    var functionReturnType: Type? = null,
    var exceptionType: Type? = null,
    var used: Boolean = true
) {
    constructor(contextHolder: HashMap<String, Type>) : this(contextHolder, null, null, null, true)
    constructor(contextHolder: HashMap<String, Type>, expectedType: Type?) : this(contextHolder, expectedType, null, null, true)
    constructor(contextHolder: HashMap<String, Type>, expectedType: Type?, functionReturnType: Type?, exceptionType: Type?) :
            this(contextHolder, expectedType, functionReturnType, exceptionType, true)
}

class VisitTypeCheck {

    inner class ProgramVisitor : Program.Visitor<Void, Void> {
        override fun visit(p: AProgram, arg: Void?): Void? {
            val context = TypeContext(HashMap())
            var isMainFuncExists = false


            for (declaration in p.listdecl_) {
                if (declaration is DeclExceptionType) {
                    declaration.accept(CollectingDeclContext(), context)
                }
            }

            for (declaration in p.listdecl_) {
                if (declaration !is DeclExceptionType) {
                    declaration.accept(CollectingDeclContext(), context)
                }
            }

            for (declaration in p.listdecl_) {
                if (declaration is DeclFun && declaration.stellaident_ == "main") {
                    isMainFuncExists = true
                    break
                }
            }
            if (!isMainFuncExists) {
                throw TypingException(TypingExceptionTypes.ERROR_MISSING_MAIN)
            }

            for (declaration in p.listdecl_) {
                declaration.accept(CheckingDeclContext(), context)
            }
            return null
        }
    }

    inner class CollectingDeclContext : Decl.Visitor<Void, TypeContext> {
        override fun visit(p: DeclFun, arg: TypeContext): Void? {
            val paramsList = ListType()
            for (paramDecl in p.listparamdecl_) {
                if (paramDecl is AParamDecl) {
                    paramsList.add(paramDecl.type_)
                }
            }
            val typeFun = TypeFun(paramsList, p.returntype_.accept(ReturnTypeVisitor(), arg))
            arg.contextHolder[p.stellaident_] = typeFun
            return null
        }

        override fun visit(p: DeclExceptionType, arg: TypeContext): Void? {
            arg.exceptionType = p.type_
            return null
        }

        override fun visit(p: DeclFunGeneric, arg: TypeContext): Void? = null
        override fun visit(p: DeclTypeAlias, arg: TypeContext): Void? = null
        override fun visit(p: DeclExceptionVariant, arg: TypeContext): Void? = null
    }

    inner class CheckingDeclContext : Decl.Visitor<Void, TypeContext> {
        override fun visit(p: DeclFun, arg: TypeContext): Void? {
            val functionType = arg.contextHolder[p.stellaident_] as? TypeFun
                ?: throw TypingException(TypingExceptionTypes.ERROR_UNDEFINED_VARIABLE)

            val functionScope = TypeContext(HashMap(arg.contextHolder))
            functionScope.functionReturnType = functionType.type_
            functionScope.expectedType = functionType.type_
            functionScope.exceptionType = arg.exceptionType

            val params = functionType.listtype_
            for (i in p.listparamdecl_.indices) {
                val param = p.listparamdecl_[i] as AParamDecl
                functionScope.contextHolder[param.stellaident_] = params[i]
            }

            val bodyType = p.expr_.accept(ExprVisitor(), functionScope)
            val expectedReturnType = functionType.type_
            TypeUtils.checkTheEquality(bodyType, expectedReturnType)

            return null
        }

        override fun visit(p: DeclFunGeneric, arg: TypeContext): Void? = null
        override fun visit(p: DeclTypeAlias, arg: TypeContext): Void? = null
        override fun visit(p: DeclExceptionType, arg: TypeContext): Void? {
            arg.exceptionType = p.type_
            return null
        }
        override fun visit(p: DeclExceptionVariant, arg: TypeContext): Void? = null
    }

    inner class ReturnTypeVisitor : ReturnType.Visitor<Type, TypeContext> {
        override fun visit(p: NoReturnType, arg: TypeContext): Type? = null
        override fun visit(p: SomeReturnType, arg: TypeContext): Type = p.type_
    }

    inner class TypeVisitor<R, A> : Type.Visitor<R, A> {
        override fun visit(p: TypeAuto, arg: A): R = TypeAuto() as R
        override fun visit(p: TypeFun, arg: A): R = TypeFun(p.listtype_, p.type_) as R
        override fun visit(p: TypeForAll, arg: A): R? = null
        override fun visit(p: TypeRec, arg: A): R = TypeRec(p.stellaident_, p.type_) as R
        override fun visit(p: TypeSum, arg: A): R = TypeSum(p.type_1, p.type_2) as R
        override fun visit(p: TypeTuple, arg: A): R = TypeTuple(p.listtype_) as R
        override fun visit(p: TypeRecord, arg: A): R = p as R
        override fun visit(p: TypeVariant, arg: A): R {
            println("Constructing variant type with fields: ${p.listvariantfieldtype_}")
            return TypeVariant(p.listvariantfieldtype_) as R
        }
        override fun visit(p: TypeList, arg: A): R = TypeList(p.type_) as R
        override fun visit(p: TypeBool, arg: A): R = TypeBool() as R
        override fun visit(p: TypeNat, arg: A): R = TypeNat() as R
        override fun visit(p: TypeUnit, arg: A): R = TypeUnit() as R
        override fun visit(p: TypeTop, arg: A): R = TypeTop() as R
        override fun visit(p: TypeBottom, arg: A): R = TypeBottom() as R
        override fun visit(p: TypeRef, arg: A): R {
            val innerType = p.type_.accept(TypeVisitor<R, A>(), arg)
            return TypeRef(innerType as Type?) as R
        }
        override fun visit(p: TypeVar, arg: A): R? = null
    }

    inner class OptionalTypingVisitor<R, A> : OptionalTyping.Visitor<R, A> {
        override fun visit(p: NoTyping, arg: A): R? = null
        override fun visit(p: SomeTyping, arg: A): R = p.type_ as R
    }

    inner class PatternDataVisitor<R, A> : PatternData.Visitor<R, A> {
        override fun visit(p: NoPatternData, arg: A): R? = null
        override fun visit(p: SomePatternData, arg: A): R? = null
    }

    inner class PatternVisitor<R, A> : Pattern.Visitor<R, A> {
        override fun visit(p: PatternCastAs, arg: A): R? {
            p.pattern_.accept(PatternVisitor<R, A>(), arg)
            p.type_.accept(TypeVisitor<R, A>(), arg)
            return null
        }
        override fun visit(p: PatternAsc, arg: A): R? {
            p.pattern_.accept(PatternVisitor<R, A>(), arg)
            p.type_.accept(TypeVisitor<R, A>(), arg)
            return null
        }
        override fun visit(p: PatternVariant, arg: A): R? {
            p.patterndata_.accept(PatternDataVisitor<R, A>(), arg)
            return null
        }
        override fun visit(p: PatternInl, arg: A): R? {
            p.pattern_.accept(PatternVisitor<R, A>(), arg)
            return null
        }
        override fun visit(p: PatternInr, arg: A): R? {
            p.pattern_.accept(PatternVisitor<R,A>(), arg)
            return null
        }
        override fun visit(p: PatternTuple, arg: A): R? = null
        override fun visit(p: PatternRecord, arg: A): R? = null
        override fun visit(p: PatternList, arg: A): R? = null
        override fun visit(p: PatternCons, arg: A): R? = null
        override fun visit(p: PatternFalse, arg: A): R? = null
        override fun visit(p: PatternTrue, arg: A): R? = null
        override fun visit(p: PatternUnit, arg: A): R? = null
        override fun visit(p: PatternInt, arg: A): R? = null
        override fun visit(p: PatternSucc, arg: A): R? = null
        override fun visit(p: PatternVar, arg: A): R? = null
    }

    inner class ExprVisitor : Expr.Visitor<Type, TypeContext> {
        override fun visit(p: Sequence, arg: TypeContext): Type {
            p.expr_1.accept(this, arg)
            return p.expr_2.accept(this, arg)
        }

        override fun visit(p: Assign, arg: TypeContext): Type {
            val targetType = p.expr_1.accept(this, arg)
            val valueType = p.expr_2.accept(this, arg)

            if (targetType !is TypeRef) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_REFERENCE,
                    "Cannot assign a value to a non-reference type: $targetType")
            }

            TypeUtils.checkTheEquality(valueType, targetType.type_)
            return TypeUnit()
        }

        override fun visit(p: ConstInt, arg: TypeContext): Type = TypeNat()
        override fun visit(p: ConstMemory, arg: TypeContext): Type {
            if (arg.expectedType is TypeRef) return arg.expectedType as TypeRef
            if (arg.functionReturnType is TypeRef) return arg.functionReturnType as TypeRef

            throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_REFERENCE_TYPE,
                "Cannot infer the type of a bare memory address. Use explicit type annotations like <0x01> as &T.")
        }
        override fun visit(p: ConstTrue, arg: TypeContext): Type = TypeBool()
        override fun visit(p: ConstFalse, arg: TypeContext): Type = TypeBool()
        override fun visit(p: ConstUnit, arg: TypeContext): Type = TypeUnit()

        override fun visit(p: Var, arg: TypeContext): Type {
            val varType = arg.contextHolder[p.stellaident_]
                ?: throw TypingException(TypingExceptionTypes.ERROR_UNDEFINED_VARIABLE)
            return varType
        }

        override fun visit(p: Application, arg: TypeContext): Type {
            if (p.expr_ is Throw) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_THROW_TYPE,
                    "Cannot infer type for throw (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }
            if (p.expr_ is Panic) {
                 throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_PANIC_TYPE,
                    "Cannot infer type for panic (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }

            val funcType = p.expr_.accept(this, arg)
            if (funcType !is TypeFun) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_FUNCTION,
                    "Unexpected expression where a function is expected")
            }

            val expectedParams = funcType.listtype_
            if (p.listexpr_.size != expectedParams.size) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER,
                    "Function expected ${expectedParams.size} parameters but got ${p.listexpr_.size}")
            }

            for (i in p.listexpr_.indices) {
                val expectedType = expectedParams[i]
                val actualParamType = p.listexpr_[i].accept(this,
                    TypeContext(HashMap(arg.contextHolder), expectedType))

                if (expectedType is TypeSum && actualParamType is TypeSum) {
                    if (!(expectedType.type_1 == actualParamType.type_1 || expectedType.type_2 == actualParamType.type_2)) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                            "Expected $expectedType but got $actualParamType")
                    }
                } else {
                    TypeUtils.checkTheEquality(actualParamType, expectedType,)
                }
            }

            return funcType.type_
        }

        override fun visit(p: TypeApplication, arg: TypeContext): Type? = null

        override fun visit(p: DotRecord, arg: TypeContext): Type {
            val recordType = p.expr_.accept(this, arg)
            if (recordType !is TypeRecord) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_RECORD,
                    "Attempted to access a field of a non-record type: $recordType")
            }

            for (fieldTypeObj in recordType.listrecordfieldtype_) {
                if (fieldTypeObj is ARecordFieldType && fieldTypeObj.stellaident_ == p.stellaident_) {
                    return fieldTypeObj.type_
                }
            }

            throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_FIELD_ACCESS,
                "Access to non-existent field: ${p.stellaident_}")
        }

        override fun visit(p: If, arg: TypeContext): Type {
            val conditionContext = TypeContext(arg.contextHolder, TypeBool(), arg.functionReturnType, arg.exceptionType)
            val conditionType = p.expr_1.accept(this, conditionContext)
            if (conditionType !is TypeBool) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Condition in if-expression must be a Boolean, but got: $conditionType")
            }

            arg.used = false
            val branchContext = TypeContext(arg.contextHolder, arg.expectedType, arg.functionReturnType, arg.exceptionType)
            val thenType = p.expr_2.accept(this, branchContext)
            val elseType = p.expr_3.accept(this, branchContext)

            if (p.expr_2 is Throw || p.expr_3 is Throw) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_THROW_TYPE,
                    "Cannot infer type for throw (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }

            val isThenPanic = p.expr_2 is Panic
            val isElsePanic = p.expr_3 is Panic
            val isThenFun = thenType is TypeFun
            val isElseFun = elseType is TypeFun

            if (isThenPanic != isElsePanic) {
                if ((isThenFun && !isElseFun) || (!isThenFun && isElseFun)) {
                    throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_PANIC_TYPE,
                        "Cannot infer type for throw (use type ascriptions or enable #ambiguous-type-as-bottom).")
                }
            }

            if (p.expr_2 is Panic) return elseType
            if (p.expr_3 is Panic) return thenType

            if ((p.expr_2 is ConstMemory || p.expr_3 is ConstMemory) && arg.expectedType is TypeRef) {
                return arg.expectedType as TypeRef
            }

            TypeUtils.checkTheEquality(thenType, elseType)
            return thenType
        }

        override fun visit(p: Add, arg: TypeContext): Type {
            val left = p.expr_1.accept(this, arg)
            val right = p.expr_2.accept(this, arg)
            if (left !is TypeNat || right !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            return TypeNat()
        }

        override fun visit(p: Subtract, arg: TypeContext): Type {
            val left = p.expr_1.accept(this, arg)
            val right = p.expr_2.accept(this, arg)
            if (left !is TypeNat || right !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            return TypeNat()
        }

        override fun visit(p: Tuple, arg: TypeContext): Type {
            if (p.listexpr_.size != 2) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TUPLE)
            }
            val listType = ListType()
            listType.add(p.listexpr_[0].accept(this, arg))
            listType.add(p.listexpr_[1].accept(this, arg))
            return TypeTuple(listType)
        }

        override fun visit(p: Record, arg: TypeContext): Type {
            val inferredType = TypeRecord(ListRecordFieldType())
            for (binding in p.listbinding_) {
                if (binding is ABinding) {
                    val fieldType = binding.expr_.accept(this, arg)
                    inferredType.listrecordfieldtype_.add(ARecordFieldType(binding.stellaident_, fieldType))
                } else {
                    throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                        "Unexpected binding type in record literal: ${binding.javaClass}")
                }
            }
            return inferredType
        }

        override fun visit(p: Head, arg: TypeContext): Type {
            val listType = p.expr_.accept(this, arg)
            if (listType !is TypeList) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_LIST,
                    "Cannot get head of a non-list type: $listType")
            }
            return listType.type_
        }

        override fun visit(p: Tail, arg: TypeContext): Type {
            val listType = p.expr_.accept(this, arg)
            if (listType !is TypeList) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_LIST,
                    "Cannot get tail of a non-list type: $listType")
            }
            return listType
        }

        override fun visit(p: IsEmpty, arg: TypeContext): Type {
            if (p.expr_ is Panic) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_PANIC_TYPE,
                    "Cannot infer type for panic (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }

            val listType = p.expr_.accept(this, arg)
            if (listType is TypeBool && p.expr_.javaClass.simpleName == "Throw") {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_THROW_TYPE,
                    "Cannot infer type for throw (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }

            if (listType !is TypeList) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_LIST,
                    "Expected a list in IsEmpty but got: $listType")
            }
            return TypeBool()
        }

        override fun visit(p: Panic, arg: TypeContext): Type {
            if (arg.expectedType == null && arg.used) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_PANIC_TYPE,
                    "Cannot infer type for panic (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }
            return arg.expectedType ?: TypeUnit()
        }

        override fun visit(p: DotTuple, arg: TypeContext): Type {
            val tupleType = p.expr_.accept(this, arg)
            if (tupleType !is TypeTuple) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_TUPLE)
            }

            return when (p.integer_) {
                1 -> tupleType.listtype_[0]
                2 -> tupleType.listtype_[1]
                else -> throw TypingException(TypingExceptionTypes.ERROR_TUPLE_INDEX_OUT_OF_BOUNDS)
            }
        }

        override fun visit(p: Let, arg: TypeContext): Type {
            val newScope = TypeContext(HashMap(arg.contextHolder))
            for (binding in p.listpatternbinding_) {
                if (binding is APatternBinding) {
                    val valueType = binding.expr_.accept(this, arg)
                    if (valueType is TypeFun && binding.pattern_ !is PatternVar) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_LAMBDA,
                            "Expected an expression of a non-function type but got an anonymous function:\n$valueType")
                    }
                    if (binding.pattern_ is PatternVar) {
                        newScope.contextHolder[(binding.pattern_ as PatternVar).stellaident_] = valueType
                    } else {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                } else {
                    throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
            }

            newScope.expectedType = arg.expectedType ?: arg.functionReturnType
            newScope.functionReturnType = arg.functionReturnType
            return p.expr_.accept(this, newScope)
        }

        override fun visit(p: LetRec, arg: TypeContext): Type? = null
        override fun visit(p: TypeAbstraction, arg: TypeContext): Type? = null

        override fun visit(p: Abstraction, arg: TypeContext): Type {
            val lambdaScope = TypeContext(HashMap(arg.contextHolder), null)
            lambdaScope.functionReturnType = arg.functionReturnType

            println("Lambda function return type set to: ${lambdaScope.functionReturnType}")

            val paramTypes = ListType()
            for (paramDecl in p.listparamdecl_) {
                if (paramDecl is AParamDecl) {
                    paramTypes.add(paramDecl.type_)
                    lambdaScope.contextHolder[paramDecl.stellaident_] = paramDecl.type_
                }
            }

            lambdaScope.expectedType = when {
                arg.functionReturnType is TypeSum -> null
                arg.functionReturnType is TypeFun -> (arg.functionReturnType as TypeFun).type_
                else -> arg.functionReturnType
            }

            lambdaScope.exceptionType = arg.exceptionType
            val bodyType = p.expr_.accept(this, lambdaScope)

            when {
                p.expr_ is Throw -> throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_THROW_TYPE,
                    "Cannot infer type for throw (use type ascriptions or enable #ambiguous-type-as-bottom).")
                p.expr_ is Panic -> throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_PANIC_TYPE,
                    "Cannot infer type for panic (use type ascriptions or enable #ambiguous-type-as-bottom).")
            }

            for (paramDecl in p.listparamdecl_) {
                if (paramDecl is AParamDecl) {
                    val declaredType = paramDecl.type_
                    val inferredType = lambdaScope.contextHolder[paramDecl.stellaident_]
                    if (declaredType != inferredType) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER,
                            "Expected type $declaredType but got $inferredType for the parameter ${paramDecl.stellaident_} in function definition")
                    }
                }
            }

            return TypeFun(paramTypes, bodyType)
        }

        override fun visit(p: LessThan, arg: TypeContext): Type? = null
        override fun visit(p: LessThanOrEqual, arg: TypeContext): Type? = null
        override fun visit(p: GreaterThan, arg: TypeContext): Type? = null
        override fun visit(p: GreaterThanOrEqual, arg: TypeContext): Type? = null
        override fun visit(p: Equal, arg: TypeContext): Type? = null
        override fun visit(p: NotEqual, arg: TypeContext): Type? = null

        override fun visit(p: NatRec, arg: TypeContext): Type {
            val nType = p.expr_1.accept(this, arg)
            if (nType !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            val baseType = p.expr_2.accept(this, arg)
            val stepFuncType = p.expr_3.accept(this, arg)

            if (stepFuncType !is TypeFun) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            if (stepFuncType.listtype_.size != 1 || stepFuncType.listtype_[0] !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            if (stepFuncType.type_ !is TypeFun) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            val innerFunction = stepFuncType.type_
            if (innerFunction.listtype_.size != 1 || innerFunction.listtype_[0] != baseType) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            if (innerFunction.type_ != baseType) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }

            return baseType
        }

        override fun visit(p: Succ, arg: TypeContext): Type {
            val exprType = p.expr_.accept(this, arg)
            if (exprType is TypeTuple) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TUPLE,
                    "Unexpected tuple found where Nat was expected in succ")
            }
            if (exprType !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Expected Nat but got $exprType in succ")
            }
            return TypeNat()
        }

        override fun visit(p: Pred, arg: TypeContext): Type {
            val exprType = p.expr_.accept(this, arg)
            if (exprType !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            return TypeNat()
        }

        override fun visit(p: IsZero, arg: TypeContext): Type {
            val exprType = p.expr_.accept(this, arg)
            if (exprType !is TypeNat) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            return TypeBool()
        }

        override fun visit(p: Fix, arg: TypeContext): Type {
            var funcType = p.expr_.accept(this, arg)
            if (p.expr_ is Var) {
                funcType = arg.contextHolder[p.expr_.stellaident_]
            }

            if (funcType !is TypeFun) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_FUNCTION,
                    "Expected a function in fix-point combinator, but got: $funcType")
            }

            if (funcType.listtype_.size != 1) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Fix-point combinator expects a function fn(T) -> T, but got: $funcType")
            }

            if (funcType.listtype_[0] != funcType.type_) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Fix-point combinator must receive a function of type fn(T) -> T, but got: $funcType")
            }

            return funcType.type_
        }

        override fun visit(p: LogicAnd, arg: TypeContext): Type? = null
        override fun visit(p: LogicOr, arg: TypeContext): Type? = null
        override fun visit(p: Multiply, arg: TypeContext): Type? = null
        override fun visit(p: Divide, arg: TypeContext): Type? = null
        override fun visit(p: LogicNot, arg: TypeContext): Type? = null

        override fun visit(p: Throw, arg: TypeContext): Type {
            if (arg.expectedType == null) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_THROW_TYPE,
                    "Try-Catch block must return the same type in both branches.")
            }
            if (arg.exceptionType == null) {
                throw TypingException(TypingExceptionTypes.ERROR_EXCEPTION_TYPE_NOT_DECLARED, "")
            }

            val thrownType = p.expr_.accept(this,
                TypeContext(arg.contextHolder, null, arg.functionReturnType, arg.exceptionType))
            TypeUtils.checkTheEquality(thrownType, arg.exceptionType!!)
            return arg.expectedType!!
        }

        override fun visit(p: TryCatch, arg: TypeContext): Type {
            val tryType = p.expr_1.accept(this, arg)
            val catchType = p.expr_2.accept(this, arg)
            if (tryType != catchType) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Try-Catch block must return the same type in both branches.")
            }
            return tryType
        }

        override fun visit(p: TryWith, arg: TypeContext): Type {
            val tryType = p.expr_1.accept(this, arg)
            val withType = p.expr_2.accept(this, arg)
            if (tryType != withType) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Try-With block must return the same type in both branches. Expected: $tryType, but got: $withType")
            }
            return tryType
        }

        override fun visit(p: TryCastAs, arg: TypeContext): Type? = null

        override fun visit(p: Inl, arg: TypeContext): Type {
            val innerType = p.expr_.accept(this, arg)
            if (arg.expectedType !is TypeSum && arg.functionReturnType !is TypeSum) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_SUM_TYPE,
                    "Type inference for sum types is not supported. Use type ascriptions or enable #ambiguous-type-as-bottom.")
            }

            val expectedSum = if (arg.expectedType is TypeSum)
                arg.expectedType as TypeSum
            else
                arg.functionReturnType as TypeSum

            if (innerType is TypeFun) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_LAMBDA,
                    "Expected an expression of a non-function type ${expectedSum.type_1} but got an anonymous function $innerType")
            }

            TypeUtils.checkTheEquality(innerType, expectedSum.type_1)
            return TypeSum(innerType, expectedSum.type_2)
        }

        override fun visit(p: Inr, arg: TypeContext): Type {
            val innerType = p.expr_.accept(this, arg)
            if (arg.expectedType !is TypeSum && arg.functionReturnType !is TypeSum) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_SUM_TYPE,
                    "Expected a sum type, but got: $innerType")
            }

            var expectedSum = if (arg.expectedType is TypeSum)
                arg.expectedType as TypeSum
            else
                arg.functionReturnType as TypeSum

            while (expectedSum.type_2 is TypeSum) {
                expectedSum = expectedSum.type_2 as TypeSum
            }

            if (innerType is TypeFun && expectedSum.type_2 is TypeFun) {
                val expectedFunType = expectedSum.type_2 as TypeFun
                val actualFunType = innerType as TypeFun
                if (actualFunType.listtype_ != expectedFunType.listtype_ ||
                    actualFunType.type_ != expectedFunType.type_) {
                    throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                        "Expected function type $expectedFunType but got $actualFunType")
                }
                return TypeSum(expectedSum.type_1, innerType)
            }

            if (innerType is TypeSum && expectedSum.type_2 is TypeSum) {
                val innerSum = innerType
                val expectedInnerSum = expectedSum.type_2 as TypeSum
                if (innerSum.type_1 != expectedInnerSum.type_1 ||
                    innerSum.type_2 != expectedInnerSum.type_2) {
                    throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                        "Expected type $expectedInnerSum but got $innerSum")
                }
                return TypeSum(expectedSum.type_1, innerType)
            }

            return TypeSum(expectedSum.type_1, innerType)
        }

        override fun visit(p: Fold, arg: TypeContext): Type? = null
        override fun visit(p: Unfold, arg: TypeContext): Type? = null

        override fun visit(p: TypeAsc, arg: TypeContext): Type {
            val declaredType = p.type_.accept(TypeVisitor<Type, TypeContext>(), arg)
            val exprType = p.expr_.accept(this, TypeContext(arg.contextHolder, declaredType))

            if (p.expr_ is ConstMemory) {
                if (declaredType !is TypeRef) {
                    throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_MEMORY_ADDRESS,
                        "Expected an expression of a reference type, but got $declaredType")
                }
                return declaredType
            }

            TypeUtils.checkTheEquality(declaredType, exprType)
            return declaredType
        }

        override fun visit(p: TypeCast, arg: TypeContext): Type {
            val exprType = p.expr_.accept(this, arg)
            val castType = p.type_.accept(TypeVisitor<Type, TypeContext>(), arg)

            return castType
        }

        override fun visit(p: Ref, arg: TypeContext): Type {
            val innerType = p.expr_.accept(this, arg)
            return TypeRef(innerType)
        }

        override fun visit(p: Deref, arg: TypeContext): Type {
            val refContext = TypeContext(arg.contextHolder, TypeRef(TypeNat()))
            val refType = p.expr_.accept(this, refContext)
            if (refType !is TypeRef) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_REFERENCE,
                    "Cannot dereference a non-reference type: $refType")
            }
            return refType.type_
        }

        override fun visit(p: Variant, arg: TypeContext): Type {
            val expectedVariant = when {
                arg.expectedType is TypeVariant -> arg.expectedType as TypeVariant
                arg.exceptionType is TypeVariant -> arg.exceptionType as TypeVariant
                else -> throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_VARIANT_TYPE,
                    "Cannot determine the type of a variant expression. Use type ascriptions or enable #ambiguous-type-as-bottom.")
            }

            var matchingField: AVariantFieldType? = null
            for (expField in expectedVariant.listvariantfieldtype_) {
                if (expField is AVariantFieldType && expField.stellaident_ == p.stellaident_) {
                    matchingField = expField
                    break
                }
            }

            if (matchingField == null) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_VARIANT_TYPE,
                    "Field ${p.stellaident_} is not declared in expected variant type $expectedVariant")
            }

            val declaredFieldType = matchingField.optionaltyping_
                .accept(OptionalTypingVisitor<Type, TypeContext>(), null)

            val payloadCtx = TypeContext(arg.contextHolder, declaredFieldType, declaredFieldType, arg.exceptionType)
            val providedPayloadType = p.exprdata_.accept(ExprDataVisitor(), payloadCtx)

            val newFields = ListVariantFieldType()
            var fieldMatched = false

            for (expField in expectedVariant.listvariantfieldtype_) {
                val concreteExpField = expField as AVariantFieldType
                if (concreteExpField.stellaident_ == p.stellaident_) {
                    newFields.add(AVariantFieldType(concreteExpField.stellaident_, SomeTyping(providedPayloadType)))
                    fieldMatched = true
                } else {
                    val someDemo = concreteExpField.optionaltyping_.accept(OptionalTypingVisitor<Any?, TypeContext>(), arg)
                    if (someDemo == null) {
                        newFields.add(AVariantFieldType(concreteExpField.stellaident_, SomeTyping(TypeBottom())))
                    } else {
                        newFields.add(AVariantFieldType(concreteExpField.stellaident_, SomeTyping(someDemo as Type)))
                    }
                }
            }

            if (!fieldMatched) {
                throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_VARIANT_TYPE,
                    "Field ${p.stellaident_} is not declared in expected variant type $expectedVariant")
            }

            return TypeVariant(newFields)
        }

        override fun visit(p: Match, arg: TypeContext): Type {
            val matchExprType = p.expr_.accept(this, arg)
            val isValidMatchType = matchExprType is TypeSum ||
                    matchExprType is TypeNat ||
                    matchExprType is TypeBool ||
                    matchExprType is TypeList ||
                    matchExprType is TypeVariant

            if (!isValidMatchType) {
                throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_PATTERN_FOR_TYPE,
                    "Match expects a sum type, Nat, Bool, List, or Variant, but got: $matchExprType")
            }

            if (p.listmatchcase_.isEmpty()) {
                throw TypingException(TypingExceptionTypes.ERROR_ILLEGAL_EMPTY_MATCHING,
                    "Illegal empty matching in expression:\n  match $matchExprType { }")
            }

            var resultType: Type? = null
            var hasInlCase = false
            var hasInrCase = false
            val coveredLabels = HashSet<String>()

            for (matchCase in p.listmatchcase_) {
                if (matchCase is AMatchCase) {
                    val caseScope = TypeContext(HashMap(arg.contextHolder))
                    var isPatternValid = false

                    when {
                        matchExprType is TypeVariant && matchCase.pattern_ is PatternVariant -> {
                            val pv = matchCase.pattern_
                            coveredLabels.add(pv.stellaident_)
                            val variantType = matchExprType
                            for (field in variantType.listvariantfieldtype_) {
                                if (field is AVariantFieldType && field.stellaident_ == pv.stellaident_) {
                                    if (pv.patterndata_ is SomePatternData) {
                                        val spd = pv.patterndata_
                                        if (spd.pattern_ is PatternVar) {
                                            val pvPayload = spd.pattern_
                                            val expectedPayload = OptionalTypingUtils.getTypeFromOptional(field.optionaltyping_)
                                            caseScope.contextHolder[pvPayload.stellaident_] = expectedPayload as Type
                                        }
                                    }
                                    isPatternValid = true
                                    break
                                }
                            }
                        }
                        matchExprType is TypeSum -> {
                            when {
                                matchCase.pattern_ is PatternInl -> {
                                    hasInlCase = true
                                    val inl = matchCase.pattern_
                                    if (inl.pattern_ is PatternVar) {
                                        val pv = inl.pattern_
                                        caseScope.contextHolder[pv.stellaident_] = matchExprType.type_1
                                    }
                                    isPatternValid = true
                                }
                                matchCase.pattern_ is PatternInr -> {
                                    hasInrCase = true
                                    val inr = matchCase.pattern_ as PatternInr
                                    if (inr.pattern_ is PatternVar) {
                                        val pv = inr.pattern_ as PatternVar
                                        caseScope.contextHolder[pv.stellaident_] = matchExprType.type_2
                                    }
                                    isPatternValid = true
                                }
                            }
                        }
                        matchExprType is TypeNat && matchCase.pattern_ is PatternInt -> isPatternValid = true
                        matchExprType is TypeBool && (matchCase.pattern_ is PatternTrue || matchCase.pattern_ is PatternFalse) -> isPatternValid = true
                        matchExprType is TypeList && matchCase.pattern_ is PatternList -> isPatternValid = true
                        matchCase.pattern_ is PatternVar -> {
                            val pv = matchCase.pattern_ as PatternVar
                            caseScope.contextHolder[pv.stellaident_] = matchExprType
                            isPatternValid = true
                        }
                    }

                    if (!isPatternValid) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_PATTERN_FOR_TYPE,
                            "Unexpected pattern ${matchCase.pattern_} for type $matchExprType")
                    }

                    val caseType = matchCase.expr_.accept(this, caseScope)
                    if (resultType == null) {
                        resultType = caseType
                    } else if (resultType != caseType) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                            "All match cases must return the same type, but found $resultType and $caseType")
                    }
                }
            }

            if (matchExprType is TypeSum) {
                if (!hasInlCase || !hasInrCase) {
                    val missingCase = if (!hasInlCase) "inl (__something__)" else "inr (__something__)"
                    throw TypingException(TypingExceptionTypes.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS,
                        "Match expression does not cover all cases for sum type: $matchExprType\nAt least the following patterns are missing:\n  $missingCase")
                }
            }

            if (matchExprType is TypeVariant) {
                val variantType = matchExprType
                val missing = ArrayList<String>()
                for (field in variantType.listvariantfieldtype_) {
                    if (field is AVariantFieldType && !coveredLabels.contains(field.stellaident_)) {
                        missing.add(field.stellaident_)
                    }
                }
                if (missing.isNotEmpty()) {
                    throw TypingException(TypingExceptionTypes.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS,
                        "Match expression does not cover all cases for variant type: $variantType\nAt least the following patterns are missing:\n  $missing")
                }
            }

            return resultType ?: throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        override fun visit(p: org.syntax.stella.Absyn.List, arg: TypeContext): Type {
            if (p.listexpr_.isEmpty()) {
                return when {
                    arg.expectedType is TypeList -> arg.expectedType as TypeList
                    arg.functionReturnType is TypeList -> arg.functionReturnType as TypeList
                    else -> throw TypingException(TypingExceptionTypes.ERROR_AMBIGUOUS_LIST_TYPE,
                        "Cannot determine the type of an empty list.")
                }
            }

            if (arg.expectedType is TypeList) {
                val expectedElemType = (arg.expectedType as TypeList).type_
                for (expr in p.listexpr_) {
                    val elemType = expr.accept(this, TypeContext(arg.contextHolder, expectedElemType))
                    TypeUtils.checkTheEquality(elemType, expectedElemType)
                }
                return TypeList(expectedElemType)
            } else {
                var listElementType: Type? = null
                for (expr in p.listexpr_) {
                    val elemType = expr.accept(this, arg)
                    if (listElementType == null) {
                        listElementType = elemType
                    } else if (listElementType != elemType) {
                        throw TypingException(TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                            "Expected type $listElementType but got $elemType")
                    }
                }
                return TypeList(listElementType!!)
            }
        }

        override fun visit(p: ConsList, arg: TypeContext): Type {
            val headType = p.expr_1.accept(this, arg)
            val tailType = p.expr_2.accept(this, TypeContext(arg.contextHolder, TypeList(headType)))

            if (tailType !is TypeList) {
                throw TypingException(TypingExceptionTypes.ERROR_NOT_A_LIST,
                    "Tail of a cons-list must be of list type, but found $tailType")
            }

            TypeUtils.checkTheEquality(headType, tailType.type_)
            return tailType
        }
    }

    inner class ExprDataVisitor : ExprData.Visitor<Type, TypeContext> {
        override fun visit(p: NoExprData?, arg: TypeContext?): Type? {
            TODO("Not yet implemented")
        }

        override fun visit(p: SomeExprData, arg: TypeContext): Type {
            return p.expr_.accept(ExprVisitor(), arg)
        }
    }
}
