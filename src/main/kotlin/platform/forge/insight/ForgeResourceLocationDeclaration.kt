/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.forge.insight

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.util.regex.Matcher
import java.util.regex.Pattern

class ForgeResourceLocationDeclaration : GotoDeclarationHandler {
    private val resourcePattern: Pattern = Pattern.compile("(\\w+)?:?((.*/)?(.*))")
    private val filePattern: Pattern = Pattern.compile("(.*/main/).*")
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return PsiElement.EMPTY_ARRAY
        if (sourceElement.toString() != "PsiJavaToken:STRING_LITERAL") return PsiElement.EMPTY_ARRAY
        val sourceText = sourceElement.context!!.text.replace("\"", "")
        val resourceMatcher: Matcher = resourcePattern.matcher(sourceText)
        if (!resourceMatcher.matches()) return PsiElement.EMPTY_ARRAY

        val psiElements: MutableList<PsiElement> = ArrayList()
        var path = sourceElement.containingFile.virtualFile.path
        val pathMatcher: Matcher = filePattern.matcher(path)
        if (pathMatcher.matches()) {
            path = pathMatcher.group(1) + "resources/assets/" + resourceMatcher.group(1) + "/" + resourceMatcher.group(2)
            val a = VirtualFileManager.getInstance().findFileByUrl(
                "file://$path"
            )
            if (a != null && a.exists()) {
                PsiManager.getInstance(sourceElement.project).findFile(a)?.let { psiElements.add(it) }
            } else return PsiElement.EMPTY_ARRAY
        } else return PsiElement.EMPTY_ARRAY

        return psiElements.toTypedArray()
    }
}