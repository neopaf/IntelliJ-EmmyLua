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

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

/**
 * Whether the line of the index still declares the case it names.
 *
 * <p>The index keeps the line a case was declared at during its run, and the
 * file may change afterwards: a case removed from the file (or the lines above
 * it shifted) leaves a record whose line now points into the middle of a table.
 * Drawing such a record puts the fixtures and the result of a dead case into
 * the body of a live one — a block of file links in the middle of a `destMap`.
 *
 * <p>A line that still declares the case is not enough: the record remembers
 * the text of the line as the run saw it, and a line rewritten since (a
 * commented-out row brought back, an `id` renamed, a line that shifted onto the
 * declaration of a neighbour) is not the declaration the record speaks about.
 *
 * <p>Every editor integration therefore asks here before drawing anything, and
 * a record whose line no longer declares the case stays invisible. The answer
 * is a look at one line of the document, never the PSI: the question is asked
 * on every scroll and every repaint.
 */
internal object LuaUnitCaseLines {

    /** The keyword of a declaration: `function Suite:test_x()` and `Suite.test_x = function(`. */
    private const val FUNCTION = "function"

    /** `id = "..."` — the row of a data module that declares a generated case. */
    private val ID_ROW = Regex("""(?<![\p{L}\p{N}_.])id[ \t]*=[ \t]*["']""")

    /** A plain Lua identifier (Latin or not, as the case names of a project are). */
    private val PLAIN_NAME = Regex("""[\p{L}_][\p{L}\p{N}_]*""")

    /**
     * True when [line] (1-based) of [document] declares [case]: a function of
     * that name, or the `id` row of a generated one.
     *
     * <p>A plain name is looked for in the line, so a shifted record lands on
     * the declaration of another case and is rejected there too. A name with
     * blanks or punctuation (`test_HDAS-227 accept @501`) is not a Lua
     * identifier and cannot be matched word by word: such a case only has to
     * meet a declaration of some kind.
     */
    fun declares(
        document: Document,
        line: Int,
        case: LuaUnitRunIndex.CaseResult
    ): Boolean {
        if (line < 1 || line > document.lineCount) {
            return false
        }
        val text = document.getText(
            TextRange(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1))
        ).trim()
        // The record remembers the line as the run saw it. A different text
        // means the record belongs to another state of the file (the line was
        // rewritten, or the lines moved and this one is a stranger): the
        // result of the run says nothing about it.
        if (case.declarationText.isNotEmpty() && case.declarationText != text) {
            return false
        }
        // A commented-out twin of a case (`-- function Suite:test_x()`): the
        // run never executes it, so its line declares nothing.
        if (text.isEmpty() || text.startsWith("--")) {
            return false
        }
        if (ID_ROW.containsMatchIn(text)) {
            return true
        }
        if (!text.contains(FUNCTION)) {
            return false
        }
        val name = case.caseName.substringAfterLast('.')
        if (!PLAIN_NAME.matches(name)) {
            return true
        }
        val word = Regex("""(?<![\p{L}\p{N}_])""" + Regex.escape(name) + """(?![\p{L}\p{N}_])""")
        return word.containsMatchIn(text)
    }
}
