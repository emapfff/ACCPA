package org.stella.typecheck

enum class TypingExceptionTypes(val defaultMessage: String) {
    ERROR_MISSING_MAIN("A program is missing a main function"),
    ERROR_UNEXPECTED_TYPE_FOR_PARAMETER("Unexpected type specified for a parameter"),
    ERROR_UNEXPECTED_TYPE_FOR_EXPRESSION("Type of an expression does not match the expected type"),
    ERROR_UNEXPECTED_LAMBDA("Unexpected anonymous function where a non-function type is expected"),
    ERROR_NOT_A_FUNCTION("Unexpected expression where a function is expected"),
    ERROR_UNDEFINED_VARIABLE("Undefined variable in an expression"),

    ERROR_AMBIGUOUS_SUM_TYPE("Cannot typecheck an expression of a sum type because the other half of the type is unknown"),
    ERROR_NONEXHAUSTIVE_MATCH_PATTERNS("Match-expression is missing cases for sum types"),
    ERROR_ILLEGAL_EMPTY_MATCHING("Match-expression does not have any patterns"),
    ERROR_UNEXPECTED_PATTERN_FOR_TYPE("Pattern in a match-expression does not match the expected type"),

    ERROR_AMBIGUOUS_LIST_TYPE("Cannot typecheck an expression of a list type because the type of its elements is unknown"),
    ERROR_NOT_A_LIST("Unexpected expression where a list is expected"),

    ERROR_AMBIGUOUS_REFERENCE_TYPE("Bare memory address found without an expected type"),
    ERROR_AMBIGUOUS_PANIC_TYPE("Panic expression found without an expected type"),
    ERROR_NOT_A_REFERENCE("Attempted to assign or dereference a non-reference type"),
    ERROR_UNEXPECTED_MEMORY_ADDRESS("Unexpected memory address literal found"),

    ERROR_NOT_A_RECORD("Unexpected expression where a record is expected"),
    ERROR_UNEXPECTED_FIELD_ACCESS("Attempted to access a field that is not present in the record"),

    ERROR_EXCEPTION_TYPE_NOT_DECLARED("Exception mechanism used without a globally declared exception type"),
    ERROR_AMBIGUOUS_THROW_TYPE("Cannot infer the type of a throw expression"),

    ERROR_UNEXPECTED_TUPLE("Unexpected tuple/pair where an expression of a non-tuple type is expected"),
    ERROR_TUPLE_INDEX_OUT_OF_BOUNDS("Tuple index out of bounds"),
    ERROR_NOT_A_TUPLE("Unexpected expression where a tuple/pair is expected"),

    ERROR_AMBIGUOUS_VARIANT_TYPE("Cannot infer type for variant (needs a type ascription)");

    override fun toString(): String = "$name: $defaultMessage"
}
