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

/** One element of the block: a link (a file, a folded folder) or a note. */
internal class Part(val label: String, val paths: List<String>, val link: Boolean = true)

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
            g.color = if (cell.part?.link == true) linkColor() else JBColor.GRAY
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

    companion object {
        private const val PREFIX = "-- "
        private const val GAP = 4
    }
}
