package org.stella.typecheck

import org.syntax.stella.Absyn.*
import kotlin.apply
import kotlin.collections.asReversed
import kotlin.collections.firstNotNullOfOrNull
import kotlin.collections.last
import kotlin.collections.plusAssign
import kotlin.collections.removeLast
import kotlin.collections.set

class Context {
    private val variableScopes = mutableListOf(linkedMapOf<String, Type>())
    private val functionTypes = linkedMapOf<String, Type>()
    private val extensions = linkedSetOf<String>()
    private var _hasMainFunction = false
    var exceptionType: Type? = null
        private set

    init {
        addBuiltinFunctions()
    }

    private fun addBuiltinFunctions() {
        val isZeroType = TypeFun(
            ListType().apply { add(TypeNat()) },
            TypeBool()
        )
        addFunction("Nat::iszero", isZeroType)
    }

    fun pushScope() = variableScopes.add(linkedMapOf())

    fun popScope() {
        if (variableScopes.size > 1) variableScopes.removeLast()
    }

    fun addVariable(name: String, type: Type) {
        variableScopes.last()[name] set type
    }

    fun lookupVariable(name: String): Type? =
        variableScopes.asReversed().firstNotNullOfOrNull { it[name] }

    fun addFunction(name: String, type: Type) {
        functionTypes[name] set type
        if (name == "main") _hasMainFunction = true
    }

    fun lookupFunction(name: String): Type? = functionTypes[name]

    val hasMainFunction: Boolean
        get() = _hasMainFunction

    fun addExtension(name: String) {
        extensions plusAssign name
    }

    fun hasExtension(name: String): Boolean = name in extensions

    fun setExceptionType(type: Type) {
        exceptionType = type
    }
}
