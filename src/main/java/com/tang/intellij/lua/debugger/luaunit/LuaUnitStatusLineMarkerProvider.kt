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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.psi.LuaTypes
import java.nio.file.Path

/**
 * The gutter mark of a case's line: a green tick or a red cross after a run,
 * and the crossed circle of an ignored case — before any run as well.
 *
 * <p>A commented-out case never runs, so the run index knows nothing about it.
 * Its mark comes from the text discovery ([LuaUnitIgnoredCases]) and sits on
 * the declaration line: the line of the commented-out `function`, or the
 * `id = ...` line of a commented-out data row. A block comment holds a whole
 * region as one token, so its mark is placed on the ignored line inside it,
 * not on the `--[[` opener.
 *
 * <p>One marker per line: the only leaf that answers is the first non-blank
 * one. The slow pass (a list of all elements at once) put a second marker
 * on the same line when the daemon split the file into ranges — a failed
 * `id` showed two icons.
 *
 * <p>The declaration of a suite table (`SomeSuite = {}`) gets the same mark,
 * aggregated over the tests declared on that table — the way the test tree
 * paints a suite node.
 *
 * <p>The manifest of the suite gets the marks too, on the very lines its
 * run icons sit on: the first line (the whole suite), a section title (its
 * group) and an entry (that file). Each mark is the state of the cases the
 * icon runs, so the editor shows where the last run failed without opening
 * the test files.
 */
class LuaUnitStatusLineMarkerProvider : LineMarkerProvider {

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
        // The manifest of the suite carries the run icons; the marks of the
        // results sit on the very same lines.
        if (isManifest(virtualFile)) {
            return manifestStatus(element, file, virtualFile, line, offset, first)
        }
        val index = LuaUnitRunIndex.getInstance(project)
        val path = LuaUnitRunIndex.canonical(virtualFile.path)
        if (offset == first) {
            val declared = index.casesDeclaredIn(path)
                .filter {
                    !it.setup && !it.fileLevel && it.declarationLine == line &&
                        LuaUnitCaseLines.declares(document, line, it)
                }
            if (declared.isNotEmpty()) {
                return statusMarker(element, declared, "LuaUnit")
            }
            // The suite's own line: its state is the state of its tests, the
            // way the test tree paints a suite node. The name comes from the
            // file itself (`function SomeSuite:test_x()`), so a suite called
            // anything at all gets its mark.
            val suite = LuaUnitSuites.declaredAt(chars, offset)
            if (suite != null) {
                val cases = index.casesDeclaredIn(path).filter { it.caseName.startsWith("$suite.") }
                if (cases.isNotEmpty()) {
                    return statusMarker(element, cases, "LuaUnit: $suite")
                }
            }
        }
        // The ignored mark: only a comment can declare a case that never
        // runs. A line comment starts its line; a block comment covers the
        // lines of every case it hides.
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
        val start = element.textRange.startOffset
        val end = element.textRange.endOffset
        val target = ignored.firstOrNull { case ->
            val lineStartOffset = document.getLineStartOffset(
                (case.declarationLine - 1).coerceIn(0, document.lineCount - 1)
            )
            lineStartOffset in start..end
        } ?: return null
        val targetOffset = document.getLineStartOffset(
            (target.declarationLine - 1).coerceIn(0, document.lineCount - 1)
        )
        return LineMarkerInfo(
            file, TextRange(targetOffset, targetOffset + 1), PoolOfTestIcons.IGNORED_ICON,
            com.intellij.util.Function<PsiElement, String> { "LuaUnit: ignored (${target.caseName})" }, null,
            GutterIconRenderer.Alignment.LEFT, java.util.function.Supplier { "LuaUnit: ignored" }
        )
    }

    /** True when the file itself is the manifest of the suite, not a test file. */
    private fun isManifest(virtualFile: VirtualFile): Boolean =
        LuaTestManifest.MANIFEST_NAMES.any { it == virtualFile.name }

    /**
     * The mark of a manifest line: the first line of the file (the "Run all"
     * icon), a section title (its group) or an entry (that file). The state is
     * aggregated over the cases the icon runs — the way the test tree paints a
     * group node. Null until a run has told the index something about them.
     */
    private fun manifestStatus(
        element: PsiElement,
        file: PsiFile,
        virtualFile: VirtualFile,
        line: Int,
        offset: Int,
        first: Int
    ): LineMarkerInfo<*>? {
        val path = Path.of(virtualFile.path)
        val directory = LuaUnitProjectRoot.of(path) ?: path.parent ?: path.toAbsolutePath().parent
        val text = file.text
        val index = LuaUnitRunIndex.getInstance(element.project)
        // The first line runs everything: the "Run all" icon sits on the first
        // leaf of the file, whatever that leaf is.
        if (element == PsiTreeUtil.getDeepestFirst(file)) {
            return filesStatus(element, index, LuaTestManifest.filesIn(text, directory), "LuaUnit: all tests")
        }
        // One marker per line: only the first leaf of the line answers.
        if (offset != first) {
            return null
        }
        val section = LuaTestManifest.sectionAt(text, directory, line)
        if (section != null) {
            return filesStatus(element, index, section.files, "LuaUnit: ${section.name}")
        }
        val entry = LuaTestManifest.entryAt(text, directory, line) ?: return null
        return filesStatus(element, index, listOf(entry), "LuaUnit: ${entry.fileName}")
    }

    /** The state of the cases behind [files], aggregated as the test tree does. */
    private fun filesStatus(
        element: PsiElement,
        index: LuaUnitRunIndex,
        files: List<Path>,
        label: String
    ): LineMarkerInfo<*>? {
        val cases = files.flatMap { index.casesBehind(it.toString()) }
        if (cases.isEmpty()) {
            return null
        }
        return statusMarker(element, cases, label)
    }

    /** The result of the last run: a tick, a cross or the error circle. */
    private fun statusMarker(
        element: PsiElement,
        declared: List<LuaUnitRunIndex.CaseResult>,
        label: String
    ): LineMarkerInfo<*> {
        val failed = declared.any { it.failed }
        // The very icons the test tree paints: a green tick for a pass, a
        // crossed circle for a failed assertion (an expected/actual pair)
        // and the red error circle for anything else.
        val icon = when {
            !failed -> PoolOfTestIcons.PASSED_ICON
            declared.any { it.failed && !it.comparison } -> PoolOfTestIcons.ERROR_ICON
            else -> PoolOfTestIcons.FAILED_ICON
        }
        val tooltip = if (failed) "$label failed" else "$label passed"
        return LineMarkerInfo(
            element, element.textRange, icon,
            com.intellij.util.Function<PsiElement, String> { tooltip }, null,
            GutterIconRenderer.Alignment.LEFT, java.util.function.Supplier { tooltip }
        )
    }
}
