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

import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaTypes
import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics2D
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * What became of every case a data row declares, right after the row.
 *
 * <p>One `id = "..."` line may feed several generators: the row declares a
 * case of each of them, and the gutter icon alone cannot say which of those
 * cases ran. The hint names them and their results —
 * `2 cases: decode_ss7 passed · encode_ss7 not run` — so a partial run is
 * visible where the row is written, not only in the menu of the icon.
 *
 * <p>The hint is shown only for a row of two or more cases, only when at least
 * one of them has a result and only when the results differ: a row whose cases
 * all passed is what the green tick already says, and a row no run touched has
 * nothing to report.
 */
class LuaUnitCaseStatusHints : InlayHintsProvider<NoSettings> {

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        val virtualFile = file.virtualFile ?: return EmptyCollector()
        if (LuaUnitHighlights.isRunning()) {
            return EmptyCollector()
        }
        val diskPath = try {
            Path.of(virtualFile.path)
        } catch (e: Exception) {
            return EmptyCollector()
        }
        val manifest = LuaTestManifest.findManifest(diskPath) ?: return EmptyCollector()
        // The manifest lists test files; a data module is not among them. Its
        // rows still declare the cases the listed files generate.
        val inManifest = try {
            LuaTestManifest.files(manifest).any {
                it.toAbsolutePath().normalize() == diskPath.toAbsolutePath().normalize()
            }
        } catch (e: Exception) {
            false
        }
        if (inManifest || manifest.toAbsolutePath().normalize() == diskPath.toAbsolutePath().normalize()) {
            return EmptyCollector()
        }
        val root = LuaUnitProjectRoot.of(manifest) ?: return EmptyCollector()
        val generators = LuaUnitDataSources.find(manifest, diskPath, root)
        if (generators.size < 2) {
            return EmptyCollector()
        }
        val path = LuaUnitRunIndex.canonical(virtualFile.path)
        val index = LuaUnitRunIndex.getInstance(file.project)
        if (!index.hasResultsFor(path)) {
            return EmptyCollector()
        }
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("case status request: ${virtualFile.name}, generators=${generators.size}")
        return Collector(file.project, editor, file, virtualFile, generators)
    }

    override fun createSettings(): NoSettings = NoSettings()

    override val name: String = "LuaUnit case coverage"

    override val key: SettingsKey<NoSettings> = KEY

    override val previewText: String = "2 cases: decode_ss7 passed · encode_ss7 not run"

    override val isVisibleInSettings: Boolean = false

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener): JComponent =
                JPanel()
        }

    /** Nothing to show: the file declares no row of two or more cases. */
    private class EmptyCollector : InlayHintsCollector {
        override fun collect(element: com.intellij.psi.PsiElement, editor: Editor, sink: InlayHintsSink): Boolean = true
    }

    /**
     * One pass over the data module: the platform calls [collect] for every
     * element, and the hints are built and added on the first call only.
     */
    private class Collector(
        private val project: Project,
        private val editor: Editor,
        private val file: PsiFile,
        private val virtualFile: com.intellij.openapi.vfs.VirtualFile,
        private val generators: List<LuaUnitDataSources.Generator>
    ) : FactoryInlayHintsCollector(editor) {

        private var collected = false

        override fun collect(
            element: com.intellij.psi.PsiElement,
            editor: Editor,
            sink: InlayHintsSink
        ): Boolean {
            if (collected) {
                return true
            }
            collected = true
            val document = editor.document
            val dataFile = Path.of(virtualFile.path)
            val seenLines = HashSet<Int>()
            var added = 0
            for (literal in PsiTreeUtil.findChildrenOfType(file, LuaLiteralExpr::class.java)) {
                if (!LuaUnitDataRows.isEntryLiteral(literal)) {
                    continue
                }
                // The `desc` of a row names the case: the hint sits there, and
                // the `id` literal of the same row stays plain data.
                if (!LuaUnitDataRows.isDescField(literal) && LuaUnitDataRows.hasDescSibling(literal)) {
                    continue
                }
                val raw = literal.text
                if (raw.length < 2) {
                    continue
                }
                val line = document.getLineNumber(literal.textRange.startOffset) + 1
                if (!seenLines.add(line)) {
                    continue
                }
                val rowName = LuaUnitDataSources.rowName(
                    dataFile, raw.substring(1, raw.length - 1), literal.textRange.startOffset
                ) ?: continue
                val expected = LuaUnitLineCases.expected(generators, rowName)
                if (expected.size < 2) {
                    continue
                }
                val cases = LuaUnitGutterStatus.casesOnLine(project, virtualFile, document, line)
                // A row no run touched has nothing to report, and a row whose
                // cases all passed is what the green tick says already.
                if (cases.isEmpty()) {
                    continue
                }
                val statuses = LuaUnitLineCases.statuses(expected, cases)
                if (statuses.none { it.second != LuaUnitTestState.PASSED }) {
                    continue
                }
                val offset = insertionOffset(document, line)
                val shown = factory.withTooltip(tooltip(statuses), presentation(statuses))
                sink.addInlineElement(offset, true, shown, false)
                added++
            }
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("case status: ${file.name} hints=$added")
            return true
        }

        /** The statuses as one line of coloured parts. */
        private fun presentation(
            statuses: List<Pair<LuaUnitLineCases.ExpectedCase, LuaUnitTestState?>>
        ): BasePresentation {
            val parts = ArrayList<Pair<String, Color>>()
            parts.add("${statuses.size} cases: " to muted())
            for ((index, item) in statuses.withIndex()) {
                if (index > 0) {
                    parts.add(" · " to muted())
                }
                parts.add(item.first.label + ": " to normal())
                parts.add(statusText(item.second) to statusColor(item.second))
            }
            return StatusPresentation(parts)
        }

        private fun tooltip(
            statuses: List<Pair<LuaUnitLineCases.ExpectedCase, LuaUnitTestState?>>
        ): String = statuses.joinToString("\n") { (case, state) ->
            "${case.caseName} (${case.file.fileName}): ${statusText(state)}"
        }

        private fun statusText(state: LuaUnitTestState?): String = when (state) {
            LuaUnitTestState.PASSED -> "passed"
            LuaUnitTestState.FAILED -> "failed"
            LuaUnitTestState.ERROR -> "error"
            null -> "not run"
        }

        private fun statusColor(state: LuaUnitTestState?): Color = when (state) {
            LuaUnitTestState.PASSED -> PASSED
            LuaUnitTestState.FAILED, LuaUnitTestState.ERROR -> LuaUnitErrorColors.text
            null -> muted()
        }

        private fun normal(): Color =
            EditorColorsManager.getInstance().globalScheme.defaultForeground

        private fun muted(): Color {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)
                ?.foregroundColor ?: scheme.defaultForeground
        }

        /**
         * Where the hint goes on its line: right after the code, before the
         * `--` comment when the line has one (a hint after the comment would
         * read as part of it).
         */
        private fun insertionOffset(document: Document, line: Int): Int {
            val start = document.getLineStartOffset(line - 1)
            val end = document.getLineEndOffset(line - 1)
            var offset = start
            while (offset < end) {
                val leaf = file.findElementAt(offset) ?: break
                if (leaf.node.elementType in COMMENTS) {
                    return maxOf(start, leaf.textRange.startOffset)
                }
                val next = leaf.textRange.endOffset
                offset = if (next > offset) next else offset + 1
            }
            return end
        }
    }

    /** One status line: coloured parts drawn side by side. */
    private class StatusPresentation(
        private val parts: List<Pair<String, Color>>
    ) : BasePresentation() {

        private val font = JBFont.label()
        private val metrics = Canvas().getFontMetrics(font)

        override val width: Int
            get() = parts.sumOf { metrics.stringWidth(it.first) } + GAP

        override val height: Int
            get() = metrics.height

        override fun paint(g: Graphics2D, attributes: TextAttributes) {
            val oldFont = g.font
            val oldColor = g.color
            g.font = font
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            var x = 0
            for ((text, color) in parts) {
                g.color = color
                g.drawString(text, x, baseline)
                x += metrics.stringWidth(text)
            }
            g.font = oldFont
            g.color = oldColor
        }

        override fun toString(): String = parts.joinToString("") { it.first }
    }

    companion object {

        private val KEY: SettingsKey<NoSettings> = SettingsKey("luaunit.caseStatus")

        /** The green of a passed case, readable on both themes. */
        private val PASSED: Color = JBColor(Color(0x08, 0x8B, 0x08), Color(0x62, 0xC5, 0x54))

        /** The comment tokens, the only leaves that start a `-- ...` remark. */
        private val COMMENTS = setOf(LuaTypes.SHORT_COMMENT, LuaTypes.BLOCK_COMMENT, LuaTypes.DOC_COMMENT)

        /** A little room after the text, so the hint does not touch the code. */
        private const val GAP = 4
    }
}
