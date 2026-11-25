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

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import javax.swing.BoxLayout
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * The panel of a data file a run saw a test open: which tests read it, and
 * whether they passed.
 *
 * <p>The tests sit in a row at the left, separated by commas, each name a link
 * of its own: a click goes to the declaration of that case. The link's colour
 * is the result — green for a passed case, red for a failed one.
 *
 * <p>The panel is shown only for the files of the run — a plain resource that
 * no test opened keeps its editor clean.
 */
class LuaUnitFixtureNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile
    ): Function<in FileEditor, out JComponent>? {
        if (LuaUnitHighlights.isRunning()) {
            return null
        }
        val tests = LuaUnitRunIndex.getInstance(project).testsUsing(file.path)
            // The file may have changed since the run: a case that is no
            // longer declared where the run saw it is stale, and its link
            // would take the reader into the middle of another case.
            .filter { alive(it) }
        if (tests.isEmpty()) {
            return null
        }
        return Function { _ -> createPanel(project, tests) }
    }

    /**
     * The links of the header: one line, comma separated, wrapped by the
     * panel's width. A rich text pane is the only standard component that
     * keeps several links in one flowing paragraph and wraps them.
     */
    private fun createPanel(project: Project, tests: List<LuaUnitRunIndex.CaseResult>): JComponent {
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
        panel.removeAll()
        // The icon and the first link must not touch: the panel's layout gives
        // no gap of its own.
        val icon = JBLabel(AllIcons.Nodes.Test)
        icon.border = com.intellij.util.ui.JBUI.Borders.emptyRight(6)
        panel.add(icon, BorderLayout.WEST)
        panel.add(linksPane(project, tests), BorderLayout.CENTER)
        return panel
    }

    private fun linksPane(project: Project, tests: List<LuaUnitRunIndex.CaseResult>): JComponent {
        // The cases follow the order they are declared in the files, the way
        // the tree shows them: a suite's own order, not the alphabet.
        val ordered = tests.sortedWith(
            compareBy(
                { it.declarationFile },
                { it.declarationLine },
                { it.caseName }
            )
        )
        val shown = ordered.take(MAX_SHOWN)
        val rest = ordered.drop(MAX_SHOWN)
        val html = StringBuilder("<html><body style='margin:0'>")
        fun link(href: String, text: String, color: Color) {
            html.append("<a href=\"").append(href).append("\" style=\"color:#")
                .append(String.format("%06X", color.rgb and 0xFFFFFF))
                .append("\">").append(escape(text)).append("</a>")
        }
        shown.forEachIndexed { index, test ->
            if (index > 0) {
                html.append(", ")
            }
            link("t$index", label(shown, test), if (test.failed) FAILED_COLOR else PASSED_COLOR)
        }
        if (rest.isNotEmpty()) {
            if (shown.isNotEmpty()) {
                html.append(", ")
            }
            // Only the text inside the parentheses is a link.
            html.append("(")
            link("more", "+${rest.size} more", LINK_COLOR)
            html.append(")")
        }
        html.append("</body></html>")
        val pane = javax.swing.JEditorPane("text/html", html.toString())
        pane.isEditable = false
        pane.isOpaque = false
        pane.font = com.intellij.util.ui.UIUtil.getLabelFont()
        pane.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) {
                return@addHyperlinkListener
            }
            val href = event.description ?: return@addHyperlinkListener
            if (href == "more") {
                showMore(project, pane, rest)
                return@addHyperlinkListener
            }
            val index = href.removePrefix("t").toIntOrNull() ?: return@addHyperlinkListener
            shown.getOrNull(index)?.let { test -> openTest(project, test) }
        }
        return pane
    }

    /**
     * The name to show: the shortest one that still names one case.
     *
     * <p>A case of its own is shown by its name (`test_x`). A name two cases
     * share is widened by the part that tells them apart — the suite when both
     * live in one file (`TestSuite:test_x`), the file when the suites do not
     * help (`test_x (suite.lua)`), and both parts when neither alone is
     * enough (`TestSuite:test_x (suite.lua)`).
     */
    private fun label(all: List<LuaUnitRunIndex.CaseResult>, test: LuaUnitRunIndex.CaseResult): String {
        if (all.count { it.displayName == test.displayName } <= 1) {
            return test.displayName
        }
        val suite = test.caseName.substringBeforeLast('.', "")
        val suiteLabel = if (suite.isEmpty()) test.displayName else "$suite:${test.displayName}"
        if (all.count { it.caseName == test.caseName } <= 1) {
            return suiteLabel
        }
        val file = java.io.File(test.testFile).name
        if (all.count { it.displayName == test.displayName && it.testFile == test.testFile } <= 1) {
            return "${test.displayName} ($file)"
        }
        return "$suiteLabel ($file)"
    }

    /** True when the case is still declared where the run saw it. */
    private fun alive(test: LuaUnitRunIndex.CaseResult): Boolean {
        val path = test.declarationFile.ifEmpty { test.testFile }
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return true
        return LuaUnitCaseLines.declares(document, test.declarationLine, test)
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * The tests the header does not show: a popup with an incremental search
     * (the chooser builder filters by the typed text), alphabetical.
     */
    private fun showMore(project: Project, anchor: JComponent, tests: List<LuaUnitRunIndex.CaseResult>) {
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(tests)
            .setTitle("Tests")
            .setNamerForFiltering { it.displayName }
            .setRenderer(object : com.intellij.ui.SimpleListCellRenderer<LuaUnitRunIndex.CaseResult>() {
                override fun customize(
                    list: javax.swing.JList<out LuaUnitRunIndex.CaseResult>,
                    value: LuaUnitRunIndex.CaseResult,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    text = label(tests, value)
                }
            })
            .setItemChosenCallback { test -> openTest(project, test) }
            .createPopup()
            .showInCenterOf(anchor)
    }

    /** Opens the declaration of a case: the suite, or the data row it comes from. */
    private fun openTest(project: Project, test: LuaUnitRunIndex.CaseResult) {
        val (path, line) = LuaUnitRunIndex.getInstance(project).declarationOf(test)
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    companion object {
        /** How many test links the header shows before folding the rest. */
        private const val MAX_SHOWN = 10

        private val PASSED_COLOR: Color = JBColor(0x59A869, 0x499C54)
        private val FAILED_COLOR: Color = JBColor(0xDB5860, 0xC7222D)
        private val LINK_COLOR: Color = JBColor(0x1A73E8, 0x589DF6)
    }
}
