package org.stella.typecheck

import org.stella.typecheck.TypeUtils.getOptionalTypingType
import org.stella.typecheck.TypeUtils.hasAllRequiredFields
import org.stella.typecheck.TypeUtils.isEqualSubtypes
import org.syntax.stella.Absyn.*

object TypeCheck {
    fun typecheckProgram(program: Program) {
        program.accept(ProgramVisitor(), TypeEnv())
    }
}

class ProgramVisitor : Program.Visitor<Void, TypeEnv> {
    override fun visit(p: AProgram, typeEnv: TypeEnv): Void? {
        for (ext in p.listextension_) {
            ext.accept(ExtensionVisitor(), typeEnv)
        }

        for (decl in p.listdecl_) {
            if (decl is DeclFun) {
                if (decl.listparamdecl_.size != 1) {
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
                }

                val param = decl.listparamdecl_[0]
                val paramType =
                    if (param is AParamDecl) param.type_ else throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
                val paramTypes = ListType().apply { add(paramType) }
                typeEnv.addFunction(decl.stellaident_, TypeFun(paramTypes, getReturnType(decl.returntype_)))
            } else if (decl is DeclExceptionType) {
                typeEnv.setExceptionType(decl.type_)
            }
        }
        if (!typeEnv.hasMainFunction) throw kotlin.Error(Errors.ERROR_MISSING_MAIN)

        for (decl in p.listdecl_) {
            decl.accept(DeclVisitor(), typeEnv)
        }
        return null
    }

    private fun getReturnType(returnType: ReturnType): Type {
        return if (returnType is SomeReturnType) returnType.type_
        else throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
    }
}

class ExtensionVisitor : Extension.Visitor<Void, TypeEnv> {
    override fun visit(p: AnExtension, typeEnv: TypeEnv): Void? {
        for (extName in p.listextensionname_) {
            val cleanExtName = if (extName.startsWith("#")) extName.substring(1) else extName
            typeEnv.addExtension(cleanExtName)
        }
        return null
    }
}

class DeclVisitor : Decl.Visitor<Void, TypeEnv> {
    override fun visit(p: DeclFun, typeEnv: TypeEnv): Void? {
        val funType = typeEnv.findFunction(p.stellaident_)
            ?: throw kotlin.Error(Errors.ERROR_UNDEFINED_VARIABLE)

        if (funType !is TypeFun) throw kotlin.Error(Errors.ERROR_UNDEFINED_VARIABLE)

        val expectedReturnType = funType.type_
        typeEnv.enterScope()

        if (p.listparamdecl_.size == 1) {
            val param = p.listparamdecl_[0]
            if (param is AParamDecl) {
                typeEnv.addVariable(param.stellaident_, param.type_)
            }
        }

        val exprVisitor = ExprVisitor()
        val actualReturnType = exprVisitor.visitWithExpectedType(p.expr_, expectedReturnType, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(actualReturnType, expectedReturnType, typeEnv)) {
                if (actualReturnType is TypeFun && expectedReturnType is TypeFun) {
                    val actualFun = actualReturnType
                    val expectedFun = expectedReturnType

                    if (actualFun.listtype_.size == 1 && expectedFun.listtype_.size == 1) {
                        val actualParam = actualFun.listtype_[0]
                        val expectedParam = expectedFun.listtype_[0]

                        if (actualParam is TypeRecord && expectedParam is TypeRecord) {
                            if (!hasAllRequiredFields(expectedParam, actualParam)) {
                                throw kotlin.Error(Errors.ERROR_MISSING_RECORD_FIELDS)
                            }
                        }
                    }
                }
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(expectedReturnType, actualReturnType)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        typeEnv.leaveScope()
        return null

    }

    override fun visit(p: DeclExceptionType, typeEnv: TypeEnv): Void? {
        if (!typeEnv.hasExtension("exception-type-declaration")) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return null
    }

    override fun visit(p: DeclFunGeneric, typeEnv: TypeEnv): Void? {
        TODO("Not yet implemented")
    }

    override fun visit(p: DeclTypeAlias, typeEnv: TypeEnv): Void? {
        TODO("Not yet implemented")
    }

    override fun visit(p: DeclExceptionVariant, typeEnv: TypeEnv): Void? {
        TODO("Not yet implemented")
    }
}

class ExprVisitor : Expr.Visitor<Type, TypeEnv> {
    override fun visit(p: ConstTrue, typeEnv: TypeEnv): Type = TypeBool()
    override fun visit(p: ConstFalse, typeEnv: TypeEnv): Type = TypeBool()

    override fun visit(p: ConstInt, typeEnv: TypeEnv): Type {
        return TypeNat()
    }

    override fun visit(p: ConstUnit, typeEnv: TypeEnv): Type {
        return TypeUnit()
    }

    override fun visit(p: ConstMemory, typeEnv: TypeEnv): Type {
        throw kotlin.Error(Errors.ERROR_AMBIGUOUS_REFERENCE_TYPE)
    }

    override fun visit(p: Var, typeEnv: TypeEnv): Type? {
        when (p.stellaident_) {
            "Nat::iszero" -> {
                val paramTypes = ListType().apply { add(TypeNat()) }
                return TypeFun(paramTypes, TypeBool())
            }

            "Nat::rec", "List::head", "List::tail", "List::isempty" -> return null
        }

        val varType = typeEnv.findVariable(p.stellaident_) ?: typeEnv.findFunction(p.stellaident_)
        if (varType == null) {
            throw kotlin.Error(Errors.ERROR_UNDEFINED_VARIABLE)
        }
        return varType
    }

    override fun visit(p: If, typeEnv: TypeEnv): Type {
        val condType = p.expr_1.accept(this, typeEnv)
        if (condType !is TypeBool) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        }

        val thenType = p.expr_2.accept(this, typeEnv)
        val elseType = p.expr_3.accept(this, typeEnv)

        when {
            isPanicExpression(p.expr_2) -> {
                visitWithExpectedType(p.expr_2, elseType, typeEnv)
                return elseType
            }

            isPanicExpression(p.expr_3) -> {
                visitWithExpectedType(p.expr_3, thenType, typeEnv)
                return thenType
            }
        }

        return if (typeEnv.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(thenType, elseType, typeEnv) -> elseType
                isEqualSubtypes(elseType, thenType, typeEnv) -> thenType
                else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(thenType, elseType)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
            thenType
        }
    }

    override fun visit(p: Sequence, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("sequencing")) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val firstType = p.expr_1.accept(this, typeEnv)
        if (firstType !is TypeUnit) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        return p.expr_2.accept(this, typeEnv)
    }

    private fun isPanicExpression(expr: Expr): Boolean = expr is Panic

    fun visitIfWithContext(ifExpr: If, expectedType: Type, typeEnv: TypeEnv): Type {
        val condType = ifExpr.expr_1.accept(this, typeEnv)
        if (condType !is TypeBool) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)


        val thenType = visitWithExpectedType(ifExpr.expr_2, expectedType, typeEnv)
        val elseType = visitWithExpectedType(ifExpr.expr_3, expectedType, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(thenType, expectedType, typeEnv) || !isEqualSubtypes(elseType, expectedType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(thenType, expectedType) || !TypeUtils.isEqual(elseType, expectedType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return expectedType
    }

    override fun visit(p: Record, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("records")) throw kotlin.Error(Errors.ERROR_UNEXPECTED_RECORD)

        val fieldTypes = ListRecordFieldType()
        val seenFields = kotlin.collections.HashSet<String>()

        for (binding in p.listbinding_) {
            if (binding is ABinding) {
                val fieldName = binding.stellaident_
                if (seenFields.contains(fieldName))
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                seenFields.add(fieldName)
                val fieldType = binding.expr_.accept(this, typeEnv)
                fieldTypes.add(ARecordFieldType(fieldName, fieldType))
            }
        }

        return TypeRecord(fieldTypes)
    }

    override fun visit(p: DotRecord, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("records"))
            throw kotlin.Error(Errors.ERROR_NOT_A_RECORD)

        val recordType = p.expr_.accept(this, typeEnv)
        if (recordType !is TypeRecord)
            throw kotlin.Error(Errors.ERROR_NOT_A_RECORD)

        val fieldName = p.stellaident_
        for (fieldType in recordType.listrecordfieldtype_) {
            if (fieldType is ARecordFieldType && fieldName == fieldType.stellaident_) {
                return fieldType.type_
            }
        }

        throw kotlin.Error(Errors.ERROR_UNEXPECTED_FIELD_ACCESS)
    }

    override fun visit(p: Fix, typeEnv: TypeEnv): Type {

        if (!typeEnv.hasExtension("fixpoint-combinator")) {
            throw kotlin.Error(
                Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION
            )
        }

        val exprType = p.expr_.accept(this, typeEnv)
        if (exprType !is TypeFun) {
            throw kotlin.Error(Errors.ERROR_NOT_A_FUNCTION)
        }

        val argType = TypeUtils.getFunctionArgumentType(exprType)
        val retType = TypeUtils.getFunctionReturnType(exprType)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(argType!!, retType!!, typeEnv) && !isEqualSubtypes(retType, argType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(argType, retType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return argType!!
    }

    override fun visit(p: Ref, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("references")) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        val exprType = p.expr_.accept(this, typeEnv)
        return TypeRef(exprType)
    }

    override fun visit(p: Deref, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("references")) throw kotlin.Error(Errors.ERROR_NOT_A_REFERENCE)

        val exprType = p.expr_.accept(this, typeEnv)
        if (exprType !is TypeRef)
            throw kotlin.Error(Errors.ERROR_NOT_A_REFERENCE)

        return exprType.type_
    }

    override fun visit(p: Assign, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("references"))
            throw kotlin.Error(Errors.ERROR_NOT_A_REFERENCE)

        val refType = p.expr_1.accept(this, typeEnv)
        val valueType = p.expr_2.accept(this, typeEnv)

        if (refType !is TypeRef)
            throw kotlin.Error(Errors.ERROR_NOT_A_REFERENCE)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(valueType, refType.type_, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(refType.type_, valueType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }
        return TypeUnit()
    }

    override fun visit(p: Panic, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("panic")) {
            throw kotlin.Error(Errors.ERROR_AMBIGUOUS_PANIC_TYPE)
        }
        throw kotlin.Error(Errors.ERROR_AMBIGUOUS_PANIC_TYPE)
    }

    override fun visit(p: Throw, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("exceptions")) {
            throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            typeEnv.exceptionType ?: throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val thrownType = p.expr_.accept(this, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(thrownType, exceptionType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(exceptionType, thrownType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        throw kotlin.Error(Errors.ERROR_AMBIGUOUS_THROW_TYPE)
    }

    override fun visit(p: TryWith, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("exceptions")) {
            throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            typeEnv.exceptionType ?: throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val tryType = p.expr_1.accept(this, typeEnv)
        val handlerType = p.expr_2.accept(this, typeEnv)

        if (handlerType !is TypeFun)
            throw kotlin.Error(Errors.ERROR_NOT_A_FUNCTION)

        return if (typeEnv.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(tryType, handlerType, typeEnv) -> handlerType
                isEqualSubtypes(handlerType, tryType, typeEnv) -> tryType
                else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(tryType, handlerType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            tryType
        }
    }

    override fun visit(p: TryCatch, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("exceptions")) {
            throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)
        }

        val exceptionType =
            typeEnv.exceptionType ?: throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

        val tryType = p.expr_1.accept(this, typeEnv)
        typeEnv.enterScope()
        addPatternBindings(p.pattern_, exceptionType, typeEnv)
        val catchType = p.expr_2.accept(this, typeEnv)
        typeEnv.leaveScope()

        return if (typeEnv.hasExtension("structural-subtyping")) {
            when {
                isEqualSubtypes(tryType, catchType, typeEnv) -> catchType
                isEqualSubtypes(catchType, tryType, typeEnv) -> tryType
                else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(tryType, catchType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            tryType
        }
    }

    override fun visit(p: Variant, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("variants")) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_VARIANT)
        }
        throw kotlin.Error(
            Errors.ERROR_AMBIGUOUS_VARIANT_TYPE
        )
    }

    fun visitVariantWithContext(
        variant: Variant,
        expectedVariantType: TypeVariant,
        typeEnv: TypeEnv
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
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_VARIANT_LABEL)

        when (variant.exprdata_) {
            is SomeExprData -> {
                val someData = variant.exprdata_
                val actualDataType = someData.expr_.accept(this, typeEnv)

                if (expectedDataType == null)
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

                if (typeEnv.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(actualDataType, expectedDataType, typeEnv))
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
                } else {
                    if (!TypeUtils.isEqual(actualDataType, expectedDataType)) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                }
            }

            is NoExprData -> {
                if (expectedDataType != null)
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedVariantType
    }

    override fun visit(p: TypeCast, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("type-cast"))
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val exprType = p.expr_.accept(this, typeEnv)
        val targetType = p.type_

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(exprType, targetType, typeEnv) && !isEqualSubtypes(targetType, exprType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return targetType
    }

    override fun visit(p: TypeAsc, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("type-ascriptions"))
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)


        val exprType = p.expr_.accept(this, typeEnv)
        val ascribedType = p.type_

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(exprType, ascribedType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)

        } else {
            if (!TypeUtils.isEqual(exprType, ascribedType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return ascribedType
    }

    override fun visit(p: Inl, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("sum-types")) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_INJECTION)
        }
        throw kotlin.Error(Errors.ERROR_AMBIGUOUS_SUM_TYPE)
    }

    override fun visit(p: Inr, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("sum-types")) throw kotlin.Error(Errors.ERROR_UNEXPECTED_INJECTION)

        throw kotlin.Error(Errors.ERROR_AMBIGUOUS_SUM_TYPE)
    }

    override fun visit(p: Succ, typeEnv: TypeEnv): Type {
        val argType = p.expr_.accept(this, typeEnv)
        if (argType !is TypeNat) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        return TypeNat()
    }

    override fun visit(p: IsZero, typeEnv: TypeEnv): Type {
        val argType = p.expr_.accept(this, typeEnv)
        if (argType !is TypeNat) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        return TypeBool()
    }

    override fun visit(p: NatRec, typeEnv: TypeEnv): Type {
        return visitNatRecApplication(listOf(p.expr_1, p.expr_2, p.expr_3), typeEnv)
    }

    override fun visit(p: Abstraction, typeEnv: TypeEnv): Type {
        if (p.listparamdecl_.size != 1)
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)

        val param = p.listparamdecl_[0]
        if (param !is AParamDecl)
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)

        typeEnv.enterScope()
        typeEnv.addVariable(param.stellaident_, param.type_)
        val bodyType = p.expr_.accept(this, typeEnv)
        typeEnv.leaveScope()

        val paramTypes = ListType().apply { add(param.type_) }
        return TypeFun(paramTypes, bodyType)
    }

    override fun visit(p: Match, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("sum-types") && !typeEnv.hasExtension("lists") && !typeEnv.hasExtension("variants"))
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        if (p.listmatchcase_.isEmpty())
            throw kotlin.Error(Errors.ERROR_ILLEGAL_EMPTY_MATCHING)

        val matchedType = p.expr_.accept(this, typeEnv)
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
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasInlPattern = true
                        val leftType = TypeUtils.getSumLeftType(matchedType)!!
                        typeEnv.enterScope()
                        addPatternBindings(pattern.pattern_, leftType, typeEnv)
                        val caseType = matchCase.expr_.accept(this, typeEnv)
                        typeEnv.leaveScope()
                        resultType = updateResultType(resultType, caseType, typeEnv)
                    }

                    is PatternInr -> {
                        if (!TypeUtils.isSumType(matchedType))
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasInrPattern = true
                        val rightType = TypeUtils.getSumRightType(matchedType)!!
                        typeEnv.enterScope()
                        addPatternBindings(pattern.pattern_, rightType, typeEnv)
                        val caseType = matchCase.expr_.accept(this, typeEnv)
                        typeEnv.leaveScope()
                        resultType = updateResultType(resultType, caseType, typeEnv)
                    }

                    is PatternList -> {
                        if (!TypeUtils.isListType(matchedType))
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        if (pattern.listpattern_.isEmpty()) {
                            hasNilPattern = true
                        }
                        typeEnv.enterScope()
                        val caseType = matchCase.expr_.accept(this, typeEnv)
                        typeEnv.leaveScope()
                        resultType = updateResultType(resultType, caseType, typeEnv)
                    }

                    is PatternCons -> {
                        if (!TypeUtils.isListType(matchedType))
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                        hasConsPattern = true
                        val elemType = TypeUtils.getListElementType(matchedType)!!
                        typeEnv.enterScope()
                        addPatternBindings(pattern.pattern_1, elemType, typeEnv)
                        addPatternBindings(pattern.pattern_2, matchedType, typeEnv)
                        val caseType = matchCase.expr_.accept(this, typeEnv)
                        typeEnv.leaveScope()
                        resultType = updateResultType(resultType, caseType, typeEnv)
                    }

                    is PatternVariant -> {
                        if (!TypeUtils.isVariantType(matchedType))
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
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
                            throw kotlin.Error(Errors.ERROR_UNEXPECTED_VARIANT_LABEL)
                        }

                        typeEnv.enterScope()
                        if (pattern.patterndata_ is SomePatternData) {
                            val someData = pattern.patterndata_ as SomePatternData
                            if (expectedDataType != null) {
                                addPatternBindings(someData.pattern_, expectedDataType, typeEnv)
                            } else {
                                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                            }
                        }
                        val caseType = matchCase.expr_.accept(this, typeEnv)
                        typeEnv.leaveScope()
                        resultType = updateResultType(resultType, caseType, typeEnv)
                    }

                    else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_PATTERN_FOR_TYPE)
                }
            }
        }

        when {
            TypeUtils.isSumType(matchedType) -> {
                if (!hasInlPattern || !hasInrPattern)
                    throw kotlin.Error(Errors.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
            }

            TypeUtils.isListType(matchedType) -> {
                if (!hasNilPattern || !hasConsPattern)
                    throw kotlin.Error(Errors.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
            }

            TypeUtils.isVariantType(matchedType) && matchedType is TypeVariant -> {
                for (field in matchedType.listvariantfieldtype_) {
                    if (field is AVariantFieldType && !variantLabels.contains(field.stellaident_))
                        throw kotlin.Error(Errors.ERROR_NONEXHAUSTIVE_MATCH_PATTERNS)
                }
            }
        }

        return resultType ?: throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
    }

    private fun updateResultType(currentResultType: Type?, newCaseType: Type, typeEnv: TypeEnv): Type {
        return if (currentResultType == null) {
            newCaseType
        } else {
            if (typeEnv.hasExtension("structural-subtyping")) {
                when {
                    isEqualSubtypes(currentResultType, newCaseType, typeEnv) -> newCaseType
                    isEqualSubtypes(newCaseType, currentResultType, typeEnv) -> currentResultType
                    else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
                }
            } else {
                if (!TypeUtils.isEqual(currentResultType, newCaseType))
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                currentResultType
            }
        }
    }

    override fun visit(p: org.syntax.stella.Absyn.List, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("lists")) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_LIST)
        }

        if (p.listexpr_.isEmpty()) {
            throw kotlin.Error(Errors.ERROR_AMBIGUOUS_LIST_TYPE)
        }

        var elemType = p.listexpr_[0].accept(this, typeEnv)
        for (i in 1 until p.listexpr_.size) {
            val nextType = p.listexpr_[i].accept(this, typeEnv)

            if (typeEnv.hasExtension("structural-subtyping")) {
                when {
                    isEqualSubtypes(elemType, nextType, typeEnv) -> elemType = nextType
                    else -> throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
                }
            } else {
                if (!TypeUtils.isEqual(elemType, nextType)) {
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
            }
        }

        return TypeList(elemType)
    }

    fun visitListWithContext(
        list: org.syntax.stella.Absyn.List,
        expectedListType: TypeList,
        typeEnv: TypeEnv
    ): Type {
        val expectedElemType = expectedListType.type_

        for (elemExpr in list.listexpr_) {
            val elemType = elemExpr.accept(this, typeEnv)

            if (typeEnv.hasExtension("structural-subtyping")) {
                if (!isEqualSubtypes(elemType, expectedElemType, typeEnv))
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            } else {
                if (!TypeUtils.isEqual(elemType, expectedElemType))
                    throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedListType
    }

    override fun visit(p: ConsList, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("lists"))
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_LIST)

        val headType = p.expr_1.accept(this, typeEnv)
        val tailType = p.expr_2.accept(this, typeEnv)

        if (tailType !is TypeList) {
            throw kotlin.Error(Errors.ERROR_NOT_A_LIST)
        }

        val elemType = TypeUtils.getListElementType(tailType)!!

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(headType, elemType, typeEnv)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(headType, elemType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return tailType
    }

    override fun visit(p: Head, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("lists")) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, typeEnv)
        if (listType !is TypeList) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        return TypeUtils.getListElementType(listType)!!
    }

    override fun visit(p: Tail, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("lists")) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, typeEnv)
        if (listType !is TypeList) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        return listType
    }

    override fun visit(p: IsEmpty, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("lists")) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        val listType = p.expr_.accept(this, typeEnv)
        if (listType !is TypeList) throw kotlin.Error(Errors.ERROR_NOT_A_LIST)

        return TypeBool()
    }

    override fun visit(p: Application, typeEnv: TypeEnv): Type {
        if (p.expr_ is Var) {
            val varExpr = p.expr_
            when (varExpr.stellaident_) {
                "List::head" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(Head(p.listexpr_[0]), typeEnv)
                }

                "List::tail" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(Tail(p.listexpr_[0]), typeEnv)
                }

                "List::isempty" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return visit(IsEmpty(p.listexpr_[0]), typeEnv)
                }

                "Nat::iszero" -> {
                    if (p.listexpr_.size != 1) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    val argType = p.listexpr_[0].accept(this, typeEnv)
                    if (argType !is TypeNat) {
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    }
                    return TypeBool()
                }

                "Nat::rec" -> {
                    if (p.listexpr_.size != 3)
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                    return visitNatRecApplication(p.listexpr_, typeEnv)
                }
            }
        }

        val funType = p.expr_.accept(this, typeEnv)
            ?: throw kotlin.Error(Errors.ERROR_UNDEFINED_VARIABLE)

        if (funType !is TypeFun)
            throw kotlin.Error(Errors.ERROR_NOT_A_FUNCTION)

        if (p.listexpr_.size != 1)
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val expectedArgType = TypeUtils.getFunctionArgumentType(funType)!!
        val argType = visitWithExpectedType(p.listexpr_[0], expectedArgType, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(argType, expectedArgType, typeEnv))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
        } else {
            if (!TypeUtils.isEqual(expectedArgType, argType))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return TypeUtils.getFunctionReturnType(funType)!!
    }

    fun visitWithExpectedType(expr: Expr, expectedType: Type, typeEnv: TypeEnv): Type {
        return when {
            expr is Inl && expectedType is TypeSum ->
                visitInlCtx(expr, expectedType, typeEnv)

            expr is Inr && expectedType is TypeSum ->
                visitInrCtx(expr, expectedType, typeEnv)

            expr is org.syntax.stella.Absyn.List && expectedType is TypeList ->
                visitListWithContext(expr, expectedType, typeEnv)

            expr is Variant && expectedType is TypeVariant ->
                visitVariantWithContext(expr, expectedType, typeEnv)

            expr is ConstMemory && expectedType is TypeRef ->
                expectedType

            expr is If ->
                visitIfWithContext(expr, expectedType, typeEnv)

            expr is Abstraction && expectedType is TypeFun ->
                visitAbstractionWithContext(expr, expectedType, typeEnv)

            expr is Panic ->
                expectedType

            expr is TryWith -> {
                if (!typeEnv.hasExtension("exceptions"))
                    throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                val exceptionType =
                    typeEnv.exceptionType ?: throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                visitWithExpectedType(expr.expr_1, expectedType, typeEnv)
                visitWithExpectedType(expr.expr_2, expectedType, typeEnv)
                expectedType
            }

            expr is Throw -> {
                val exceptionType =
                    typeEnv.exceptionType ?: throw kotlin.Error(Errors.ERROR_EXCEPTION_TYPE_NOT_DECLARED)

                val thrownType = expr.expr_.accept(this, typeEnv)

                if (typeEnv.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(thrownType, exceptionType, typeEnv))
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)

                } else {
                    if (!TypeUtils.isEqual(exceptionType, thrownType))
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
                expectedType
            }

            else -> {
                val actualType = expr.accept(this, typeEnv)

                if (typeEnv.hasExtension("structural-subtyping")) {
                    if (!isEqualSubtypes(actualType, expectedType, typeEnv))
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)

                } else {
                    if (!TypeUtils.isEqual(expectedType, actualType))
                        throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                }
                actualType
            }
        }
    }

    private fun visitInlCtx(inl: Inl, expectedSumType: TypeSum, typeEnv: TypeEnv): Type {
        val leftType = visitWithExpectedType(inl.expr_, expectedSumType.type_1, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(leftType, expectedSumType.type_1, typeEnv)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(leftType, expectedSumType.type_1)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return expectedSumType
    }

    private fun visitInrCtx(inr: Inr, expectedSumType: TypeSum, typeEnv: TypeEnv): Type {
        val rightType = visitWithExpectedType(inr.expr_, expectedSumType.type_2, typeEnv)

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(rightType, expectedSumType.type_2, typeEnv)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(rightType, expectedSumType.type_2))
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
        }

        return expectedSumType
    }

    private fun addPatternBindings(pattern: Pattern, type: Type, typeEnv: TypeEnv) {
        if (pattern is PatternVar) {
            typeEnv.addVariable(pattern.stellaident_, type)
        }
    }

    override fun visit(p: Let, typeEnv: TypeEnv): Type {
        if (!typeEnv.hasExtension("let-bindings"))
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        typeEnv.enterScope()
        for (binding in p.listpatternbinding_) {
            if (binding is APatternBinding) {
                if (binding.pattern_ !is PatternVar) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
                val varPattern = binding.pattern_ as PatternVar
                val exprType = binding.expr_.accept(this, typeEnv)
                typeEnv.addVariable(varPattern.stellaident_, exprType)
            }
        }
        val resultType = p.expr_.accept(this, typeEnv)
        typeEnv.leaveScope()
        return resultType
    }

    override fun visit(p: Tuple, typeEnv: TypeEnv): Type {
        if (p.listexpr_.size < 2) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TUPLE)

        val types = ListType()
        for (expr in p.listexpr_) {
            types.add(expr.accept(this, typeEnv))
        }

        return TypeTuple(types)
    }

    override fun visit(p: DotTuple, typeEnv: TypeEnv): Type {
        val tupleType = p.expr_.accept(this, typeEnv)
        if (tupleType !is TypeTuple) throw kotlin.Error(Errors.ERROR_NOT_A_TUPLE)

        val componentType = TypeUtils.getTupleTypeByIndex(tupleType, p.integer_)
            ?: throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        return componentType
    }

    override fun visit(p: LetRec?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: TypeAbstraction?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: LessThan?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: LessThanOrEqual?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: GreaterThan?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: GreaterThanOrEqual?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Equal?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: NotEqual?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: LogicNot?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Pred?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: TryCastAs?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Fold?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Unfold?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Add?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Subtract?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: LogicOr?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Multiply?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: Divide?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: LogicAnd?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    override fun visit(p: TypeApplication?, arg: TypeEnv?): Type? {
        TODO("Not yet implemented")
    }

    private fun visitNatRecApplication(args: List<Expr>, typeEnv: TypeEnv): Type {
        val nType = args[0].accept(this, typeEnv)
        if (nType !is TypeNat) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        val zType = args[1].accept(this, typeEnv)
        val sType = args[2].accept(this, typeEnv)

        if (sType !is TypeFun) throw kotlin.Error(Errors.ERROR_NOT_A_FUNCTION)

        val sArgType = TypeUtils.getFunctionArgumentType(sType)!!
        val sRetType = TypeUtils.getFunctionReturnType(sType)!!

        if (sArgType !is TypeNat) throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)

        if (sRetType !is TypeFun) throw kotlin.Error(Errors.ERROR_NOT_A_FUNCTION)

        val innerArgType = TypeUtils.getFunctionArgumentType(sRetType)!!
        val innerRetType = TypeUtils.getFunctionReturnType(sRetType)!!

        if (typeEnv.hasExtension("structural-subtyping")) {
            if (!isEqualSubtypes(zType, innerArgType, typeEnv) || !isEqualSubtypes(innerRetType, zType, typeEnv)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_SUBTYPE)
            }
        } else {
            if (!TypeUtils.isEqual(zType, innerArgType) || !TypeUtils.isEqual(zType, innerRetType)) {
                throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION)
            }
        }

        return zType
    }

    fun visitAbstractionWithContext(
        abstraction: Abstraction,
        expectedFunType: TypeFun,
        typeEnv: TypeEnv
    ): Type {
        if (abstraction.listparamdecl_.size != 1) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
        }

        val param = abstraction.listparamdecl_[0]
        if (param !is AParamDecl) {
            throw kotlin.Error(Errors.ERROR_UNEXPECTED_TYPE_FOR_PARAMETER)
        }

        typeEnv.enterScope()
        typeEnv.addVariable(param.stellaident_, param.type_)

        val expectedReturnType = TypeUtils.getFunctionReturnType(expectedFunType)!!
        val bodyType = visitWithExpectedType(abstraction.expr_, expectedReturnType, typeEnv)

        typeEnv.leaveScope()

        val paramTypes = ListType().apply { add(param.type_) }
        return TypeFun(paramTypes, bodyType)
    }
}
