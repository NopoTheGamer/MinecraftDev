/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2017 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.i18n.translations

import com.demonwav.mcdev.facet.MinecraftFacet
import com.demonwav.mcdev.i18n.reference.I18nReference
import com.demonwav.mcdev.platform.mcp.McpModuleType
import com.demonwav.mcdev.platform.mcp.srg.SrgManager
import com.demonwav.mcdev.util.evaluate
import com.demonwav.mcdev.util.extractVarArgs
import com.demonwav.mcdev.util.findModule
import com.demonwav.mcdev.util.getCalls
import com.demonwav.mcdev.util.getCallsReturningResult
import com.demonwav.mcdev.util.isCalling
import com.demonwav.mcdev.util.isReturningResultOf
import com.demonwav.mcdev.util.isSameReference
import com.demonwav.mcdev.util.referencedMethod
import com.demonwav.mcdev.util.substituteParameter
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.MethodSignature
import java.util.regex.Pattern

class TranslationFunction(val className: String, val name: String, val parameterTypes: String,
                          val matchedIndex: Int, val formatting: Boolean, val setter: Boolean = false,
                          val foldParameters: Boolean = false, val prefix: String = "", val suffix: String = "",
                          val obfuscatedName: Boolean = false) {
    fun getMethodName(element: PsiElement): String {
        val srgManager = element.findModule()?.let { MinecraftFacet.getInstance(it, McpModuleType)?.srgManager } ?: SrgManager.findAnyInstance(element.project)
        if (obfuscatedName && srgManager != null) {
            return srgManager.srgMapNow?.mapSrgName(name) ?: name
        } else {
            return name
        }
    }

    fun matches(method: PsiMethod?, paramIndex: Int): Boolean {
        if (method == null) {
            return false
        }
        val scope = GlobalSearchScope.allScope(method.project)
        val psiClass = JavaPsiFacade.getInstance(method.project).findClass(className, scope) ?: return false
        val referenceMethod = psiClass.findMethodsByName(getMethodName(method), false)
            .firstOrNull { convertSignatureToDescriptor(it.getSignature(PsiSubstitutor.EMPTY)) == parameterTypes } ?: return false
        if (setter) {
            return method.isCalling(referenceMethod, paramIndex, matchedIndex)
        } else {
            return method.isReturningResultOf(referenceMethod, paramIndex, matchedIndex)
        }
    }

    fun convertSignatureToDescriptor(signature: MethodSignature): String {
        fun typeToDesc(type: String): String = when (type) {
            "byte" -> "B"
            "char" -> "C"
            "double" -> "D"
            "float" -> "F"
            "int" -> "I"
            "long" -> "J"
            "short" -> "S"
            "boolean" -> "Z"
            else ->
                if (type.endsWith(']')) {
                    val dimension = type.count { it == '[' }
                    "[".repeat(dimension) + typeToDesc(type.takeWhile { it != '[' })
                } else {
                    "L$type;"
                }
        }

        return signature.parameterTypes.map { typeToDesc(it.getCanonicalText(true)) }.joinToString("")
    }

    fun getCalls(call: PsiCall, paramIndex: Int): Iterable<PsiCall> {
        val scope = GlobalSearchScope.allScope(call.project)
        val psiClass = JavaPsiFacade.getInstance(call.project).findClass(className, scope) ?: return emptyList()
        val referenceMethod = psiClass.findMethodsByName(getMethodName(call), false)
            .firstOrNull { convertSignatureToDescriptor(it.getSignature(PsiSubstitutor.EMPTY)) == parameterTypes } ?: return emptyList()
        if (setter) {
            return call.getCalls(referenceMethod, paramIndex, matchedIndex)
        } else {
            return call.getCallsReturningResult(referenceMethod, paramIndex, matchedIndex)
        }
    }

    fun getTranslationKey(call: PsiCall): Pair<Boolean, String>? {
        data class Step(val propagate: Boolean, val validReference: Boolean, val key: String) {
            val result = Pair(validReference, key)
        }

        fun resolveCall(depth: Int, single: Boolean, referenced: PsiMethod, call: PsiCall, acc: Step): Step? {
            if (acc.propagate) {
                return acc
            }
            val method = call.referencedMethod
            val isReferencedMethod = referenced.isSameReference(method)
            val param = call.argumentList?.expressions?.get(matchedIndex)
            if (method != null && param != null) {
                val result = param.evaluate(null, I18nReference.VARIABLE_MARKER) ?: return null
                if (!result.contains(I18nReference.VARIABLE_MARKER) && (depth > 0 || isReferencedMethod)) {
                    return Step(true, single, result)
                }
                return Step(false, true,
                    if (acc.key.contains(I18nReference.VARIABLE_MARKER)) result.replace(I18nReference.VARIABLE_MARKER, acc.key) else result)
            }
            return null
        }

        val calls = getCalls(call, matchedIndex)
        val scope = GlobalSearchScope.allScope(call.project)
        val psiClass = JavaPsiFacade.getInstance(call.project).findClass(className, scope) ?: return null
        val referenced = psiClass.findMethodsByName(getMethodName(psiClass), false)
            .firstOrNull { convertSignatureToDescriptor(it.getSignature(PsiSubstitutor.EMPTY)) == parameterTypes } ?: return null
        val result = calls.foldIndexed(Step(false, true, "") as Step?,
            {
                depth, acc, v ->
                if (acc == null) acc else resolveCall(depth, calls.count() == 1, referenced, v, acc)
            })?.result
        return result?.copy(second = prefix + result.second + suffix)
    }

    fun format(translation: String, topCall: PsiCall): Pair<String, Int>? {
        if (!formatting) {
            return translation to -1
        }
        val format = NUMBER_FORMATTING_PATTERN.matcher(translation).replaceAll("%$1s")

        fun resolveCall(call: PsiCall, substitutions: Map<Int, Array<String?>?>): Map<Int, Array<String?>?> {
            val method = call.referencedMethod
            val args = call.argumentList?.expressions
            if (method != null && args != null && args.size >= method.parameterList.parametersCount) {
                return method.parameterList.parameters
                    .mapIndexed {
                        i, parameter ->
                        if (parameter.isVarArgs) {
                            val varargType = method.getSignature(PsiSubstitutor.EMPTY).parameterTypes[i]
                            Pair(i, extractVarArgs(varargType, args.drop(i), substitutions, true, true))
                        } else {
                            Pair(i, args[i].substituteParameter(substitutions, true, true))
                        }
                    }.toMap()
            } else {
                return emptyMap()
            }
        }

        val calls = getCalls(topCall, matchedIndex)
        val paramCount = STRING_FORMATTING_PATTERN.matcher(format).let {
            var count = 0
            while (it.find()) {
                count++
            }
            return@let count
        }
        val substitutions = if (calls.count() > 1) calls.take(calls.count() - 1).fold(emptyMap<Int, Array<String?>?>(), { acc, v -> resolveCall(v, acc) }) else emptyMap()
        val referenceCall = if (calls.count() > 1) calls.last() else calls.first()
        val method = referenceCall.referencedMethod ?: return translation to -1
        val varargs = referenceCall.extractVarArgs(method.parameterList.parametersCount - 1, substitutions, calls.count() <= 1, true)
        if (varargs.any { it == null }) {
            return null
        }
        val varargStart = if (varargs.size > paramCount) method.parameterList.parametersCount - 1 + paramCount else -1
        return String.format(format, *varargs) to varargStart
    }

    override fun toString(): String {
        return "$className.$name@$matchedIndex"
    }

    companion object {
        val NUMBER_FORMATTING_PATTERN = Pattern.compile("%(\\d+\\$)?[\\d\\.]*[df]")
        val STRING_FORMATTING_PATTERN = Pattern.compile("[^%]?%(?:\\d+\\$)?s")
    }
}
