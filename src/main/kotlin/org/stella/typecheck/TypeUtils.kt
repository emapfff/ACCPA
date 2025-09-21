package org.stella.typecheck

import org.syntax.stella.Absyn.*
import kotlin.collections.all
import kotlin.collections.any
import kotlin.collections.filterIsInstance
import kotlin.collections.firstOrNull
import kotlin.collections.indices
import kotlin.let

object TypeUtils {
    fun isEqual(t1: Type?, t2: Type?): Boolean {
        if (t1 == null || t2 == null) return t1 == t2
        if (t1::class != t2::class) return false

        return when {
            t1 is TypeBool && t2 is TypeBool -> true
            t1 is TypeNat && t2 is TypeNat -> true
            t1 is TypeUnit && t2 is TypeUnit -> true
            t1 is TypeFun && t2 is TypeFun -> {
                if (t1.listtype_.size != 1 || t2.listtype_.size != 1) return false
                isEqual(t1.listtype_[0], t2.listtype_[0]) && isEqual(t1.type_, t2.type_)
            }

            t1 is TypeTuple && t2 is TypeTuple -> {
                if (t1.listtype_.size != t2.listtype_.size) return false
                t1.listtype_.indices.all { isEqual(t1.listtype_[it], t2.listtype_[it]) }
            }

            t1 is TypeSum && t2 is TypeSum ->
                isEqual(t1.type_1, t2.type_1) && isEqual(t1.type_2, t2.type_2)

            t1 is TypeList && t2 is TypeList ->
                isEqual(t1.type_, t2.type_)

            t1 is TypeRef && t2 is TypeRef ->
                isEqual(t1.type_, t2.type_)

            t1 is TypeVariant && t2 is TypeVariant ->
                isVariantTypesEqual(t1, t2)

            t1 is TypeRecord && t2 is TypeRecord ->
                isRecordTypesEqual(t1, t2)

            else -> false
        }
    }

    private fun isVariantTypesEqual(variant1: TypeVariant, variant2: TypeVariant): Boolean {
        if (variant1.listvariantfieldtype_.size != variant2.listvariantfieldtype_.size) return false

        for (field1 in variant1.listvariantfieldtype_) {
            if (field1 is AVariantFieldType) {
                val found = variant2.listvariantfieldtype_.any { field2 ->
                    field2 is AVariantFieldType &&
                            field1.stellaident_ == field2.stellaident_ &&
                            isEqual(
                                getOptionalTypingType(field1.optionaltyping_),
                                getOptionalTypingType(field2.optionaltyping_)
                            )
                }
                if (!found) return false
            }
        }
        return true
    }

    private fun isRecordTypesEqual(record1: TypeRecord, record2: TypeRecord): Boolean {
        if (record1.listrecordfieldtype_.size != record2.listrecordfieldtype_.size) return false

        for (field1 in record1.listrecordfieldtype_) {
            if (field1 is ARecordFieldType) {
                val found = record2.listrecordfieldtype_.any { field2 ->
                    field2 is ARecordFieldType &&
                            field1.stellaident_ == field2.stellaident_ &&
                            isEqual(field1.type_, field2.type_)
                }
                if (!found) return false
            }
        }
        return true
    }


    fun isEqualSubtypes(subtype: Type, supertype: Type, typeEnv: TypeEnv): Boolean {
        if (isEqual(subtype, supertype)) return true

        return when {
            subtype is TypeRecord && supertype is TypeRecord ->
                isRecordSubtype(subtype, supertype, typeEnv)

            subtype is TypeVariant && supertype is TypeVariant ->
                isVariantSubtype(subtype, supertype, typeEnv)

            subtype is TypeFun && supertype is TypeFun ->
                isFunctionSubtype(subtype, supertype, typeEnv)

            subtype is TypeRef && supertype is TypeRef ->
                isEqual(subtype.type_, supertype.type_)

            subtype is TypeList && supertype is TypeList ->
                isEqualSubtypes(subtype.type_, supertype.type_, typeEnv)

            subtype is TypeTuple && supertype is TypeTuple ->
                subtype.listtype_.size == supertype.listtype_.size &&
                        subtype.listtype_.indices.all {
                            isEqualSubtypes(subtype.listtype_[it], supertype.listtype_[it], typeEnv)
                        }

            subtype is TypeSum && supertype is TypeSum ->
                isEqualSubtypes(subtype.type_1, supertype.type_1, typeEnv) &&
                        isEqualSubtypes(subtype.type_2, supertype.type_2, typeEnv)

            else -> false
        }
    }

    fun isRecordSubtype(subRecord: TypeRecord, superRecord: TypeRecord, typeEnv: TypeEnv): Boolean {
        for (superField in superRecord.listrecordfieldtype_) {
            if (superField is ARecordFieldType) {
                val fieldName = superField.stellaident_
                val superFieldType = superField.type_
                val subFieldType = subRecord.listrecordfieldtype_
                    .filterIsInstance<ARecordFieldType>()
                    .firstOrNull { it.stellaident_ == fieldName }
                    ?.type_

                if (subFieldType == null || !isEqualSubtypes(subFieldType, superFieldType, typeEnv)) {
                    return false
                }
            }
        }
        return true
    }

    fun isVariantSubtype(
        subVariant: TypeVariant,
        superVariant: TypeVariant,
        typeEnv: TypeEnv
    ): Boolean {
        for (subField in subVariant.listvariantfieldtype_) {
            if (subField is AVariantFieldType) {
                val labelName = subField.stellaident_
                var found = false

                for (superField in superVariant.listvariantfieldtype_) {
                    if (superField is AVariantFieldType && labelName == superField.stellaident_) {
                        val subFieldType = getOptionalTypingType(subField.optionaltyping_)
                        val superFieldType = getOptionalTypingType(superField.optionaltyping_)

                        found = when {
                            subFieldType == null && superFieldType == null -> true
                            subFieldType != null && superFieldType != null ->
                                isEqualSubtypes(subFieldType, superFieldType, typeEnv)

                            else -> false
                        }
                        break
                    }
                }

                if (!found) return false
            }
        }
        return true
    }

    fun isFunctionSubtype(subFun: TypeFun, superFun: TypeFun, typeEnv: TypeEnv): Boolean {
        if (subFun.listtype_.size != 1 || superFun.listtype_.size != 1) return false

        val subParam = subFun.listtype_[0]
        val superParam = superFun.listtype_[0]
        val subReturn = subFun.type_
        val superReturn = superFun.type_

        val paramCheck = isEqualSubtypes(superParam, subParam, typeEnv)
        val returnCheck = isEqualSubtypes(subReturn, superReturn, typeEnv)

        if (!paramCheck && subParam is TypeRecord && superParam is TypeRecord) {
            if (!hasAllRequiredFields(superParam, subParam)) return false
        }

        return paramCheck && returnCheck
    }

    fun hasAllRequiredFields(actual: TypeRecord, required: TypeRecord): Boolean {
        for (requiredField in required.listrecordfieldtype_) {
            if (requiredField is ARecordFieldType) {
                val fieldName = requiredField.stellaident_
                val found = actual.listrecordfieldtype_
                    .filterIsInstance<ARecordFieldType>()
                    .any { it.stellaident_ == fieldName }

                if (!found) return false
            }
        }
        return true
    }

    fun getOptionalTypingType(optTyping: OptionalTyping): Type? {
        return if (optTyping is SomeTyping) optTyping.type_ else null
    }

    fun isSumType(type: Type?): Boolean = type is TypeSum
    fun isListType(type: Type?): Boolean = type is TypeList
    fun isVariantType(type: Type?): Boolean = type is TypeVariant
    fun getFunctionArgumentType(functionType: Type?): Type? {
        return (functionType as? TypeFun)?.let {
            if (it.listtype_.size == 1) it.listtype_[0] else null
        }
    }

    fun getFunctionReturnType(functionType: Type?): Type? {
        return (functionType as? TypeFun)?.type_
    }

    fun getTupleTypeByIndex(tupleType: Type?, index: Int): Type? {
        return (tupleType as? TypeTuple)?.let {
            if (index >= 1 && index <= it.listtype_.size) it.listtype_[index - 1] else null
        }
    }

    fun getSumLeftType(sumType: Type?): Type? = (sumType as? TypeSum)?.type_1
    fun getSumRightType(sumType: Type?): Type? = (sumType as? TypeSum)?.type_2
    fun getListElementType(listType: Type?): Type? = (listType as? TypeList)?.type_
}