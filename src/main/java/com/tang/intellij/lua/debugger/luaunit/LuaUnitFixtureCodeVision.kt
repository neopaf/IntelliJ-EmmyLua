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
 * <p>Every file is a code vision entry of its own, so a click goes straight to
 * that file; a folder whose files are all among the fixtures is one entry
 * (`cca/...`) that offers its files. Two files of different directories often
 * share the base name (`EDR/CCA.json` and `CCDR/CCA.json`), and the entry keeps
 * the directory for the colliding ones. The status icon is carried by the
 * first entry of the line.
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

    // ReadAction.computeBlocking exists only in IDEA 2026.2.
    @Suppress("DEPRECATION")
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
                if (!LuaUnitCaseLines.declares(document, line, case)) {
                    continue
                }
                val files = FixtureNames.labels(
                    case.fixtures, FixtureNames.sameExtension(case.fixtures)
                ).joinToString(", ")
                if (files.isNotEmpty()) {
                    fixtures.getOrPut(line) { ArrayList() }.add(case to files)
                }
            }
            for ((line, items) in fixtures) {
                val first = items.first().first
                // One line may hold several cases; their fixtures are shown
                // as one list (a folder folded once, not per case).
                val allPaths = items.flatMap { it.first.fixtures }.distinct()
                val parts = foldedParts(allPaths)
                if (parts.isEmpty()) {
                    continue
                }
                val statusIcon = if (first.failed) AllIcons.RunConfigurations.TestState.Red2
                else AllIcons.RunConfigurations.TestState.Green2
                // Every file — and every folded folder — is a code vision
                // entry of its own, so a click goes to that very file (the
                // folder opens its popup). Entries of one provider with the
                // same range are deduplicated by the platform, so each entry
                // carries a provider id of its own; the click is handled by
                // the entry itself (ClickableTextCodeVisionEntry), not by the
                // provider looked up by that id.
                for ((index, part) in parts.withIndex()) {
                    val last = index == parts.lastIndex
                    val text = if (last) part.label else part.label + ", "
                    val providerId = "$ID#${line}-$index"
                    entryIds.add(providerId)
                    entries.add(
                        anchor(document, line, taken) to fixtureEntry(
                            providerId = providerId,
                            icon = if (index == 0) statusIcon else NO_ICON,
                            text = text,
                            tooltip = "${first.displayName}: ${part.label}",
                            onClick = { _, editor -> openPaths(editor, part.paths) }
                        )
                    )
                }
            }
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("cv ${virtualFile.name}: ${entries.size} entries, ${index.casesDeclaredIn(path).size} declared")
            CodeVisionState.Ready(entries)
        }
    }

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

    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        // Every entry of this provider is a fixture: a click opens its file.
        openPaths(editor, entry.getUserData(FIXTURES) ?: return)
    }

    /** Opens the only [paths] file, or offers the list of them. */
    private fun openPaths(editor: Editor, paths: List<String>) {
        val project = editor.project ?: return
        val files = paths.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
        when (files.size) {
            0 -> return
            1 -> FileEditorManager.getInstance(project).openFile(files.first(), true)
            else -> {
                // A folded folder is one entry and its files are several:
                // the entry offers the list and every name opens its own file.
                // The popup names a file the same way the entry does: the
                // directory is kept for colliding base names.
                val labels = FixtureNames.labels(paths)
                val byPath = paths.withIndex().associate { (index, path) -> path to labels[index] }
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(files)
                    .setTitle(fixturePopupTitle(project, paths))
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
     * One entry: a fixture file, or a folded folder. The click is a lambda of
     * the entry itself: such an entry is not looked up by its provider id (the
     * ids here are unique per line and entry, see [computeCodeVision]).
     */
    private fun fixtureEntry(
        providerId: String,
        icon: javax.swing.Icon,
        text: String,
        tooltip: String,
        onClick: (java.awt.event.MouseEvent?, Editor) -> Unit
    ): CodeVisionEntry = com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry(
        providerId = providerId,
        text = text,
        onClick = onClick,
        icon = icon,
        longPresentation = tooltip,
        tooltip = tooltip,
        extraActions = emptyList()
    )

    companion object {
        const val ID: String = "luaunit.fixtures"

        /**
         * Every provider id the entries of this provider carry. The platform
         * deduplicates entries with the same provider id and range, so each
         * entry gets an id of its own — and the invalidation after a run has
         * to name them all (see [LuaUnitHighlights.refresh]).
         */
        private val entryIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The ids the code vision of this provider has to invalidate. */
        fun providerIds(): List<String> = (setOf(ID) + entryIds).toList()

        private val FIXTURES: Key<List<String>> = Key.create("luaunit.fixtures")

        /** Entries after the first carry no icon: the status is shown once. */
        private val NO_ICON: javax.swing.Icon = object : javax.swing.Icon {
            override fun getIconWidth(): Int = 0

            override fun getIconHeight(): Int = 0

            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics?, x: Int, y: Int) = Unit
        }

        /** The entries of one line: a file, or a folded folder. */
        private class Part(val label: String, val paths: List<String>)

        /**
         * The entries to show for [fixtures].
         *
         * <p>Two files of different directories may share the base name
         * (`EDR/CCA.json` and `CCDR/CCA.json`): such a name alone would open
         * the wrong idea of the file, so the directory is kept for the
         * colliding ones.
         *
         * <p>When every file of a folder is among [fixtures], the folder is
         * one entry (`cca/...`) that offers its files: a case that read the
         * whole folder is about the folder, not about a dozen rows of it. A
         * folder with a file the case did not read is a fact of its own and
         * stays spelled out file by file.
         */
        private fun foldedParts(fixtures: List<String>): List<Part> {
            val plain = FixtureNames.labels(fixtures, FixtureNames.sameExtension(fixtures))
            val byParent = fixtures.withIndex().groupBy { parentOf(it.value) }
            val foldedFiles = HashMap<String, List<String>>()
            for ((parent, items) in byParent) {
                if (parent.isEmpty() || items.size < 2) {
                    continue
                }
                val files = items.map { it.value }
                if (!folderComplete(parent, files)) {
                    continue
                }
                foldedFiles[parent] = files
            }
            if (foldedFiles.isEmpty()) {
                return fixtures.withIndex().map { (index, path) -> Part(plain[index], listOf(path)) }
            }
            val parts = ArrayList<Part>()
            val seenFolders = HashSet<String>()
            for ((index, path) in fixtures.withIndex()) {
                val parent = parentOf(path)
                val files = foldedFiles[parent]
                if (files != null) {
                    if (seenFolders.add(parent)) {
                        parts.add(Part(java.io.File(parent).name + "/*", files))
                    }
                    continue
                }
                parts.add(Part(plain[index], listOf(path)))
            }
            return parts
        }

        private fun parentOf(path: String): String = java.io.File(path).parent ?: ""

        /** True when every file of [parent] is among [files] (no unused one). */
        private fun folderComplete(parent: String, files: List<String>): Boolean {
            val directory = LocalFileSystem.getInstance().findFileByPath(parent) ?: return false
            val children = directory.children.filter { !it.isDirectory }
            if (children.isEmpty()) {
                return false
            }
            val names = files.map { java.io.File(it).name }.toSet()
            return children.all { it.name in names }
        }
    }
}
