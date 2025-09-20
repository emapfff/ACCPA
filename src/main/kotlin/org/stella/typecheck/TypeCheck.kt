package org.stella.typecheck

import org.stella.typecheck.TypeUtils.getOptionalTypingType
import org.stella.typecheck.TypeUtils.hasAllRequiredFields
import org.stella.typecheck.TypeUtils.isEqualSubtypes
import org.syntax.stella.Absyn.*
import kotlin.apply
import kotlin.ranges.until
import kotlin.text.startsWith
import kotlin.text.substring

object TypeCheck {
    fun typecheckProgram(program: Program) {
        program.accept(ProgramVisitor(), Context())
    }
}

class ProgramVisitor : Program.Visitor<Void, Context> {
    override fun visit(p: AProgram, context: Context): Void? {
        for (ext in p.listextension_) {
            ext.accept(ExtensionVisitor(), context)
        }

        for (decl in p.listdecl_) {
            if (decl is DeclFun) {
                val returnType = getReturnType(decl.returntype_)
                if (decl.listparamdecl_.size != 1) {
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
                }

                val param = decl.listparamdecl_[0]
                val paramType =
                    if (param is AParamDecl) param.type_ else throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
                val paramTypes = ListType().apply { add(paramType) }
                val funType = TypeFun(paramTypes, returnType)
                context.addFunction(decl.stellaident_, funType)
            } else if (decl is DeclExceptionType) {
                context.setExceptionType(decl.type_)
            }
        }
        if (!context.hasMainFunction) throw kotlin.Error(TypeCheckException.ERROR_MISSING_MAIN)

        for (decl in p.listdecl_) {
            decl.accept(DeclVisitor(), context)
        }

        return null
    }

    private fun getReturnType(returnType: ReturnType): Type {
        return if (returnType is SomeReturnType) returnType.type_
        else throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
    }
}

class ExtensionVisitor : Extension.Visitor<Void, Context> {
    override fun visit(p: AnExtension, context: Context): Void? {
        for (extName in p.listextensionname_) {
            val cleanExtName = if (extName.startsWith("#")) extName.substring(1) else extName
            context.addExtension(cleanExtName)
        }
        return null
    }
}

class DeclVisitor : Decl.Visitor<Void, Context> {
    override fun visit(p: DeclFun, context: Context): Void? {
        val funType = context.lookupFunction(p.stellaident_)
            ?: throw kotlin.Error(TypeCheckException.ERROR_UNDEFINED_VARIABLE)

        if (funType !is TypeFun) throw kotlin.Error(TypeCheckException.ERROR_UNDEFINED_VARIABLE)

        val expectedReturnType = funType.type_
        context.pushScope()

        if (p.listparamdecl_.size == 1) {
            val param = p.listparamdecl_[0]
            if (param is AParamDecl) {
                context.addVariable(param.stellaident_, param.type_)
            }
        }

        val exprVisitor = ExprVisitor()
        val actualReturnType = exprVisitor.visitWithExpectedType(p.expr_, expectedReturnType, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(actualReturnType, expectedReturnType, context)) {
                if (actualReturnType is TypeFun && expectedReturnType is TypeFun) {
                    val actualFun = actualReturnType
                    val expectedFun = expectedReturnType

                    if (actualFun.listtype_.size == 1 && expectedFun.listtype_.size == 1) {
                        val actualParam = actualFun.listtype_[0]
                        val expectedParam = expectedFun.listtype_[0]

                        if (actualParam is TypeRecord && expectedParam is TypeRecord) {
                            if (!hasAllRequiredFields(expectedParam, actualParam)) {
                                throw kotlin.Error(TypeCheckException.ERROR_MISSING_RECORD_FIELDS)
                            }
                        }
                    }
                }
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)

            }
        } else {
            if (!TypeUtils.isEqual(expectedReturnType, actualReturnType)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        context.popScope()
        return null

    }

    override fun visit(p: DeclExceptionType, context: Context): Void? {
        if (!context.hasExtension("exception-type-declaration")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return null
    }

    override fun visit(p: DeclFunGeneric, context: Context): Void? {
        TODO()
    }

    override fun visit(p: DeclTypeAlias, context: Context): Void? {
        TODO()
    }

    override fun visit(p: DeclExceptionVariant, context: Context): Void? {
        TODO()
    }
}

class ExprVisitor : Expr.Visitor<Type, Context> {
    override fun visit(p: ConstTrue, context: Context): Type = TypeBool()
    override fun visit(p: ConstFalse, context: Context): Type = TypeBool()

    override fun visit(p: ConstInt, context: Context): Type {
        if (p.integer_ < 0) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        return TypeNat()
    }

    override fun visit(p: ConstUnit, context: Context): Type {
        if (!context.hasExtension("unit-type")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return TypeUnit()
    }

    override fun visit(p: ConstMemory, context: Context): Type {
        if (!context.hasExtension("references")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_MEMORY_ADDRESS)
        }
        throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_REFERENCE_TYPE)
    }

    override fun visit(p: Var, context: Context): Type? {
        when (p.stellaident_) {
            "Nat::iszero" -> {
                val paramTypes = ListType().apply { add(TypeNat()) }
                return TypeFun(paramTypes, TypeBool())
            }

            "Nat::rec", "List::head", "List::tail", "List::isempty" -> return null
        }

        val varType = context.lookupVariable(p.stellaident_) ?: context.lookupFunction(p.stellaident_)
        if (varType == null) {
            throw kotlin.Error(TypeCheckException.ERROR_UNDEFINED_VARIABLE)
        }
        return varType
    }

    override fun visit(p: If, context: Context): Type {
        val condType = p.expr_1.accept(this, context)
        if (condType !is TypeBool) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        }

        val thenType = p.expr_2.accept(this, context)
        val elseType = p.expr_3.accept(this, context)

        when {
            isPanicExpression(p.expr_2) -> {
                visitWithExpectedType(p.expr_2, elseType, context)
                return elseType
            }

            isPanicExpression(p.expr_3) -> {
                visitWithExpectedType(p.expr_3, thenType, context)
                return thenType
            }
        }

        return if (context.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(thenType, elseType, context) -> elseType
                isEqualSubtypes(elseType, thenType, context) -> thenType
                else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(thenType, elseType)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            thenType
        }
    }

    override fun visit(p: Sequence, context: Context): Type {
        if (!context.hasExtension("sequencing")) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val firstType = p.expr_1.accept(this, context)
        if (firstType !is TypeUnit) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        return p.expr_2.accept(this, context)
    }

    private fun isPanicExpression(expr: Expr): Boolean = expr is Panic

    fun visitIfWithContext(ifExpr: If, expectedType: Type, context: Context): Type {
        val condType = ifExpr.expr_1.accept(this, context)
        if (condType !is TypeBool) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)


        val thenType = visitWithExpectedType(ifExpr.expr_2, expectedType, context)
        val elseType = visitWithExpectedType(ifExpr.expr_3, expectedType, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(thenType, expectedType, context) || !isEqualSubtypes(elseType, expectedType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(thenType, expectedType) || !TypeUtils.isEqual(elseType, expectedType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return expectedType
    }

    override fun visit(p: Record, context: Context): Type {
        if (!context.hasExtension("records")) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_RECORD)

        val fieldTypes = ListRecordFieldType()
        val seenFields = kotlin.collections.HashSet<String>()

        for (binding in p.listbinding_) {
            if (binding is ABinding) {
                val fieldName = binding.stellaident_
                if (seenFields.contains(fieldName)) {
                    throw kotlin.Error(
                        TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION
                    )
                }
                seenFields.add(fieldName)
                val fieldType = binding.expr_.accept(this, context)
                fieldTypes.add(ARecordFieldType(fieldName, fieldType))
            }
        }

        return TypeRecord(fieldTypes)
    }

    override fun visit(p: DotRecord, context: Context): Type {
        if (!context.hasExtension("records"))
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_RECORD)

        val recordType = p.expr_.accept(this, context)
        if (recordType !is TypeRecord)
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_RECORD)

        val fieldName = p.stellaident_
        for (fieldType in recordType.listrecordfieldtype_) {
            if (fieldType is ARecordFieldType && fieldName == fieldType.stellaident_) {
                return fieldType.type_
            }
        }

        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_FIELD_ACCESS)
    }

    override fun visit(p: Fix, context: Context): Type {

        if (!context.hasExtension("fixpoint-combinator")) {
            throw kotlin.Error(
                TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION
            )
        }

        val exprType = p.expr_.accept(this, context)
        if (exprType !is TypeFun) {
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_FUNCTION)
        }

        val argType = TypeUtils.getFunctionArgumentType(exprType)
        val retType = TypeUtils.getFunctionReturnType(exprType)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(argType!!, retType!!, context) && !isEqualSubtypes(retType, argType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(argType, retType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return argType!!
    }

    override fun visit(p: Ref, context: Context): Type {
        if (!context.hasExtension("references")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        val exprType = p.expr_.accept(this, context)
        return TypeRef(exprType)
    }

    override fun visit(p: Deref, context: Context): Type {
        if (!context.hasExtension("references")) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_REFERENCE)

        val exprType = p.expr_.accept(this, context)
        if (exprType !is TypeRef)
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_REFERENCE)

        return exprType.type_
    }

    override fun visit(p: Assign, context: Context): Type {
        if (!context.hasExtension("references"))
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_REFERENCE)

        val refType = p.expr_1.accept(this, context)
        val valueType = p.expr_2.accept(this, context)

        if (refType !is TypeRef)
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_REFERENCE)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(valueType, refType.type_, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(refType.type_, valueType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return TypeUnit()
    }

    override fun visit(p: Panic, context: Context): Type {
        if (!context.hasExtension("panic")) {
            throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_PANIC_TYPE)
        }
        throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_PANIC_TYPE)
    }

    override fun visit(p: Throw, context: Context): Type {
        if (!context.hasExtension("exceptions")) {
            throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            context.exceptionType ?: throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val thrownType = p.expr_.accept(this, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(thrownType, exceptionType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(exceptionType, thrownType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_THROW_TYPE)
    }

    override fun visit(p: TryWith, context: Context): Type {
        if (!context.hasExtension("exceptions")) {
            throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            context.exceptionType ?: throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val tryType = p.expr_1.accept(this, context)
        val handlerType = p.expr_2.accept(this, context)

        if (handlerType !is TypeFun)
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_FUNCTION)

        return if (context.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(tryType, handlerType, context) -> handlerType
                isEqualSubtypes(handlerType, tryType, context) -> tryType
                else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(tryType, handlerType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            tryType
        }
    }

    override fun visit(p: TryCatch, context: Context): Type {
        if (!context.hasExtension("exceptions")) {
            throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            context.exceptionType ?: throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val tryType = p.expr_1.accept(this, context)
        context.pushScope()
        addPatternBindings(p.pattern_, exceptionType, context)
        val catchType = p.expr_2.accept(this, context)
        context.popScope()

        return if (context.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(tryType, catchType, context) -> catchType
                isEqualSubtypes(catchType, tryType, context) -> tryType
                else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(tryType, catchType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            tryType
        }
    }

    override fun visit(p: Variant, context: Context): Type {
        if (!context.hasExtension("variants")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_VARIANT)
        }
        throw kotlin.Error(
            TypeCheckException.ERROR_AMBIGUOUS_VARIANT_TYPE
        )
    }

    fun visitVariantWithContext(
        variant: Variant,
        expectedVariantType: TypeVariant,
        context: Context
    ): Type {
        val variantLabel = variant.stellaident_
        var expectedDataType: Type? = null
        var labelFound = false

        for (field in expectedVariantType.listvariantfieldtype_) {
            if (field is AVariantFieldType && variantLabel == field.stellaident_) {
                expectedDataType = getOptionalTypingType(field.optionaltyping_)
                labelFound = true
                break
            }
        }

        if (!labelFound)
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_VARIANT_LABEL)

        when (variant.exprdata_) {
            is SomeExprData -> {
                val someData = variant.exprdata_
                val actualDataType = someData.expr_.accept(this, context)

                if (expectedDataType == null)
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

                if (context.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(actualDataType, expectedDataType, context))
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
                } else {
                    if (!TypeUtils.isEqual(actualDataType, expectedDataType)) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                }
            }

            is NoExprData -> {
                if (expectedDataType != null)
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedVariantType
    }

    override fun visit(p: TypeCast, context: Context): Type {
        if (!context.hasExtension("type-cast"))
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val exprType = p.expr_.accept(this, context)
        val targetType = p.type_

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(exprType, targetType, context) && !isEqualSubtypes(targetType, exprType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return targetType
    }

    override fun visit(p: TypeAsc, context: Context): Type {
        if (!context.hasExtension("type-ascriptions"))
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)


        val exprType = p.expr_.accept(this, context)
        val ascribedType = p.type_

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(exprType, ascribedType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)

        } else {
            if (!TypeUtils.isEqual(exprType, ascribedType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return ascribedType
    }

    override fun visit(p: Inl, context: Context): Type {
        if (!context.hasExtension("sum-types")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_INJECTION)
        }
        throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_SUM_TYPE)
    }

    override fun visit(p: Inr, context: Context): Type {
        if (!context.hasExtension("sum-types")) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_INJECTION)

        throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_SUM_TYPE)
    }

    override fun visit(p: Succ, context: Context): Type {
        val argType = p.expr_.accept(this, context)
        if (argType !is TypeNat) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        return TypeNat()
    }

    override fun visit(p: IsZero, context: Context): Type {
        val argType = p.expr_.accept(this, context)
        if (argType !is TypeNat) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        return TypeBool()
    }

    override fun visit(p: NatRec, context: Context): Type {
        return visitNatRecApplication(listOf(p.expr_1, p.expr_2, p.expr_3), context)
    }

    override fun visit(p: Abstraction, context: Context): Type {
        if (p.listparamdecl_.size != 1)
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)

        val param = p.listparamdecl_[0]
        if (param !is AParamDecl)
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)

        context.pushScope()
        context.addVariable(param.stellaident_, param.type_)
        val bodyType = p.expr_.accept(this, context)
        context.popScope()

        val paramTypes = ListType().apply { add(param.type_) }
        return TypeFun(paramTypes, bodyType)
    }

    override fun visit(p: Match, context: Context): Type {
        if (!context.hasExtension("sum-types") && !context.hasExtension("lists") && !context.hasExtension("variants"))
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        if (p.listmatchcase_.isEmpty())
            throw kotlin.Error(TypeCheckException.ERROR_ILLEGAL_EMPTY_MATCHING)

        val matchedType = p.expr_.accept(this, context)
        var hasInlPattern = false
        var hasInrPattern = false
        var hasNilPattern = false
        var hasConsPattern = false
        val variantLabels = kotlin.collections.HashSet<String>()
        var resultType: Type? = null

        for (matchCase in p.listmatchcase_) {
            if (matchCase is AMatchCase) {
                when (val pattern = matchCase.pattern_) {
                    is PatternInl -> {
                        if (!TypeUtils.isSumType(matchedType))
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasInlPattern = true
                        val leftType = TypeUtils.getSumLeftType(matchedType)!!
                        context.pushScope()
                        addPatternBindings(pattern.pattern_, leftType, context)
                        val caseType = matchCase.expr_.accept(this, context)
                        context.popScope()
                        resultType = updateResultType(resultType, caseType, context)
                    }

                    is PatternInr -> {
                        if (!TypeUtils.isSumType(matchedType))
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasInrPattern = true
                        val rightType = TypeUtils.getSumRightType(matchedType)!!
                        context.pushScope()
                        addPatternBindings(pattern.pattern_, rightType, context)
                        val caseType = matchCase.expr_.accept(this, context)
                        context.popScope()
                        resultType = updateResultType(resultType, caseType, context)
                    }

                    is PatternList -> {
                        if (!TypeUtils.isListType(matchedType))
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        if (pattern.listpattern_.isEmpty()) {
                            hasNilPattern = true
                        }
                        context.pushScope()
                        val caseType = matchCase.expr_.accept(this, context)
                        context.popScope()
                        resultType = updateResultType(resultType, caseType, context)
                    }

                    is PatternCons -> {
                        if (!TypeUtils.isListType(matchedType))
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasConsPattern = true
                        val elemType = TypeUtils.getListElementType(matchedType)!!
                        context.pushScope()
                        addPatternBindings(pattern.pattern_1, elemType, context)
                        addPatternBindings(pattern.pattern_2, matchedType, context)
                        val caseType = matchCase.expr_.accept(this, context)
                        context.popScope()
                        resultType = updateResultType(resultType, caseType, context)
                    }

                    is PatternVariant -> {
                        if (!TypeUtils.isVariantType(matchedType))
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        val variantLabel = pattern.stellaident_
                        variantLabels.add(variantLabel)
                        var expectedDataType: Type? = null
                        var labelFound = false

                        if (matchedType is TypeVariant) {
                            for (field in matchedType.listvariantfieldtype_) {
                                if (field is AVariantFieldType && variantLabel == field.stellaident_) {
                                    expectedDataType = getOptionalTypingType(field.optionaltyping_)
                                    labelFound = true
                                    break
                                }
                            }
                        }

                        if (!labelFound) {
                            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_VARIANT_LABEL)
                        }

                        context.pushScope()
                        if (pattern.patterndata_ is SomePatternData) {
                            val someData = pattern.patterndata_ as SomePatternData
                            if (expectedDataType != null) {
                                addPatternBindings(someData.pattern_, expectedDataType, context)
                            } else {
                                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                            }
                        }
                        val caseType = matchCase.expr_.accept(this, context)
                        context.popScope()
                        resultType = updateResultType(resultType, caseType, context)
                    }

                    else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                }
            }
        }

        when {
            TypeUtils.isSumType(matchedType) -> {
                if (!hasInlPattern || !hasInrPattern)
                    throw kotlin.Error(TypeCheckException.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
            }

            TypeUtils.isListType(matchedType) -> {
                if (!hasNilPattern || !hasConsPattern)
                    throw kotlin.Error(TypeCheckException.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
            }

            TypeUtils.isVariantType(matchedType) && matchedType is TypeVariant -> {
                for (field in matchedType.listvariantfieldtype_) {
                    if (field is AVariantFieldType && !variantLabels.contains(field.stellaident_))
                        throw kotlin.Error(TypeCheckException.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
                }
            }
        }

        return resultType ?: throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
    }

    private fun updateResultType(currentResultType: Type?, newCaseType: Type, context: Context): Type {
        return if (currentResultType == null) {
            newCaseType
        } else {
            if (context.hasExtension("structural-subtyping")) {
                when {
                    isEqualSubtypes(currentResultType, newCaseType, context) -> newCaseType
                    isEqualSubtypes(newCaseType, currentResultType, context) -> currentResultType
                    else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
                }
            } else {
                if (!TypeUtils.isEqual(currentResultType, newCaseType))
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                currentResultType
            }
        }
    }

    override fun visit(p: org.syntax.stella.Absyn.List, context: Context): Type {
        if (!context.hasExtension("lists")) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_LIST)
        }

        if (p.listexpr_.isEmpty()) {
            throw kotlin.Error(TypeCheckException.ERROR_AMBIGUOUS_LIST_TYPE)
        }

        var elemType = p.listexpr_[0].accept(this, context)
        for (i in 1 until p.listexpr_.size) {
            val nextType = p.listexpr_[i].accept(this, context)

            if (context.hasExtension("structural-subtyping")) {
                when {
                    isEqualSubtypes(elemType, nextType, context) -> elemType = nextType
                    else -> throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
                }
            } else {
                if (!TypeUtils.isEqual(elemType, nextType)) {
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
            }
        }

        return TypeList(elemType)
    }

    fun visitListWithContext(
        list: org.syntax.stella.Absyn.List,
        expectedListType: TypeList,
        context: Context
    ): Type {
        val expectedElemType = expectedListType.type_

        for (elemExpr in list.listexpr_) {
            val elemType = elemExpr.accept(this, context)

            if (context.hasExtension("structural-subtyping")) {
                if (!isEqualSubtypes(elemType, expectedElemType, context))
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            } else {
                if (!TypeUtils.isEqual(elemType, expectedElemType))
                    throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedListType
    }

    override fun visit(p: ConsList, context: Context): Type {
        if (!context.hasExtension("lists"))
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_LIST)

        val headType = p.expr_1.accept(this, context)
        val tailType = p.expr_2.accept(this, context)

        if (tailType !is TypeList) {
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)
        }

        val elemType = TypeUtils.getListElementType(tailType)!!

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(headType, elemType, context)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(headType, elemType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return tailType
    }

    override fun visit(p: Head, context: Context): Type {
        if (!context.hasExtension("lists")) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, context)
        if (listType !is TypeList) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        return TypeUtils.getListElementType(listType)!!
    }

    override fun visit(p: Tail, context: Context): Type {
        if (!context.hasExtension("lists")) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, context)
        if (listType !is TypeList) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        return listType
    }

    override fun visit(p: IsEmpty, context: Context): Type {
        if (!context.hasExtension("lists")) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, context)
        if (listType !is TypeList) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_LIST)

        return TypeBool()
    }

    override fun visit(p: Application, context: Context): Type {
        if (p.expr_ is Var) {
            val varExpr = p.expr_
            when (varExpr.stellaident_) {
                "List::head" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(Head(p.listexpr_[0]), context)
                }

                "List::tail" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(Tail(p.listexpr_[0]), context)
                }

                "List::isempty" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(IsEmpty(p.listexpr_[0]), context)
                }

                "Nat::iszero" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    val argType = p.listexpr_[0].accept(this, context)
                    if (argType !is TypeNat) {
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return TypeBool()
                }

                "Nat::rec" -> {
                    if (p.listexpr_.size != 3)
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    return visitNatRecApplication(p.listexpr_, context)
                }
            }
        }

        val funType = p.expr_.accept(this, context)
            ?: throw kotlin.Error(TypeCheckException.ERROR_UNDEFINED_VARIABLE)

        if (funType !is TypeFun)
            throw kotlin.Error(TypeCheckException.ERROR_NOT_A_FUNCTION)

        if (p.listexpr_.size != 1)
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val expectedArgType = TypeUtils.getFunctionArgumentType(funType)!!
        val argType = visitWithExpectedType(p.listexpr_[0], expectedArgType, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(argType, expectedArgType, context))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(expectedArgType, argType))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return TypeUtils.getFunctionReturnType(funType)!!
    }

    fun visitWithExpectedType(expr: Expr, expectedType: Type, context: Context): Type {
        return when {
            expr is Inl && expectedType is TypeSum ->
                visitInlCtx(expr, expectedType, context)

            expr is Inr && expectedType is TypeSum ->
                visitInrCtx(expr, expectedType, context)

            expr is org.syntax.stella.Absyn.List && expectedType is TypeList ->
                visitListWithContext(expr, expectedType, context)

            expr is Variant && expectedType is TypeVariant ->
                visitVariantWithContext(expr, expectedType, context)

            expr is ConstMemory && expectedType is TypeRef ->
                expectedType

            expr is If ->
                visitIfWithContext(expr, expectedType, context)

            expr is Abstraction && expectedType is TypeFun ->
                visitAbstractionWithContext(expr, expectedType, context)

            expr is Panic ->
                expectedType

            expr is TryWith -> {
                if (!context.hasExtension("exceptions"))
                    throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                val exceptionType =
                    context.exceptionType ?: throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                visitWithExpectedType(expr.expr_1, expectedType, context)
                visitWithExpectedType(expr.expr_2, expectedType, context)
                expectedType
            }

            expr is Throw -> {
                val exceptionType =
                    context.exceptionType ?: throw kotlin.Error(TypeCheckException.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                val thrownType = expr.expr_.accept(this, context)

                if (context.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(thrownType, exceptionType, context))
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)

                } else {
                    if (!TypeUtils.isEqual(exceptionType, thrownType))
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
                expectedType
            }

            else -> {
                val actualType = expr.accept(this, context)

                if (context.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(actualType, expectedType, context))
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)

                } else {
                    if (!TypeUtils.isEqual(expectedType, actualType))
                        throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
                actualType
            }
        }
    }

    private fun visitInlCtx(inl: Inl, expectedSumType: TypeSum, context: Context): Type {
        val leftType = visitWithExpectedType(inl.expr_, expectedSumType.type_1, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(leftType, expectedSumType.type_1, context)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(leftType, expectedSumType.type_1)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedSumType
    }

    private fun visitInrCtx(inr: Inr, expectedSumType: TypeSum, context: Context): Type {
        val rightType = visitWithExpectedType(inr.expr_, expectedSumType.type_2, context)

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(rightType, expectedSumType.type_2, context)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(rightType, expectedSumType.type_2))
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return expectedSumType
    }

    private fun addPatternBindings(pattern: Pattern, type: Type, context: Context) {
        if (pattern is PatternVar) {
            context.addVariable(pattern.stellaident_, type)
        }
    }

    override fun visit(p: Let, context: Context): Type {
        if (!context.hasExtension("let-bindings"))
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        context.pushScope()
        for (binding in p.listpatternbinding_) {
            if (binding is APatternBinding) {
                if (binding.pattern_ !is PatternVar) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                val varPattern = binding.pattern_ as PatternVar
                val exprType = binding.expr_.accept(this, context)
                context.addVariable(varPattern.stellaident_, exprType)
            }
        }
        val resultType = p.expr_.accept(this, context)
        context.popScope()
        return resultType
    }

    override fun visit(p: Tuple, context: Context): Type {
        if (!context.hasExtension("pairs")) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TUPLE)

        if (p.listexpr_.size < 2) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TUPLE)

        val types = ListType()
        for (expr in p.listexpr_) {
            types.add(expr.accept(this, context))
        }

        return TypeTuple(types)
    }

    override fun visit(p: DotTuple, context: Context): Type {
        if (!context.hasExtension("pairs")) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_TUPLE)

        val tupleType = p.expr_.accept(this, context)
        if (tupleType !is TypeTuple) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_TUPLE)

        val componentType = TypeUtils.getTupleComponentType(tupleType, p.integer_)
            ?: throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        return componentType
    }

    override fun visit(p: LetRec?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: TypeAbstraction?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: LessThan?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: LessThanOrEqual?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: GreaterThan?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: GreaterThanOrEqual?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Equal?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: NotEqual?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: LogicNot?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Pred?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: TryCastAs?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Fold?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Unfold?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Add?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Subtract?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: LogicOr?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Multiply?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: Divide?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: LogicAnd?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }
    override fun visit(p: TypeApplication?, arg: Context?): Type? {
        TODO("Not yet implemented")
    }


    private fun visitNatRecApplication(args: List<Expr>, context: Context): Type {
        val nType = args[0].accept(this, context)
        if (nType !is TypeNat) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val zType = args[1].accept(this, context)
        val sType = args[2].accept(this, context)

        if (sType !is TypeFun) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_FUNCTION)

        val sArgType = TypeUtils.getFunctionArgumentType(sType)!!
        val sRetType = TypeUtils.getFunctionReturnType(sType)!!

        if (sArgType !is TypeNat) throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        if (sRetType !is TypeFun) throw kotlin.Error(TypeCheckException.ERROR_NOT_A_FUNCTION)

        val innerArgType = TypeUtils.getFunctionArgumentType(sRetType)!!
        val innerRetType = TypeUtils.getFunctionReturnType(sRetType)!!

        if (context.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(zType, innerArgType, context) || !isEqualSubtypes(innerRetType, zType, context)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(zType, innerArgType) || !TypeUtils.isEqual(zType, innerRetType)) {
                throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return zType
    }

    fun visitAbstractionWithContext(
        abstraction: Abstraction,
        expectedFunType: TypeFun,
        context: Context
    ): Type {
        if (abstraction.listparamdecl_.size != 1) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
        }

        val param = abstraction.listparamdecl_[0]
        if (param !is AParamDecl) {
            throw kotlin.Error(TypeCheckException.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
        }

        context.pushScope()
        context.addVariable(param.stellaident_, param.type_)

        val expectedReturnType = TypeUtils.getFunctionReturnType(expectedFunType)!!
        val bodyType = visitWithExpectedType(abstraction.expr_, expectedReturnType, context)

        context.popScope()

        val paramTypes = ListType().apply { add(param.type_) }
        return TypeFun(paramTypes, bodyType)
    }
}
