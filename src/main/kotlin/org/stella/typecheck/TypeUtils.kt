package org.stella.typecheck

import org.syntax.stella.Absyn.*

object TypeUtils {

    fun compareVariants(a: TypeVariant, b: TypeVariant): Boolean {
        if (a.listvariantfieldtype_.size != b.listvariantfieldtype_.size) {
            return false
        }

        for (i in a.listvariantfieldtype_.indices) {
            val fieldA = a.listvariantfieldtype_[i]
            val fieldB = b.listvariantfieldtype_[i]
            if (fieldA !is AVariantFieldType || fieldB !is AVariantFieldType) {
                return false
            }

            if (fieldA.stellaident_ != fieldB.stellaident_) {
                return false
            }
        }
        return true
    }

    fun checkIfRecordsAreEqual(actual: TypeRecord, expected: TypeRecord): Boolean {
        if (actual.listrecordfieldtype_.size != expected.listrecordfieldtype_.size) return false
        return actual.listrecordfieldtype_.all { expected.listrecordfieldtype_.contains(it) }
    }

    fun checkTheEquality(actual: Type, expected: Type) {
        when {
            actual is TypeVariant && expected is TypeVariant -> {
                if (!compareVariants(actual, expected)) {
                    throw TypingException(
                        TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                        "Expected type $expected but got $actual"
                    )
                }
            }

            actual is TypeRecord && expected is TypeRecord -> {
                if (!checkIfRecordsAreEqual(actual, expected)) {
                    throw TypingException(
                        TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                        "Expected type ${expected.listrecordfieldtype_} but got ${actual.listrecordfieldtype_}"
                    )
                }
            }

            actual != expected -> {
                throw TypingException(
                    TypingExceptionTypes.ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION,
                    "Expected type $expected but got $actual"
                )
            }
        }
    }

}

object OptionalTypingUtils {
    fun getTypeFromOptional(opt: OptionalTyping?): Type? {
        if (opt is SomeTyping) {
            return opt.type_
        }
        return TypeBottom()
    }
}
