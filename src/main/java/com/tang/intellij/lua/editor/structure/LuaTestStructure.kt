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

package com.tang.intellij.lua.editor.structure

import com.intellij.icons.AllIcons
import com.tang.intellij.lua.debugger.luaunit.LuaUnitTestCaseLookup
import com.tang.intellij.lua.psi.LuaPsiFile

/**
 * The LuaUnit cases of a file, drawn into its structure view.
 *
 * <p>A case is shown the way the test tree names it: the case's own name
 * (`test_x`) under its suite, the row's `desc` (or `id`) when the case is built
 * from data. A declared case keeps its function node and only takes the test
 * icon and the description; a generated or a commented-out case has no PSI
 * node of the file, so a node of its own is added and a click opens its line.
 */
internal object LuaTestStructure {

    /**
     * Marks the declared cases of [children] and adds the cases that have no
     * node yet. Returns the nodes to place at the top level of the file.
     */
    fun decorate(
        file: LuaPsiFile,
        children: List<LuaTreeElement>,
        tables: List<Triple<Int, Int, LuaTreeElement>>,
        entries: List<LuaUnitTestCaseLookup.Entry>
    ): List<LuaTreeElement> {
        val topLevel = ArrayList<LuaTreeElement>()
        val text = file.text
        val current = file.virtualFile?.path?.let {
            java.nio.file.Path.of(it).toAbsolutePath().normalize()
        }
        // The case lines of this file are the only positions comparable with
        // the offsets of the PSI nodes. A case declared in another file (a data
        // row of a generated case in a suite) gets a place after the file's own
        // nodes, in the order of its rows.
        val starts = lineStarts(text)
        val afterFile = text.length + 1
        for (entry in entries) {
            if (!entry.generated) {
                val node = functionNode(children, entry)
                if (node != null) {
                    node.icon = AllIcons.Nodes.Test
                    node.location = noteOf(entry)
                    continue
                }
            }
            val ownLine = entry.file == current
            val offset = if (ownLine) lineStart(starts, entry.line) else afterFile + entry.line
            val element = LuaTestCaseElement(
                file.project, entry.file, entry.line, entry.shortName, noteOf(entry)
            )
            // The offset, not the line number: the sorter compares it with the
            // offsets the PSI nodes carry.
            element.lineOrder = offset
            val parent = ownerNode(children, entry.owner)
                ?: if (ownLine) tableNode(tables, offset) else null
            if (parent != null) {
                parent.addChild(element, keyOf(entry))
            } else {
                topLevel.add(element)
            }
        }
        return topLevel
    }

    /** True when the file has at least one case the structure view can show. */
    fun hasTests(file: LuaPsiFile): Boolean {
        val path = file.virtualFile?.path ?: return false
        return try {
            LuaUnitTestCaseLookup.entriesOf(java.nio.file.Path.of(path)).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /** The note to show to the right of a case: its description, and the ignored mark. */
    private fun noteOf(entry: LuaUnitTestCaseLookup.Entry): String? = when {
        entry.ignored && entry.description.isNotEmpty() -> "ignored \u00b7 ${entry.description}"
        entry.ignored -> "ignored"
        entry.description.isNotEmpty() -> entry.description
        else -> null
    }

    /** The node of a declared case: the function under its suite table. */
    private fun functionNode(children: List<LuaTreeElement>, entry: LuaUnitTestCaseLookup.Entry): LuaTreeElement? {
        // `function A.B.test_x()` is a plain function whose view name is the
        // whole `A.B.test_x`: the full case name finds it without a suite node.
        children.firstOrNull { it is LuaFuncElement && it.name == entry.name }?.let { return it }
        if (entry.owner.isEmpty()) {
            return children.firstOrNull { it is LuaFuncElement && it.name == entry.shortName }
        }
        val owner = ownerNode(children, entry.owner) ?: return null
        return owner.children()
            .filterIsInstance<LuaFuncElement>()
            .firstOrNull { it.name == entry.shortName }
    }

    /** The node named [name]: a top-level one, or a table nested in it (`M.Suite`). */
    private fun ownerNode(children: List<LuaTreeElement>, name: String): LuaTreeElement? {
        if (name.isEmpty()) {
            return null
        }
        for (child in children) {
            if (child.name == name) {
                return child
            }
        }
        for (child in children) {
            findNamed(child.children(), name)?.let { return it }
        }
        return null
    }

    private fun findNamed(nodes: List<LuaTreeElement>, name: String): LuaTreeElement? {
        for (node in nodes) {
            if (node.name == name) {
                return node
            }
        }
        for (node in nodes) {
            findNamed(node.children(), name)?.let { return it }
        }
        return null
    }

    /** The innermost table of the view that holds [offset]: the data table of a row. */
    private fun tableNode(tables: List<Triple<Int, Int, LuaTreeElement>>, offset: Int): LuaTreeElement? =
        tables.filter { offset in it.first..it.second }
            .minByOrNull { it.second - it.first }
            ?.third

    /**
     * The key of a case in the children map: a data row may feed two suites
     * with the same short name, and the nodes must not overwrite each other.
     */
    private fun keyOf(entry: LuaUnitTestCaseLookup.Entry): String =
        "${entry.name}\u0000${entry.file}:${entry.line}"

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>(text.length / 40 + 1)
        starts.add(0)
        for (index in text.indices) {
            if (text[index] == '\n') {
                starts.add(index + 1)
            }
        }
        return starts.toIntArray()
    }

    private fun lineStart(starts: IntArray, line: Int): Int =
        starts.getOrElse(line - 1) { starts.lastOrNull() ?: 0 }
}

/** The children of a node as the structure elements they are. */
private fun LuaTreeElement.children(): List<LuaTreeElement> =
    getChildren().filterIsInstance<LuaTreeElement>()
