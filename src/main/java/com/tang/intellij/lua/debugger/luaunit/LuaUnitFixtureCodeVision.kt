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

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * The result of a case, right above the line that declares it: a green icon
 * for a pass, a red one for a failure, and the fixtures of the case.
 *
 * <p>Every fixture is an entry of its own, so a click goes straight to that
 * file: two fixtures of different directories often share the base name
 * (`EDR/CCA.json` and `CCDR/CCA.json`), and a single combined entry could only
 * offer a popup to choose. The status icon is carried by the first entry of the
 * case.
 *
 * <p>The entries come from [LuaUnitRunIndex] only — the run already knows the
 * declaration line of every case, including the rows of a data module. The
 * provider therefore costs a list lookup and never walks the PSI: code vision
 * is asked on every scroll and every edit, and a per-file PSI scan (plus the
 * manifest scan for generators) made the marks appear seconds late.
 */
class LuaUnitFixtureCodeVision : CodeVisionProvider<Unit> {

    override val id: String = ID
    override val name: String = "LuaUnit fixtures"
    override val groupId: String = "luaunit"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.Ready(emptyList())
        // A run is in progress: the previous marks are already gone, and the
        // new ones will be painted by the invalidation that follows the run.
        if (LuaUnitHighlights.isRunning()) {
            return CodeVisionState.Ready(emptyList())
        }
        return ReadAction.compute<CodeVisionState, RuntimeException> {
            val document = editor.document
            val virtualFile = FileDocumentManager.getInstance().getFile(document)
                ?: return@compute CodeVisionState.Ready(emptyList())
            val index = LuaUnitRunIndex.getInstance(project)
            val path = LuaUnitRunIndex.canonical(virtualFile.path)
            val entries = ArrayList<Pair<TextRange, CodeVisionEntry>>()
            // One line may declare several cases (one data row feeds more than
            // one generator): each entry gets its own anchor inside the line,
            // so the platform keeps them apart instead of merging them into one.
            val taken = HashMap<Int, Int>()
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            // The fixtures of a case sit above its declaration (the `id` row
            // or the `function` line).
            val fixtures = LinkedHashMap<Int, MutableList<Pair<LuaUnitRunIndex.CaseResult, String>>>()
            for (case in index.casesDeclaredIn(path)) {
                val line = case.declarationLine
                if (line < 1 || line > document.lineCount) {
                    continue
                }
                val files = labels(case.fixtures).joinToString(", ")
                if (files.isNotEmpty()) {
                    fixtures.getOrPut(line) { ArrayList() }.add(case to files)
                }
            }
            for ((line, items) in fixtures) {
                entries.add(anchor(document, line, taken) to CaseEntry(items.first().first, join(items)))
            }
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("cv ${virtualFile.name}: ${entries.size} entries, ${index.casesDeclaredIn(path).size} declared")
            CodeVisionState.Ready(entries)
        }
    }

    /** The texts of one line, without repeating the same one twice. */
    private fun join(items: List<Pair<LuaUnitRunIndex.CaseResult, String>>): String =
        items.map { it.second }.distinct().joinToString("   ")

    /** A point inside the line, unique per entry of that line. */
    private fun anchor(
        document: com.intellij.openapi.editor.Document,
        line: Int,
        taken: MutableMap<Int, Int>
    ): TextRange {
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        val shift = taken.merge(start, 1, Int::plus)!! - 1
        val offset = (start + shift).coerceAtMost(end)
        return TextRange(offset, offset)
    }

    override fun handleClick(editor: Editor, range: TextRange, entry: CodeVisionEntry) {
        val project = editor.project ?: return
        // Every entry of this provider is a fixture: a click opens its file.
        val paths = entry.getUserData(FIXTURES) ?: return
        val files = paths.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
        when (files.size) {
            0 -> return
            1 -> FileEditorManager.getInstance(project).openFile(files.first(), true)
            else -> {
                // The platform keeps one code vision entry per line, so two
                // fixtures of a case cannot be two entries: the entry offers
                // the list and every name opens its own file.
                // The popup names a file the same way the entry does: the
                // directory is kept for colliding base names.
                val labels = labels(paths)
                val byPath = paths.withIndex().associate { (index, path) -> path to labels[index] }
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(files)
                    .setTitle("Fixtures")
                    .setNamerForFiltering { byPath[it.path] ?: it.name }
                    .setRenderer(object : com.intellij.ui.SimpleListCellRenderer<com.intellij.openapi.vfs.VirtualFile>() {
                        override fun customize(
                            list: javax.swing.JList<out com.intellij.openapi.vfs.VirtualFile>,
                            value: com.intellij.openapi.vfs.VirtualFile,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            text = byPath[value.path] ?: value.name
                        }
                    })
                    .setItemChosenCallback { file ->
                        FileEditorManager.getInstance(project).openFile(file, true)
                    }
                    .createPopup()
                    .showInFocusCenter()
            }
        }
    }

    /**
     * One entry: the name of a fixture. The anchor "above the declaration"
     * paints [toString], not the long presentation.
     */
    private class CaseEntry(
        result: LuaUnitRunIndex.CaseResult,
        private val label: String
    ) : CodeVisionEntry(
        ID,
        if (result.failed) AllIcons.RunConfigurations.TestState.Red2
        else AllIcons.RunConfigurations.TestState.Green2,
        label,
        "${result.displayName}: ${label}",
        emptyList()
    ) {

        init {
            putUserData(FIXTURES, result.fixtures)
        }

        override fun toString(): String = label
    }

    companion object {
        const val ID: String = "luaunit.fixtures"

        private val FIXTURES: Key<List<String>> = Key.create("luaunit.fixtures")

        /**
         * The names to show for [fixtures]. Two files of different directories
         * may share the base name (`EDR/CCA.json` and `CCDR/CCA.json`): such a
         * name alone would open the wrong idea of the file, so the directory is
         * kept for the colliding ones.
         */
        private fun labels(fixtures: List<String>): List<String> {
            val names = fixtures.map { java.io.File(it).name }
            val counts = names.groupingBy { it }.eachCount()
            return fixtures.withIndex().map { (index, path) ->
                val name = names[index]
                if (counts[name] == 1) name else {
                    val parent = java.io.File(path).parentFile?.name
                    if (parent.isNullOrBlank()) name else "$parent/$name"
                }
            }
        }
    }
}
