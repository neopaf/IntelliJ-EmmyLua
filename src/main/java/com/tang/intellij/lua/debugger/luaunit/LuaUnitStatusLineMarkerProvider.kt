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

package com.tang.intellij.lua.debugger.luaunit

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.testframework.PoolOfTestIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.tang.intellij.lua.psi.LuaTypes
import java.nio.file.Path

/**
 * The gutter mark of a case that never runs: the crossed circle of an ignored
 * case.
 *
 * <p>Everything else a line shows about a run — the arrow and the result of the
 * last run — is one icon of [LuaUnitRunLineMarkerContributor]: the arrow with
 * the status badge over it. Two separate icons made the gutter of the whole
 * file a column wider, so the result no longer has a marker of its own.
 *
 * <p>A commented-out case cannot run, so it has no run marker and its ignored
 * mark stays a mark of its own. Its line comes from the text discovery
 * ([LuaUnitIgnoredCases]): the line of the commented-out `function`, or the
 * `id = ...` line of a commented-out data row. A block comment holds a whole
 * region as one token, so its mark is placed on the ignored line inside it, not
 * on the `--[[` opener.
 */
class LuaUnitStatusLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (LuaUnitHighlights.isRunning() || element.firstChild != null) {
            return null
        }
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        // Only a comment can declare a case that never runs. A line comment
        // starts its line; a block comment covers the lines of every case it
        // hides.
        val type = element.node.elementType
        val isComment = type == LuaTypes.SHORT_COMMENT ||
                type == LuaTypes.BLOCK_COMMENT ||
                type == LuaTypes.DOC_COMMENT
        if (!isComment) {
            return null
        }
        val ignored = LuaUnitIgnoredCases.ignoredIn(Path.of(virtualFile.path))
        if (ignored.isEmpty()) {
            return null
        }
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
        // The lines the comment covers. A line start would not do: a commented
        // out row is indented, so its line begins before the comment token, and
        // the token's range never contains the start of its own line.
        val startLine = document.getLineNumber(element.textRange.startOffset) + 1
        val endLine = document.getLineNumber(element.textRange.endOffset) + 1
        val target = ignored.firstOrNull { it.declarationLine in startLine..endLine } ?: return null
        val targetOffset = document.getLineStartOffset(
            (target.declarationLine - 1).coerceIn(0, document.lineCount - 1)
        )
        return LineMarkerInfo(
            file, TextRange(targetOffset, targetOffset + 1), PoolOfTestIcons.IGNORED_ICON,
            com.intellij.util.Function<PsiElement, String> { "LuaUnit: ignored (${target.caseName})" }, null,
            GutterIconRenderer.Alignment.LEFT, java.util.function.Supplier { "LuaUnit: ignored" }
        )
    }
}
