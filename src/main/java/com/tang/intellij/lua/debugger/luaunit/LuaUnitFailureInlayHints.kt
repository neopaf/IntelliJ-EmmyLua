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
import com.intellij.codeInsight.hints.InlayPresentationFactory
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The place a case failed at, with the assertion's own text right after it.
 *
 * <p>For a plain test the failure sits on its assertion line. A generated case
 * is different: its assertion is a line of the generator every row shares, so
 * the failure is shown at the row of the data module (`id = "..."`) instead —
 * and, when the assertion names the field it compared (`Test failed: NAME:
 * out_id`), at that very field of the row.
 *
 * <p>A short message is shortened; a click opens the expected/actual diff of
 * the assertion (expected on the left, actual on the right), or the whole
 * message when the failure had no two values.
 */
class LuaUnitFailureInlayHints : InlayHintsProvider<NoSettings> {

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
        val index = LuaUnitRunIndex.getInstance(file.project)
        val path = LuaUnitRunIndex.canonical(virtualFile.path)
        if (!index.hasResultsFor(path)) {
            return EmptyCollector()
        }
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("inlay request: ${virtualFile.name}")
        return Collector(file.project, editor, index, path)
    }

    override fun createSettings(): NoSettings = NoSettings()

    override val name: String = "LuaUnit failures"

    override val key: SettingsKey<NoSettings> = KEY

    override val previewText: String = "❌ Expected 200, got 403"

    override val isVisibleInSettings: Boolean = false

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener): JComponent =
                JPanel()
        }

    /** Nothing to show: the file has no failures of the last run. */
    private class EmptyCollector : InlayHintsCollector {
        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean = true
    }

    /** One hint: the line to mark, what to say and the record behind it. */
    class Site(
        val line: Int,
        val text: String,
        val failure: LuaUnitRunIndex.CaseResult,
        /** Failures merged into this hint besides [failure]. */
        val extra: Int? = null
    )

    /**
     * One pass over the file: the platform calls [collect] for every element,
     * and the hints are built and added on the first call only.
     */
    private class Collector(
        private val project: Project,
        editor: Editor,
        private val index: LuaUnitRunIndex,
        private val path: String
    ) : FactoryInlayHintsCollector(editor) {

        private var collected = false

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (collected) {
                return true
            }
            collected = true
            val document = editor.document
            val computed = sites(element.containingFile, document.lineCount)
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("inlay collect: ${element.containingFile.name} sites=${computed.size}")
            for (site in computed) {
                val line = site.line - 1
                if (line < 0 || line >= document.lineCount) {
                    continue
                }
                val offset = insertionOffset(element.containingFile, document, site.line)
                // One presentation for the whole hint. Three elements at one
                // offset are laid out by the platform on its own terms (the
                // last one may hide the others), and the `-- ` prefix, the
                // icon and the text must read as one mark anyway.
                //
                // Only a failed assertion is a link: it opens the diff, so it
                // is drawn in the scheme's link colour and underlined. An
                // `error()` has no diff to open: its text is plain bright red,
                // with no underline and no hand cursor to promise a click.
                val diff = site.failure.expected != null && site.failure.actual != null
                val presentation = FailurePresentation(
                    iconOf(site.failure),
                    if (site.extra != null && site.extra > 0) {
                        "${site.text} (+${site.extra} ${if (site.extra == 1) "error" else "errors"})"
                    } else {
                        site.text
                    },
                    diff
                )
                val shown = if (diff) {
                    factory.mouseHandling(
                        presentation,
                        object : InlayPresentationFactory.ClickListener {
                            override fun onClick(event: MouseEvent, point: Point) {
                                openDetails(site)
                            }
                        },
                        null
                    )
                } else {
                    presentation
                }
                sink.addInlineElement(offset, true, shown, false)
            }
            return true
        }

        /**
         * The failures of this file. A plain case is marked on its assertion;
         * a generated one on the data row it came from (or on the field the
         * assertion names). Two cases of a loop share one assertion, so a plain
         * line is marked only when exactly one case failed there.
         */
        private fun sites(file: PsiFile, lineCount: Int): List<Site> {
            val cases = index.casesDeclaredIn(path)
            val sites = ArrayList<Site>()
            val shared = ArrayList<Site>()

            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            for (case in cases) {
                if (!case.failed || document == null) {
                    continue
                }
                // A data case belongs to its row: the field the assertion
                // named, or the row's `id` line. The body of its generator is
                // shared by every row, so the assertion line there says nothing
                // about this very case.
                val line = LuaUnitFailureInlayHints.failureLine(file, document, case)
                if (line < 1) {
                    continue
                }
                if (case.fileLevel) {
                    // The file's own failure: the hint goes to the first line,
                    // to the right of whatever it holds.
                    sites.add(Site(line, textFor(case), case))
                    continue
                }
                if (LuaUnitFailureInlayHints.isDataRow(file, document, case)) {
                    sites.add(Site(line, textFor(case), case))
                } else if (line != case.declarationLine) {
                    // The assertion is a line of its own: the text sits there.
                    // The same line may serve several cases of a loop, so it is
                    // marked only when exactly one case shares it.
                    shared.add(Site(line, textFor(case), case))
                } else {
                    // Nothing better than the declaration: the text goes to
                    // the right of it, before a comment if the line has one.
                    sites.add(Site(line, textFor(case), case))
                }
            }
            // The assertion of a case declared in another file: a helper of the
            // suite (the data comparison lives in test_common.lua). The tree's
            // double-click opens this very line, so the hint sits here, not on
            // the declaration back in the suite.
            for (failure in index.failuresIn(path)) {
                if (failure.declarationFile == path) {
                    continue
                }
                if (failure.errorLine < 1 || failure.errorLine > lineCount) {
                    continue
                }
                sites.add(Site(failure.errorLine, textFor(failure), failure))
            }
            // A line shared by several cases (a loop over a table, or two
            // generators) says nothing about one case: it is left alone.
            for (group in shared.groupBy { it.line }.values) {
                if (group.size == 1) {
                    sites.add(group.first())
                }
            }
            // One hint per line: the first failure says what broke, the rest
            // are only counted — a stack of messages on one line is unreadable.
            val lines = LinkedHashMap<Int, Site>()
            for (site in sites) {
                val known = lines[site.line]
                if (known == null) {
                    lines[site.line] = site
                } else {
                    val count = (known.extra ?: 0) + 1 + (site.extra ?: 0)
                    lines[site.line] = Site(known.line, known.text, known.failure, count)
                }
            }
            return lines.values.filter { it.line in 1..lineCount }
        }

        /**
         * Where the hint goes on its line: right after the code, before the
         * `--` comment when the line has one (a hint after the comment would
         * read as part of it).
         *
         * <p>The comment is a token of the Lua grammar (`SHORT_COMMENT`), not
         * a [com.intellij.psi.PsiComment] instance: only a leaf walk finds it,
         * which is why the hint used to land at the end of the line.
         */
        private fun insertionOffset(
            file: PsiFile,
            document: com.intellij.openapi.editor.Document,
            line: Int
        ): Int {
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

        /** The icon of the test tree: a failed assertion or an error. */
        private fun iconOf(failure: LuaUnitRunIndex.CaseResult): javax.swing.Icon =
            if (failure.comparison) {
                com.intellij.execution.testframework.PoolOfTestIcons.FAILED_ICON
            } else {
                com.intellij.execution.testframework.PoolOfTestIcons.ERROR_ICON
            }

        /** `Expected 200, got 403`, shortened when the message is long. */
        private fun textFor(failure: LuaUnitRunIndex.CaseResult): String =
            LuaUnitFailureText.text(failure)

        /** The diff of the assertion, or the whole message when there is no pair. */
        private fun openDetails(site: Site) {
            val failure = site.failure
            val expected = failure.expected
            val actual = failure.actual
            if (expected != null && actual != null) {
                val factory = DiffContentFactory.getInstance()
                DiffManager.getInstance().showDiff(
                    project,
                    SimpleDiffRequest(
                        "LuaUnit: ${failure.displayName}",
                        factory.create(expected),
                        factory.create(actual),
                        "Expected",
                        "Actual"
                    )
                )
            } else if (failure.message.isNotBlank()) {
                JBPopupFactory.getInstance().createMessage(failure.message).showInFocusCenter()
            }
        }
    }

    /**
     * One failure hint: the `-- ` of a Lua remark in the comment colour, the
     * icon of the test tree and the text. A failed assertion is a link to its
     * diff — the scheme's link colour and an underline; an `error()` is plain
     * bright red.
     */
    private class FailurePresentation(
        private val icon: javax.swing.Icon,
        private val label: String,
        /** The hint opens a diff, so it looks like a link. */
        private val link: Boolean
    ) : com.intellij.codeInsight.hints.presentation.BasePresentation() {

        private val font = com.intellij.util.ui.JBFont.label()
        private val metrics = java.awt.Canvas().getFontMetrics(font)

        /** Some icons ask for the component they paint into. */
        private val component = JPanel()

        private val prefixColor: java.awt.Color
        get() = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
            .globalScheme
            .getAttributes(com.intellij.openapi.editor.SyntaxHighlighterColors.LINE_COMMENT)
            ?.foregroundColor
            ?: com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
                .globalScheme.defaultForeground

        private val textColor: java.awt.Color
            get() = if (link) LuaUnitErrorColors.link else LuaUnitErrorColors.text

        private val prefixWidth: Int
            get() = metrics.stringWidth(PREFIX)

        private val iconWidth: Int
            get() = icon.iconWidth

        private val iconHeight: Int
            get() = icon.iconHeight

        private val textWidth: Int
            get() = metrics.stringWidth(label)

        override val width: Int
            get() = prefixWidth + iconWidth + ICON_GAP + textWidth + GAP

        override val height: Int
            get() = maxOf(metrics.height, iconHeight)

        override fun paint(g: java.awt.Graphics2D, attributes: com.intellij.openapi.editor.markup.TextAttributes) {
            val oldColor = g.color
            val oldFont = g.font
            g.font = font
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            g.color = prefixColor
            g.drawString(PREFIX, 0, baseline)
            val textX = prefixWidth + iconWidth + ICON_GAP
            g.color = textColor
            g.drawString(label, textX, baseline)
            if (link) {
                // The diff hint is a link: the underline and the hand cursor
                // of the click handler invite the click, not a "(click ...)"
                // tail.
                g.drawLine(textX, baseline + 1, textX + textWidth, baseline + 1)
            }
            g.color = oldColor
            g.font = oldFont
            // The icon goes last: a cached icon paints through its own
            // composite and clip, and the text after it came out washed out.
            val oldComposite = g.composite
            val oldClip = g.clip
            icon.paintIcon(component, g, prefixWidth, (height - iconHeight) / 2)
            g.composite = oldComposite
            g.clip = oldClip
        }

        override fun toString(): String = PREFIX + label

        companion object {
            /** The `-- ` that starts a Lua remark. */
            private const val PREFIX = "-- "

            /** The space between the icon and the text. */
            private const val ICON_GAP = 2

            /** The space between the hint and the text that follows it. */
            private const val GAP = 4
        }
    }

    companion object {
        private val KEY: SettingsKey<NoSettings> = SettingsKey("luaunit.failures")

        /**
         * The line the editor marks for a failure: the field the assertion
         * named, the `id` row of a generated case, the assertion of a plain
         * one, and the declaration when nothing better exists. The inlay and
         * the red band of the line ask the same question, so they ask here.
         */
        fun failureLine(
            file: PsiFile,
            document: com.intellij.openapi.editor.Document,
            failure: LuaUnitRunIndex.CaseResult
        ): Int {
            // A failure of the file itself (a load error, a plain script that
            // threw) has no declaration to point at: the traceback names the
            // line that threw, and the mark goes there. Without a trace the
            // first line of the file is the place it belongs to.
            if (failure.fileLevel) {
                if (failure.errorFile == failure.declarationFile &&
                    failure.errorLine in 2..document.lineCount
                ) {
                    val line = alignedLine(document, failure.errorLine, failure.errorText)
                    if (line > 0) {
                        return line
                    }
                }
                return if (document.lineCount >= 1) 1 else 0
            }
            if (failure.declarationLine < 1 || failure.declarationLine > document.lineCount) {
                return 0
            }
            // The file may have changed since the run: a record whose line no
            // longer declares the case is stale, and a hint of it would land
            // in the body of another case.
            if (!LuaUnitCaseLines.declares(document, failure.declarationLine, failure)) {
                return 0
            }
            if (isDataRow(file, document, failure)) {
                val attribute = LuaUnitDataRows.namedFieldLine(
                    file, document, failure.declarationLine, failure.message
                )
                return if (attribute > 0) attribute else failure.declarationLine
            }
            // The assertion lives in another file (a helper the case calls):
            // the tree's double-click goes there, and the mark has to be at
            // the same line — a hint on the declaration would point at a place
            // the click never takes the reader.
            if (failure.errorFile.isNotEmpty() && failure.errorFile != failure.declarationFile) {
                return 0
            }
            return if (
                failure.errorFile == failure.declarationFile &&
                failure.errorLine > 0 &&
                failure.errorLine != failure.declarationLine &&
                failure.errorLine <= document.lineCount
            ) {
                alignedLine(document, failure.errorLine, failure.errorText)
            } else {
                failure.declarationLine
            }
        }

        /**
         * The line the stored [text] is on, near the stored [line]: an edit
         * above moves the text, not the number. 0 when the text is nowhere
         * around — a rewritten case has no assertion to point at any more.
         */
        private fun alignedLine(
            document: com.intellij.openapi.editor.Document,
            line: Int,
            text: String
        ): Int {
            if (text.isEmpty()) {
                return line
            }
            if (lineText(document, line) == text) {
                return line
            }
            for (delta in 1..ALIGN_RANGE) {
                for (candidate in intArrayOf(line - delta, line + delta)) {
                    if (candidate in 1..document.lineCount && lineText(document, candidate) == text) {
                        return candidate
                    }
                }
            }
            return 0
        }

        /** The text of [line] (1-based), trimmed. */
        private fun lineText(document: com.intellij.openapi.editor.Document, line: Int): String {
            if (line < 1 || line > document.lineCount) {
                return ""
            }
            return document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(line - 1),
                    document.getLineEndOffset(line - 1)
                )
            ).trim()
        }

        /** True when the case is declared by an `id` row of a data module. */
        fun isDataRow(
            file: PsiFile,
            document: com.intellij.openapi.editor.Document,
            failure: LuaUnitRunIndex.CaseResult
        ): Boolean =
            failure.declarationLine in 1..document.lineCount &&
                LuaUnitDataRows.idField(file, document, failure.declarationLine) != null

        /** How far from the stored line the assertion text is looked for. */
        private const val ALIGN_RANGE = 40

        /** The leaves the Lua grammar marks a comment with. */
        private val COMMENTS = com.intellij.psi.tree.TokenSet.create(
            com.tang.intellij.lua.psi.LuaTypes.SHORT_COMMENT,
            com.tang.intellij.lua.psi.LuaTypes.BLOCK_COMMENT,
            com.tang.intellij.lua.psi.LuaTypes.DOC_COMMENT
        )
    }
}
