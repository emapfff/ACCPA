package org.stella.typecheck

class TypingException(
    val errorType: TypingExceptionTypes,
    additionalMessage: String? = null
) : RuntimeException(
    additionalMessage ?: errorType.defaultMessage
) {
    override fun toString(): String {
        return "TypingException(errorType=${errorType.name}, message='${message}')"
    }
}
