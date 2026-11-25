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
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.NamedColorUtil
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
        val items = itemsOf(project, ordered)
        val shown = items.take(MAX_SHOWN)
        val rest = items.drop(MAX_SHOWN)
        val html = StringBuilder("<html><body style='margin:0'>")
        fun link(href: String, text: String, color: Color) {
            html.append("<a href=\"").append(href).append("\" style=\"color:#")
                .append(String.format("%06X", color.rgb and 0xFFFFFF))
                .append("\">").append(escape(text)).append("</a>")
        }
        // The muted part in front of a name: the shortest tail of the file's
        // directories that tells the case apart from its neighbours. It is a
        // note, not a link — the click goes to the name itself.
        val prefixes = prefixes(shown)
        val muted = ColorUtil.toHex(NamedColorUtil.getInactiveTextColor())
        shown.forEachIndexed { index, item ->
            if (index > 0) {
                html.append(", ")
            }
            if (prefixes[index].isNotEmpty()) {
                html.append("<span style=\"color:#").append(muted).append("\">")
                    .append(escape(prefixes[index])).append("</span>")
            }
            link("t$index", item.text, if (item.test.failed) FAILED_COLOR else PASSED_COLOR)
        }
        if (rest.isNotEmpty()) {
            if (shown.isNotEmpty()) {
                html.append(", ")
            }
            link("more", "${rest.size} more", LINK_COLOR)
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
            shown.getOrNull(index)?.let { item -> openTest(project, item.test) }
        }
        return pane
    }

    /**
     * One entry of the tests list: the name to show, the file a click opens as
     * the reader sees it from the project root, and whether the name alone
     * still names more than one case.
     */
    private class Item(
        val test: LuaUnitRunIndex.CaseResult,
        val text: String,
        val location: String,
        val folder: String,
        val ambiguous: Boolean
    )

    /**
     * The entries of the list: the shortest name that names one case, the file
     * it opens relative to the project root and the folder above that file.
     *
     * <p>A case of its own is shown by its name (`test_x`). A name two cases
     * share is widened by the part that tells them apart — the suite when both
     * live in one file (`TestSuite:test_x`). When even that is shared, the
     * file is the only thing left that does, and the entry is
     * [Item.ambiguous].
     *
     * <p>Two records of one file are one entry: the index may keep a file-level
     * record beside a case record, and both would paint the same name twice.
     */
    private fun itemsOf(project: Project, tests: List<LuaUnitRunIndex.CaseResult>): List<Item> {
        val shared = tests.groupingBy { it.displayName }.eachCount()
        val seen = HashSet<String>()
        val entries = ArrayList<Triple<LuaUnitRunIndex.CaseResult, String, String>>()
        for (test in tests) {
            val suite = test.caseName.substringBeforeLast('.', "")
            val text = when {
                (shared[test.displayName] ?: 0) <= 1 -> test.displayName
                suite.isEmpty() -> test.displayName
                else -> "$suite:${test.displayName}"
            }
            val location = locationOf(project, test)
            if (!seen.add(text + "\u0000" + location)) {
                continue
            }
            entries.add(Triple(test, text, location))
        }
        val counts = entries.groupingBy { it.second }.eachCount()
        return entries.map { (test, text, location) ->
            Item(
                test = test,
                text = text,
                location = location,
                folder = location.substringBeforeLast('/', ""),
                ambiguous = (counts[text] ?: 0) > 1
            )
        }
    }

    /**
     * The file a click on the case opens, named from the project root
     * (`tests/ss7/x.lua`) the way the platform's choosers name files. A file
     * outside the project keeps its path, shortened to the home directory.
     */
    private fun locationOf(project: Project, test: LuaUnitRunIndex.CaseResult): String {
        val path = test.declarationFile.ifEmpty { test.testFile }
        val base = project.basePath
        if (base != null) {
            try {
                val root = java.io.File(base).toPath().toAbsolutePath().normalize()
                val file = java.io.File(path).toPath().toAbsolutePath().normalize()
                val relative = root.relativize(file).toString().replace('\\', '/')
                if (relative.isNotEmpty() && relative != ".." && !relative.startsWith("../")) {
                    return relative
                }
            } catch (e: IllegalArgumentException) {
                // Another root drive (Windows): the full path below is the one
                // that still names the file.
            }
        }
        return com.intellij.openapi.util.io.FileUtil.getLocationRelativeToUserHome(path)
    }

    /**
     * The shortest tail of each entry's path that tells it apart from the
     * neighbours named the same way, as the muted part in front of the name.
     *
     * <p>Two cases read `folder1/subfolder1/file.lua` and
     * `folder1/subfolder2/file.lua`: the entries say `subfolder1/file.lua`
     * and `subfolder2/file.lua` — the tail grows right to left, as few
     * directories above the file as tell it apart. The name itself is the
     * link, so only the directories are kept in the muted part.
     */
    private fun prefixes(items: List<Item>): List<String> {
        val result = MutableList(items.size) { "" }
        items.indices.groupBy { items[it].text }.forEach { (_, group) ->
            if (group.size < 2) {
                return@forEach
            }
            val paths = group.map { items[it].location.split('/') }
            var width = 0
            for (length in 1..paths.maxOf { it.size }) {
                val tails = paths.map { path -> path.takeLast(minOf(length, path.size)).joinToString("/") }
                if (tails.toSet().size == group.size) {
                    width = length
                    break
                }
            }
            // A width of one means the files share the directory: the name
            // itself is what differs, and there is nothing to put before it.
            if (width < 2) {
                return@forEach
            }
            group.forEachIndexed { index, item ->
                val directories = paths[index].dropLast(1)
                val kept = directories.takeLast(minOf(width - 1, directories.size))
                result[item] = if (kept.isEmpty()) "" else kept.joinToString("/") + "/"
            }
        }
        return result
    }

    /** True when the case is still declared where the run saw it. */
    private fun alive(test: LuaUnitRunIndex.CaseResult): Boolean {
        // A file-level record has no declaration: it is alive as long as its
        // file exists.
        if (test.fileLevel) {
            return true
        }
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
    private fun showMore(project: Project, anchor: JComponent, tests: List<Item>) {
        val ordered = tests.sortedWith(compareBy({ it.text.lowercase() }, { it.location }))
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(ordered)
            .setTitle("Tests")
            .setNamerForFiltering { it.test.displayName }
            .setRenderer(object : ColoredListCellRenderer<Item>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out Item>,
                    value: Item,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    append(value.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    // The muted part sits on the right with the folder above
                    // the file, the way the recent files list does: the name
                    // itself is not repeated. Only an ambiguous name needs it.
                    // The colour comes from the palette, so the list follows
                    // the theme and the selection.
                    if (value.ambiguous && value.folder.isNotEmpty()) {
                        append("  ${value.folder}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            })
            .setItemChosenCallback { item -> openTest(project, item.test) }
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
