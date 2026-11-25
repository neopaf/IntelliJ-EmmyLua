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

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.util.WeakHashMap

/**
 * The fixtures of a case, in a block above the line that declares it.
 *
 * <p>A block inlay is a paragraph of its own, not a single link: every file of
 * the list is a link of its own, so a click goes straight to that file.
 *
 * <p>More than [FOLD_AFTER] files of one folder are folded into one element:
 * `cca/... (N)` for a folder the case read whole, `cca/x.json (N total,
 * M skipped)` for a folder it read in part. The file and the two counts are
 * links of their own (the file opens it, `N total` the folder, `M skipped`
 * the files the case did not read — drawn in the text colour); the
 * parentheses and the commas between the elements are plain notes. A folder
 * read whole is one link and a plain count.
 *
 * <p>The blocks are built when an editor opens and rebuilt after a run (see
 * [LuaUnitHighlights.refresh]); they never walk the PSI.
 */
class LuaUnitFixtureBlockInlays : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        ensureSubscribed(project)
        if (LuaUnitHighlights.isRunning()) {
            return
        }
        try {
            rebuild(project, editor)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .warn("fixture inlays: ${e.message}", e)
        }
        installMouse(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        clear(event.editor)
    }

    companion object {

        private val blocks = java.util.Collections.synchronizedMap(
            WeakHashMap<Editor, MutableList<Inlay<*>>>()
        )

        private val MOUSE_INSTALLED: Key<Unit> = Key.create("luaunit.fixtures.mouse")

        /** The projects whose editor manager is already listened to. */
        private val subscribed = java.util.concurrent.ConcurrentHashMap.newKeySet<Project?>()

        /**
         * A file opened from a fixture's header (or a tab switched to) must
         * show its blocks at once: `editorCreated` does not fire for a file
         * whose editor already exists, so the run's blocks were shown only
         * after the next run with the file open.
         */
        fun ensureSubscribed(project: Project) {
            if (!subscribed.add(project)) {
                return
            }
            project.messageBus.connect(project).subscribe(
                com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                    override fun fileOpened(
                        source: com.intellij.openapi.fileEditor.FileEditorManager,
                        file: com.intellij.openapi.vfs.VirtualFile
                    ) {
                        rebuildFile(project, file)
                    }

                    override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                        event.newFile?.let { rebuildFile(project, it) }
                    }
                }
            )
        }

        /** Rebuilds the blocks of every editor of [file]. */
        private fun rebuildFile(project: Project, file: com.intellij.openapi.vfs.VirtualFile) {
            if (LuaUnitHighlights.isRunning()) {
                return
            }
            for (editor in com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getAllEditors(file)) {
                val textEditor = editor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
                try {
                    rebuild(project, textEditor.editor)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                        .warn("fixture inlays: ${e.message}", e)
                }
            }
        }

        /** More than this many files of one folder are folded into one. */
        private const val FOLD_AFTER = 10

        private var lastClickAt: Long = 0

        /** Rebuilds the blocks of every open editor of [project]. */
        fun refresh(project: Project) {
            if (LuaUnitHighlights.isRunning()) {
                return
            }
            val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            for (file in manager.openFiles) {
                for (editor in manager.getAllEditors(file)) {
                    val textEditor = editor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
                    try {
                        rebuild(project, textEditor.editor)
                    } catch (e: Exception) {
                        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                            .warn("fixture inlays: ${e.message}", e)
                    }
                }
            }
        }

        private fun clear(editor: Editor) {
            blocks.remove(editor)?.forEach { inlay -> inlay.dispose() }
        }

        private fun rebuild(project: Project, editor: Editor) {
            clear(editor)
            ensureSubscribed(project)
            if (LuaUnitHighlights.isRunning()) {
                return
            }
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            if (!file.extension.equals("lua", true)) {
                return
            }
            val index = LuaUnitRunIndex.getInstance(project)
            val path = LuaUnitRunIndex.canonical(file.path)
            // One line may hold several cases; their fixtures are one list.
            val byLine = LinkedHashMap<Int, MutableList<LuaUnitRunIndex.CaseResult>>()
            for (case in index.casesDeclaredIn(path)) {
                if (case.fixtures.isEmpty()) {
                    continue
                }
                // The record of the file itself (a plain script) has no
                // declaration: its fixtures are shown above the first line.
                val line = if (case.fileLevel) 1 else case.declarationLine
                if (line < 1 || line > editor.document.lineCount) {
                    continue
                }
                // The file may have changed since the run: a record whose
                // line no longer declares the case would put the fixtures of
                // a dead case into the body of a live one. A file-level record
                // has no declaration to check.
                if (!case.fileLevel && !LuaUnitCaseLines.declares(editor.document, line, case)) {
                    continue
                }
                byLine.getOrPut(line) { ArrayList() }.add(case)
            }
            if (byLine.isEmpty()) {
                return
            }
            val inlays = ArrayList<Inlay<*>>()
            for ((line, cases) in byLine) {
                val paths = cases.flatMap { it.fixtures }.distinct()
                val parts = foldedParts(paths)
                if (parts.isEmpty()) {
                    continue
                }
                val first = cases.first()
                // The block is anchored to the start of the declaration line:
                // a block element at the line's end moves with the newline a
                // `Enter` at that end inserts — the block slid under the
                // `function` line and stayed in the body. The line start does
                // not move, so the block stays above its declaration.
                val lineStart = editor.document.getLineStartOffset((line - 1).coerceAtLeast(0))
                val offset = lineStart
                // The block starts in the column the declaration starts in
                // (`function`, `id = ...`): its own leading blanks are kept.
                var firstNonBlank = lineStart
                while (firstNonBlank < editor.document.getLineEndOffset((line - 1).coerceAtLeast(0)) &&
                    editor.document.charsSequence[firstNonBlank].isWhitespace()
                ) {
                    firstNonBlank++
                }
                val indent = editor.document.charsSequence.subSequence(lineStart, firstNonBlank).toString()
                val renderer = Renderer(editor, parts, first.displayName, indent)
                val inlay = editor.inlayModel.addBlockElement(offset, true, true, 0, renderer)
                if (inlay != null) {
                    inlays.add(inlay)
                }
            }
            blocks[editor] = inlays
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
                "fixtures blocks ${file.name}: ${inlays.size} of ${byLine.size} lines"
            )
            // A rebuild may be the first time the editor is seen (the results
            // arrived after the editor was created): the mouse has to be
            // installed here too, not only in editorCreated.
            installMouse(editor)
        }

        private fun installMouse(editor: Editor) {
            if (editor.getUserData(MOUSE_INSTALLED) != null) {
                return
            }
            editor.putUserData(MOUSE_INSTALLED, Unit)
            editor.addEditorMouseListener(object : com.intellij.openapi.editor.event.EditorMouseListener {
                override fun mouseClicked(event: com.intellij.openapi.editor.event.EditorMouseEvent) {
                    handleClick(editor, event.mouseEvent.point)
                }
            })
            // A block inlay can swallow the editor's mouse events: the raw
            // listener of the component sees the click anyway.
            editor.contentComponent.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    handleClick(editor, event.point)
                }
            })
            editor.contentComponent.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    editor.contentComponent.cursor =
                        if (hit(editor, event.point) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        else Cursor.getDefaultCursor()
                }
            })
        }

        /** Opens the link under [point]; one physical click, one action. */
        private fun handleClick(editor: Editor, point: Point) {
            val now = System.currentTimeMillis()
            if (now - lastClickAt < 300) {
                return
            }
            val part = hit(editor, point)
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("fixture click x=${point.x} y=${point.y} hit=${part?.label}")
            if (part == null) {
                return
            }
            lastClickAt = now
            openPaths(editor, part.paths)
        }

        /** The link of the block under [point], if any. */
        private fun hit(editor: Editor, point: Point): Part? {
            val known = blocks[editor]
            val inlays: List<Inlay<*>> = known
                ?: editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            for (inlay in inlays) {
                if (!inlay.isValid) {
                    continue
                }
                val bounds = inlay.bounds ?: continue
                if (!bounds.contains(point)) {
                    continue
                }
                val renderer = inlay.renderer as? Renderer ?: continue
                renderer.partAt(point.x - bounds.x, point.y - bounds.y)?.let { return it }
            }
            return null
        }

        /** Opens the only [paths] file, or offers the list of them. */
        private fun openPaths(editor: Editor, paths: List<String>) {
            val project = editor.project ?: return
            // The popup is alphabetical: a directory of rows is easier to scan
            // by name than in the order the case happened to open the files.
            val ordered = paths.sortedWith(compareBy({ java.io.File(it).name.lowercase() }, { it }))
            val files = ordered.mapNotNull { LocalFileSystem.getInstance().findFileByPath(it) }
            when (files.size) {
                0 -> return
                1 -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                    .openFile(files.first(), true)
                else -> {
                    val names = FixtureNames.labels(ordered)
                    val byPath = ordered.withIndex().associate { (index, path) -> path to names[index] }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(files)
                        .setTitle(fixturePopupTitle(project, ordered))
                        .setNamerForFiltering { byPath[it.path] ?: it.name }
                        .setRenderer(object : SimpleListCellRenderer<com.intellij.openapi.vfs.VirtualFile>() {
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
                            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                                .openFile(file, true)
                        }
                        .createPopup()
                        // Anchored to the editor: the focus-based placement
                        // looked at the focused window, which may be the other
                        // open project, and the popup jumped there.
                        .showInCenterOf(editor.contentComponent)
                }
            }
        }

        /**
         * The elements of one line, in the order the case opened the files.
         *
         * <p>More than [FOLD_AFTER] files of one folder are one element: the
         * folder itself with its count (`cca/... (12)`) when the case read it
         * whole, its first file with what the folder holds otherwise
         * (`cca/x.json (12 total, 9 skipped)`). A folder read whole is one
         * link and a plain count; a folder read in part is the first file (a
         * link to it) and two links of its own — `N total` lists the folder,
         * `M skipped` lists the files the case did not read. The commas and
         * the parentheses are notes.
         */
        private fun foldedParts(fixtures: List<String>): List<Part> {
            val dropExtension = FixtureNames.sameExtension(fixtures)
            val byParent = fixtures.withIndex().groupBy { path -> parentOf(path.value) }
            val folded = HashMap<String, Fold>()
            for ((parent, items) in byParent) {
                if (parent.isEmpty()) {
                    continue
                }
                val files = items.map { item -> item.value }
                if (files.size <= FOLD_AFTER) {
                    continue
                }
                folded[parent] = foldOf(parent, files)
            }
            val plain = FixtureNames.labels(fixtures, dropExtension)
            val groups = ArrayList<List<Part>>()
            val seenFolders = HashSet<String>()
            for ((index, path) in fixtures.withIndex()) {
                val parent = parentOf(path)
                val fold = folded[parent]
                if (fold != null) {
                    if (seenFolders.add(parent)) {
                        if (fold.skipped.isEmpty()) {
                            // Nothing of the folder's own kind is left out: the
                            // whole folder is one entry with its popup. The
                            // kind is spelled out (`*.json`), so the folder of
                            // a mixed tree says what was read.
                            val kind = fold.extension?.let { "*.$it" } ?: "*"
                            groups.add(wholeNote(java.io.File(parent).name + "/" + kind, fold.taken))
                        } else {
                            // The first file is a link to it; the two counts
                            // open their own lists — the folder, and what the
                            // case skipped.
                            groups.add(
                                partialNote(
                                    java.io.File(parent).name + "/" +
                                        FixtureNames.nameOf(path, dropExtension),
                                    path,
                                    fold.relevant,
                                    fold.skipped
                                )
                            )
                        }
                    }
                    continue
                }
                groups.add(listOf(Part(plain[index], listOf(path))))
            }
            val parts = ArrayList<Part>()
            for ((index, group) in groups.withIndex()) {
                if (index > 0) {
                    parts.add(Part(", ", emptyList(), link = false))
                }
                parts.addAll(group)
            }
            return parts
        }

        /** A folded folder: what the case took, what of it was left out. */
        private class Fold(
            /** The files of the folder the case opened. */
            val taken: List<String>,
            /** The files of the case's own kind in the folder: all of them. */
            val relevant: List<String>,
            /** The files of [relevant] the case did not open. */
            val skipped: List<String>,
            /** The extension shared by [taken], or null when they differ. */
            val extension: String?
        )

        /**
         * Folds [taken] files of [parent] into one element.
         *
         * <p>A case that reads the `.json` rows of a folder does not skip the
         * `.pcap` files next to them: when every taken file has the same
         * extension, only the files of that extension are the folder's own
         * kind, and only they count as total and skipped. With nothing of the
         * kind left out, the folder is shown whole (`cca/... (12)`).
         */
        private fun foldOf(parent: String, taken: List<String>): Fold {
            val all = folderFiles(parent) ?: taken
            val extension = FixtureNames.commonExtension(taken)
            val relevant = if (extension != null) {
                all.filter { file -> java.io.File(file).extension.equals(extension, ignoreCase = true) }
            } else {
                all
            }
            val opened = taken.toSet()
            return Fold(taken, relevant, relevant.filter { file -> file !in opened }, extension)
        }

        /**
         * A folder the case read whole: its link and the plain number of its
         * files (`cca/... (12)`) — the link opens the folder's files.
         */
        private fun wholeNote(link: String, files: List<String>): List<Part> =
            listOf(
                Part(link, files),
                Part(" (", emptyList(), link = false),
                Part("${files.size}", emptyList(), link = false),
                Part(")", emptyList(), link = false)
            )

        /**
         * A folder the case read in part: the file it opened, then the two
         * counts — `cca/x.json (12 total, 9 skipped)`. The `N total` opens
         * the whole folder, the `M skipped` — the files the case did not read
         * (a quiet link: the colour of the editor's own text).
         */
        private fun partialNote(
            link: String,
            path: String,
            folder: List<String>,
            skipped: List<String>
        ): List<Part> =
            listOf(
                Part(link, listOf(path)),
                Part(" (", emptyList(), link = false),
                Part("${folder.size} total", folder),
                Part(", ", emptyList(), link = false),
                Part("${skipped.size} skipped", skipped, muted = true),
                Part(")", emptyList(), link = false)
            )

        private fun parentOf(path: String): String = java.io.File(path).parent ?: ""

        /** The files of [parent], or null when the directory is gone. */
        private fun folderFiles(parent: String): List<String>? {
            val directory = LocalFileSystem.getInstance().findFileByPath(parent) ?: return null
            return directory.children.filter { child -> !child.isDirectory }.map { child -> child.path }
        }
    }
}
