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
 * `cca/... (N)` for a folder the case read whole, `cca/x.json (+N more)` for a
 * folder it read in part. Only the text inside the parentheses is a link of
 * its own (it opens the popup with the files); the parentheses and the commas
 * between elements are plain notes, never links.
 *
 * <p>The blocks are built when an editor opens and rebuilt after a run (see
 * [LuaUnitHighlights.refresh]); they never walk the PSI.
 */
class LuaUnitFixtureBlockInlays : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
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
                if (case.declarationLine < 1 || case.declarationLine > editor.document.lineCount) {
                    continue
                }
                byLine.getOrPut(case.declarationLine) { ArrayList() }.add(case)
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
                // A block element of the declaration line's end is painted
                // above that line: right over the `function` / `id =` line.
                val offset = editor.document.getLineEndOffset((line - 1).coerceAtLeast(0))
                // The block starts in the column the declaration starts in
                // (`function`, `id = ...`): its own leading blanks are kept.
                val lineStart = editor.document.getLineStartOffset((line - 1).coerceAtLeast(0))
                var firstNonBlank = lineStart
                while (firstNonBlank < offset &&
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
                    val names = labels(ordered)
                    val byPath = ordered.withIndex().associate { (index, path) -> path to names[index] }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(files)
                        .setTitle("Fixtures")
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
         * whole, its first file with the rest counted (`cca/x.json (+19 more)`)
         * otherwise. The count inside the parentheses is a link of its own; the
         * parentheses and the commas are notes.
         */
        private fun foldedParts(fixtures: List<String>): List<Part> {
            val byParent = fixtures.withIndex().groupBy { path -> parentOf(path.value) }
            val complete = HashMap<String, List<String>>()
            val many = HashMap<String, List<String>>()
            for ((parent, items) in byParent) {
                if (parent.isEmpty()) {
                    continue
                }
                val files = items.map { item -> item.value }
                if (files.size <= FOLD_AFTER) {
                    continue
                }
                if (folderComplete(parent, files)) {
                    complete[parent] = files
                } else {
                    many[parent] = files
                }
            }
            val plain = labels(fixtures)
            val groups = ArrayList<List<Part>>()
            val seenFolders = HashSet<String>()
            for ((index, path) in fixtures.withIndex()) {
                val parent = parentOf(path)
                val whole = complete[parent]
                if (whole != null) {
                    if (seenFolders.add(parent)) {
                        // The whole folder is one entry: its own popup list.
                        groups.add(
                            note(
                                java.io.File(parent).name + "/*",
                                whole.size,
                                whole,
                                whole,
                                plus = false
                            )
                        )
                    }
                    continue
                }
                val partial = many[parent]
                if (partial != null) {
                    if (seenFolders.add(parent)) {
                        // The file's link is direct; the popup is opened by
                        // the "more" in the parentheses only.
                        groups.add(
                            note(
                                java.io.File(parent).name + "/" + java.io.File(path).name,
                                partial.size - 1,
                                listOf(path),
                                partial,
                                plus = true
                            )
                        )
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

        /**
         * A link with its count in parentheses: `cca/... (12)` or
         * `cca/x.json (+19 more)`. Only the text inside the parentheses is a
         * link of its own — the parentheses are a note.
         */
        private fun note(
            link: String,
            count: Int,
            linkPaths: List<String>,
            notePaths: List<String>,
            plus: Boolean
        ): List<Part> =
            listOf(
                Part(link, linkPaths),
                Part(" (", emptyList(), link = false),
                Part(if (plus) "+$count more" else "$count", notePaths),
                Part(")", emptyList(), link = false)
            )

        private fun parentOf(path: String): String = java.io.File(path).parent ?: ""

        /** True when every file of [parent] is among [files] (no unused one). */
        private fun folderComplete(parent: String, files: List<String>): Boolean {
            val directory = LocalFileSystem.getInstance().findFileByPath(parent) ?: return false
            val children = directory.children.filter { child -> !child.isDirectory }
            if (children.isEmpty()) {
                return false
            }
            val names = files.map { file -> java.io.File(file).name }.toSet()
            return children.all { child -> child.name in names }
        }

        private fun labels(fixtures: List<String>): List<String> {
            val names = fixtures.map { file -> java.io.File(file).name }
            val counts = names.groupingBy { name -> name }.eachCount()
            return fixtures.withIndex().map { (index, path) ->
                val name = names[index]
                if (counts[name] == 1) {
                    name
                } else {
                    val parent = java.io.File(path).parentFile?.name
                    if (parent.isNullOrBlank()) name else "$parent/$name"
                }
            }
        }
    }
}
