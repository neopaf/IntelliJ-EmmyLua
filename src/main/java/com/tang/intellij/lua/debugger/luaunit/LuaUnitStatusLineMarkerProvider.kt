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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * The result of the last run in the gutter: a green tick or a red cross to the
 * left of the line that declares a case — a test function, or the `id = "..."`
 * row of a generated case. One row feeds several generators, so the row is red
 * as soon as any of its cases failed.
 *
 * <p>The gutter is the only place an icon can sit next to a line: the code
 * vision above a line is text only, and the word PASSED/FAILED without the
 * tree's own icon reads worse than the icon alone.
 */
class LuaUnitStatusLineMarkerProvider : LineMarkerProvider {

    /**
     * One marker per line: the only leaf that answers is the first non-blank
     * one. The slow pass (a list of all elements at once) put a second marker
     * on the same line when the daemon split the file into ranges — a failed
     * `id` showed two icons.
     */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (LuaUnitHighlights.isRunning() || element.firstChild != null) {
            return null
        }
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val project = element.project
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = element.textRange.startOffset
        val line = document.getLineNumber(offset) + 1
        val lineStart = document.getLineStartOffset(line - 1)
        val lineEnd = document.getLineEndOffset(line - 1)
        // The first non-blank char of the line: any other leaf of the line
        // would put a marker of its own.
        val chars = document.charsSequence
        var first = lineStart
        while (first < lineEnd && (chars[first] == ' ' || chars[first] == '\t')) {
            first++
        }
        if (offset != first) {
            return null
        }
        val index = LuaUnitRunIndex.getInstance(project)
        val path = LuaUnitRunIndex.canonical(virtualFile.path)
        val declared = index.casesDeclaredIn(path).filter { it.declarationLine == line }
        if (declared.isEmpty()) {
            return null
        }
        val failed = declared.any { it.failed }
        // The very icons the test tree paints: a green tick for a pass, a
        // crossed circle for a failed assertion (an expected/actual pair)
        // and the red error circle for anything else.
        val icon = when {
            !failed -> PoolOfTestIcons.PASSED_ICON
            declared.any { it.failed && !it.comparison } -> PoolOfTestIcons.ERROR_ICON
            else -> PoolOfTestIcons.FAILED_ICON
        }
        val tooltip = if (failed) "LuaUnit: failed" else "LuaUnit: passed"
        return LineMarkerInfo(
            element, element.textRange, icon,
            com.intellij.util.Function { tooltip }, null,
            GutterIconRenderer.Alignment.LEFT
        )
    }
}
