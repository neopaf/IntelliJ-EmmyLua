package com.tang.intellij.lua.debugger.luaunit

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange

/**
 * How a line of a test file fits into the visible area of the editor.
 *
 * <p>The tail of a long line (a hex dump or a comment) is out of view, so a
 * hint placed there would be invisible. Both the inlay provider and the code
 * vision provider ask this object, so a row is either marked in place or moved
 * above its block, never both.
 */
object LuaUnitLineLayout {

    /** A line is "long" when its text does not fit into the visible width. */
    fun isLong(editor: Editor, document: Document, line: Int): Boolean {
        if (line < 1 || line > document.lineCount) {
            return false
        }
        return try {
            val start = document.getLineStartOffset(line - 1)
            val end = document.getLineEndOffset(line - 1)
            val component = editor.contentComponent
            val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
            val textWidth = component.getFontMetrics(font)
                .stringWidth(document.getText(TextRange(start, end)))
            val visible = component.visibleRect.width
            (visible > 0 && textWidth > visible - 60) || textWidth > DEFAULT_WIDTH
        } catch (e: Throwable) {
            // The scrolling model may only be asked on the EDT; this pass runs
            // on a pooled thread, so anything Swing refuses falls back to the
            // character count.
            val start = document.getLineStartOffset(line - 1)
            val end = document.getLineEndOffset(line - 1)
            end - start > 60
        }
    }

    private const val DEFAULT_WIDTH = 900
}
