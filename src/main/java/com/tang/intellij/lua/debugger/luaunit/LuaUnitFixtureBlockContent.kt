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
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/**
 * One element of the block: a link (a file, a folded folder, a count) or a
 * note. A [muted] link is a link all the same — it is clickable and
 * underlined, but drawn in the editor's text colour, not in the link one.
 */
internal class Part(
    val label: String,
    val paths: List<String>,
    val link: Boolean = true,
    val muted: Boolean = false
)

/**
 * How a fixture file is named in a block of fixtures.
 *
 * <p>The extension is noise when every file of a case carries the same one: a
 * run of a diameter case reads `before.json`, `after.json`, `ocs_request.json`
 * — the block says `before, after, ocs_request` and stays a readable list
 * instead of a wall of `.json`. A mixed list keeps every extension, since the
 * extension is what tells the files apart.
 *
 * <p>A base name of two files of different directories is ambiguous
 * (`EDR/CCA.json` and `CCDR/CCA.json`): such a name keeps its directory, the
 * way it always did.
 */
internal object FixtureNames {

    /** True when every file of [fixtures] has the same (non-empty) extension. */
    fun sameExtension(fixtures: List<String>): Boolean = commonExtension(fixtures) != null

    /**
     * The extension every file of [fixtures] has, or null when they differ or
     * one of them has none.
     */
    fun commonExtension(fixtures: List<String>): String? {
        var common: String? = null
        for (path in fixtures) {
            val extension = java.io.File(path).extension
            if (extension.isEmpty()) {
                return null
            }
            if (common == null) {
                common = extension
            } else if (!common.equals(extension, ignoreCase = true)) {
                return null
            }
        }
        return common
    }

    /** The name of [path] as the block shows it: with the extension, or without it. */
    fun nameOf(path: String, dropExtension: Boolean): String =
        if (dropExtension) java.io.File(path).nameWithoutExtension else java.io.File(path).name

    /**
     * The names of [fixtures], one per file: the base name, and the parent
     * directory for the names two files share.
     */
    fun labels(fixtures: List<String>, dropExtension: Boolean = false): List<String> {
        val names = fixtures.map { file -> nameOf(file, dropExtension) }
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

/**
 * The title of a popup with the files of a fixture folder: the folder itself,
 * relative to the project root (`testdata/cca`), so the reader knows which
 * folder they are looking at. A list that spans several folders (no block
 * element does, but a caller may hand one) keeps the plain title.
 */
internal fun fixturePopupTitle(project: com.intellij.openapi.project.Project, paths: List<String>): String {
    val parents = paths.mapNotNull { java.io.File(it).parent }.distinct()
    if (parents.size != 1) {
        return "Fixtures"
    }
    val folder = java.io.File(parents.first()).toPath().toAbsolutePath().normalize()
    val base = project.basePath?.let { java.io.File(it).toPath().toAbsolutePath().normalize() }
    val relative = if (base != null) {
        try {
            base.relativize(folder).toString().replace('\\', '/')
        } catch (e: IllegalArgumentException) {
            // Another root drive (Windows): the folder has no path in the project.
            ""
        }
    } else {
        ""
    }
    return relative.ifEmpty { folder.fileName?.toString() ?: parents.first() }
}

/**
 * Paints the block: `-- file1.json, file2.json, cca/... (12)`, wrapped over as
 * many lines as the editor is wide.
 *
 * <p>Every link is drawn with the hyperlink colour and an underline; the
 * renderer keeps the rectangle of every link and the editor's mouse listener
 * asks [partAt] which one was hit.
 */
internal class Renderer(
    private val editor: Editor,
    private val parts: List<Part>,
    private val tooltip: String,
    indent: String
) : EditorCustomElementRenderer {

    private val font: Font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
    private val metrics = editor.contentComponent.getFontMetrics(font)
    private val lineHeight: Int = metrics.height + 1
    // A tab has no width of its own in FontMetrics: it is expanded the way the
    // editor expands it, or the block would start left of the declaration.
    private val indentWidth: Int = metrics.stringWidth(
        indent.replace("\t", " ".repeat(editor.settings.getTabSize(editor.project).coerceAtLeast(1)))
    )

    /** One painted piece: a link (a name) or a note. */
    private class Cell(val text: String, val part: Part?, val bounds: Rectangle)

    private var cells: List<Cell> = emptyList()
    private var rows: Int = 1
    private var laidOutWidth: Int = -1

    /** Lays the parts out up to the editor's right margin line. */
    private fun layout() {
        val maxWidth = textWidth()
        if (laidOutWidth == maxWidth) {
            return
        }
        laidOutWidth = maxWidth
        val result = ArrayList<Cell>(parts.size)
        var row = 0
        var x = indentWidth + metrics.stringWidth(PREFIX)
        for (part in parts) {
            val width = metrics.stringWidth(part.label)
            if (x > indentWidth && x + width > maxWidth) {
                row++
                x = indentWidth
            }
            result.add(Cell(part.label, part, Rectangle(x, row * lineHeight, width, metrics.height)))
            x += width
        }
        cells = result
        rows = if (cells.isEmpty()) 1 else cells.last().bounds.y / lineHeight + 1
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        layout()
        val widest = cells.maxOfOrNull { it.bounds.x + it.bounds.width } ?: 0
        return minOf(textWidth(), widest) + GAP
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        layout()
        return rows * lineHeight + 2
    }

    /** The width of the text area up to the thin right-margin line. */
    private fun textWidth(): Int {
        val columns = editor.settings.getRightMargin(editor.project)
        if (columns > 0) {
            val charWidth = metrics.charWidth('W').coerceAtLeast(1)
            return columns * charWidth
        }
        return maxOf(editor.scrollingModel.visibleArea.width - 2 * GAP, 240)
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        layout()
        val oldFont = g.font
        val oldColor = g.color
        g.font = font
        g.color = JBColor.GRAY
        g.drawString(PREFIX, targetRegion.x + indentWidth, baseline(targetRegion, 0))
        for (cell in cells) {
            val row = cell.bounds.y / lineHeight
            val x = targetRegion.x + cell.bounds.x
            g.color = when {
                cell.part?.link != true -> JBColor.GRAY
                cell.part?.muted == true -> mutedColor()
                else -> linkColor()
            }
            g.drawString(cell.text, x, baseline(targetRegion, row))
            if (cell.part?.link == true) {
                val underlineY = baseline(targetRegion, row) + 1
                g.drawLine(x, underlineY, x + metrics.stringWidth(cell.text) - 2, underlineY)
            }
        }
        g.color = oldColor
        g.font = oldFont
    }

    private fun baseline(targetRegion: Rectangle, row: Int): Int =
        targetRegion.y + row * lineHeight + metrics.ascent

    /** The link under the block-relative [x], [y], or null. */
    fun partAt(x: Int, y: Int): Part? {
        layout()
        return cells.firstOrNull {
            it.part?.link == true && x >= it.bounds.x && x <= it.bounds.x + it.bounds.width &&
                y >= it.bounds.y && y <= it.bounds.y + it.bounds.height
        }?.part
    }

    override fun toString(): String = tooltip + ": " + parts.joinToString("") { it.label }

    private fun linkColor(): Color =
        EditorColorsManager.getInstance().globalScheme
            .getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES)?.foregroundColor
            ?: JBColor.BLUE

    /** The colour of the editor's own text: a quiet link is drawn with it. */
    private fun mutedColor(): Color = editor.colorsScheme.defaultForeground ?: JBColor.GRAY

    companion object {
        private const val PREFIX = "-- "
        private const val GAP = 4
    }
}
