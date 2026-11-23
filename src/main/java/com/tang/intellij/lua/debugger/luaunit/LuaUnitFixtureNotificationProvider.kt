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
        if (tests.isEmpty()) {
            return null
        }
        return Function { _ -> createPanel(project, tests) }
    }

    /**
     * The standard notification background with a row of links of our own: the
     * panel's own text sits in the middle and its links on the right, and the
     * names have to start at the left edge, right after the icon.
     */
    private fun createPanel(project: Project, tests: List<LuaUnitRunIndex.CaseResult>): JComponent {
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
        panel.removeAll()
        val rows = JPanel()
        rows.layout = BoxLayout(rows, BoxLayout.Y_AXIS)
        rows.isOpaque = false
        // Two files often declare cases with the same display name (the same
        // data table feeds several generators): the file name tells them apart.
        val duplicates = tests.groupingBy { it.displayName }.eachCount()
            .filterValues { it > 1 }
            .keys
        for (test in tests) {
            val label = if (test.displayName in duplicates) {
                java.io.File(test.testFile).name + ": " + test.displayName
            } else {
                test.displayName
            }
            val link = HyperlinkLabel()
            link.setHyperlinkText(label)
            link.foreground = if (test.failed) FAILED_COLOR else PASSED_COLOR
            link.alignmentX = Component.LEFT_ALIGNMENT
            link.addHyperlinkListener { event ->
                if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    openTest(project, test)
                }
            }
            rows.add(link)
        }
        panel.add(JBLabel(AllIcons.Nodes.Test), BorderLayout.WEST)
        panel.add(rows, BorderLayout.CENTER)
        return panel
    }

    /** Opens the declaration of a case: the suite, or the data row it comes from. */
    private fun openTest(project: Project, test: LuaUnitRunIndex.CaseResult) {
        val (path, line) = LuaUnitRunIndex.getInstance(project).declarationOf(test)
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }

    companion object {
        private val PASSED_COLOR: Color = JBColor(0x59A869, 0x499C54)
        private val FAILED_COLOR: Color = JBColor(0xDB5860, 0xC7222D)
    }
}
