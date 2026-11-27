/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.reference

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.tang.intellij.lua.lang.type.LuaString
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.LuaCallExpr
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLiteralKind
import com.tang.intellij.lua.psi.LuaNameExpr
import com.tang.intellij.lua.psi.kind

/**
 * A file named by a string literal, such as `"tests/psi/test_psi.lua"` in a
 * test runner's file list.
 *
 * <p>Such a list usually lives in `tests/run_tests.lua` and names its files
 * from the project root, so the path is looked up relative to the literal's
 * own directory and then up its ancestors; a path relative to the directory
 * itself (`"a.lua"`, `"../a.lua"`) is found on the first step.
 */
class LuaFilePathReference(
    element: PsiElement,
    private val path: String,
    rangeInElement: TextRange
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true) {

    override fun resolve(): PsiElement? {
        val file = resolveFile() ?: return null
        return PsiManager.getInstance(myElement.project).findFile(file)
    }

    private fun resolveFile(): VirtualFile? {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isEmpty()) {
            return null
        }
        val fileSystem = LocalFileSystem.getInstance()
        if (normalized.startsWith("/")) {
            fileSystem.findFileByPath(normalized)?.let { if (!it.isDirectory) return it }
        }
        val project = myElement.project
        val base = project.basePath?.let { fileSystem.findFileByPath(it) }
        var dir = myElement.containingFile?.virtualFile?.parent
        var levels = 0
        while (dir != null && levels < MAX_LEVELS) {
            findRelative(normalized, dir)?.let { return it }
            if (dir == base) {
                break
            }
            dir = dir.parent
            levels++
        }
        // A file outside the project still may name one of the content roots.
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            findRelative(normalized, root)?.let { return it }
        }
        return null
    }

    private fun findRelative(relativePath: String, dir: VirtualFile): VirtualFile? {
        val file = VfsUtil.findRelativeFile(relativePath, dir) ?: return null
        return if (file.isDirectory) null else file
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val name = if ('/' in newElementName || '\\' in newElementName) {
            newElementName
        } else {
            val slash = path.lastIndexOf('/')
            if (slash < 0) newElementName else path.substring(0, slash + 1) + newElementName
        }
        return ElementManipulators.handleContentChange(myElement, rangeInElement, name)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    companion object {
        /** How far up the containing file may go before the search stops. */
        private const val MAX_LEVELS = 32
    }
}

/**
 * Registers [LuaFilePathReference] on string literals that look like paths to
 * files. The check is purely textual: a literal is not resolved to a file
 * here, so editing a large file list costs nothing.
 */
class LuaFilePathReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? LuaLiteralExpr ?: return PsiReference.EMPTY_ARRAY
        if (literal.kind != LuaLiteralKind.String) {
            return PsiReference.EMPTY_ARRAY
        }
        // require-like calls already carry their own file reference.
        if (isRequireArgument(literal)) {
            return PsiReference.EMPTY_ARRAY
        }
        val content = LuaString.getContent(literal.text)
        val path = content.value
        if (!looksLikeFilePath(path)) {
            return PsiReference.EMPTY_ARRAY
        }
        val range = TextRange(content.start, content.end)
        if (range.isEmpty) {
            return PsiReference.EMPTY_ARRAY
        }
        return arrayOf(LuaFilePathReference(literal, path, range))
    }

    private fun isRequireArgument(literal: LuaLiteralExpr): Boolean {
        val call = PsiTreeUtil.getParentOfType(literal, LuaCallExpr::class.java) ?: return false
        if (call.firstStringArg !== literal) {
            return false
        }
        val name = (call.expr as? LuaNameExpr)?.name ?: return false
        return LuaSettings.isRequireLikeFunctionName(name)
    }

    companion object {
        /** Extensions a literal may end with to be taken for a file path. */
        private val EXTENSIONS = setOf(
            "lua", "json", "yaml", "yml", "xml", "txt", "csv", "conf", "ini", "md"
        )

        /** Characters that never appear in a real path of a file name. */
        private val FORBIDDEN = charArrayOf('\n', '\r', '\t', '*', '?', '<', '>', '|', '"', '\'')

        private fun looksLikeFilePath(path: String): Boolean {
            if (path.length !in 5..MAX_LENGTH) {
                return false
            }
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return false
            }
            if (path.any { it in FORBIDDEN }) {
                return false
            }
            val dot = path.lastIndexOf('.')
            val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            if (dot <= slash) {
                return false
            }
            return path.substring(dot + 1).lowercase() in EXTENSIONS
        }

        private const val MAX_LENGTH = 260
    }
}
