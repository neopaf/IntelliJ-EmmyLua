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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.psi.LuaClassMethodDef
import com.tang.intellij.lua.psi.LuaFuncDef
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLocalFuncDef
import com.tang.intellij.lua.psi.LuaTableExpr

/**
 * The test case the caret points at, if any.
 *
 * <p>Two shapes identify a case in a LuaUnit-style file:
 * <ul>
 *   <li>a function named `test...` — `function TestSuite:test_x()`, a
 *       `TestSuite.test_x = function()`, a local or a global one. Its LuaUnit
 *       name is `Class.method` for a class table, the bare name otherwise;</li>
 *   <li>the data entry of a generated case — `id = "some_id"` in a table that a
 *       loop turns into cases (`TestSuite['test_' .. v.id]`). The entry line is
 *       the case's declaration, so the caret there means "this case"; the
 *       pattern is an exact match of the generated name, and when the
 *       generator's prefix cannot be read from the file the case's own name
 *       suffix still selects it.</li>
 * </ul>
 */
object LuaUnitCaseAtCaret {

    fun find(file: PsiFile, element: PsiElement): CaseAtCaret? {
        functionAt(element)?.let { return it }

        val literal = PsiTreeUtil.getParentOfType(element, LuaLiteralExpr::class.java, false)
            ?: PsiTreeUtil.getChildOfType(element.parent, LuaLiteralExpr::class.java)
        if (literal != null) {
            dataEntryAt(element, literal)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ functions

    private fun functionAt(element: PsiElement): CaseAtCaret? {
        // "function T:test_x()" is a LuaClassMethodDef, not a LuaFuncDef, so it
        // has to be looked up separately; the other two are plain functions.
        val func: PsiElement = PsiTreeUtil.getParentOfType(element, LuaClassMethodDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaFuncDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaLocalFuncDef::class.java, false)
            ?: return null
        val name = when (func) {
            is LuaClassMethodDef -> func.name
            is LuaFuncDef -> func.name
            is LuaLocalFuncDef -> func.name
            else -> null
        } ?: return null
        if (!name.startsWith("test")) {
            return null
        }
        // "function _G:test_x()" reads as owner "_G" — LuaUnit reports the
        // case as the bare "test_x", so the prefix must not survive.
        val qualified = (qualifiedName(func, name) ?: name).removePrefix("_G.")
        return CaseAtCaret(qualified, qualified, name)
    }

    /**
     * "TestSuite.test_x" for a `function TestSuite:test_x()`: the text of the
     * definition's head up to the method name. A plain (or `local`) function has
     * no table in front of its name and keeps the bare name.
     */
    private fun qualifiedName(funcDef: PsiElement, name: String): String? {
        val head = funcDef.text
            .substringBefore('(', missingDelimiterValue = "")
            .replace(Regex("\\s+"), " ")
            .trim()
        // "function TestSuite:test_x" / "function TestSuite.test_x"
        val colon = head.lastIndexOf(':')
        if (colon < 0) {
            return null
        }
        val owner = head.substring(0, colon)
            .removePrefix("function ")
            .trim()
            .substringAfterLast(' ')
        if (owner.isEmpty() || !owner.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) {
            return null
        }
        return owner + "." + name
    }

    // ---------------------------------------------------------- data entry

    /** `id = "..."` (or `desc = "..."`): the case generated from this data. */
    private fun dataEntryAt(element: PsiElement, literal: LuaLiteralExpr): CaseAtCaret? {
        val text = literal.text
        if (text.length < 2 || (text.first() != '"' && text.first() != '\'')) {
            return null
        }
        val value = text.substring(1, text.length - 1)
        if (value.isEmpty()) {
            return null
        }
        // The key of the field:  id = "x"  /  ["id"] = "x"
        val field = literal.parent ?: return null
        val fieldText = field.text
        val named = fieldText.trimStart().startsWith("id") || fieldText.contains("[\"id\"]")
                || fieldText.contains("['id']") || fieldText.trimStart().startsWith("desc")
        if (!named) {
            return null
        }
        // The loop that generates this very row, not the first loop of the
        // file: one file may hold several data tables with different prefixes.
        // The name is the full one ("TestSuite.test_x"): the tree of a
        // one-case run matches its cases by it.
        val (suite, prefix) = generatorLoopBefore(element)
        // A row may carry `desc = '...'` next to its `id`: the generator names
        // the case by the desc, so the marker on the id line must offer that
        // name, not the raw id.
        val rowDesc = PsiTreeUtil.getParentOfType(element, LuaTableExpr::class.java, false)
            ?.text?.let { DESC.find(it)?.groupValues?.get(1) }
        val name = rowDesc?.takeIf { it.isNotEmpty() } ?: value
        // A generator of globals ("_G['test_' .. v.id]") makes LuaUnit name
        // the case with no class part — "_G." would match neither the
        // discovery tree nor the runner's --pattern.
        val qualified = if (suite == "_G") "$prefix$name" else "$suite.$prefix$name"
        return CaseAtCaret(prefix + name, qualified, name)
    }

    /**
     * The (suite, prefix) of the loop that builds this row. Several tables may
     * live in one file, each with its own loop; the row belongs to the closest
     * loop above it — and when the data sits above the loop (the usual layout:
     * the table first, the for-loop after it), to the first loop below.
     */
    private fun generatorLoopBefore(element: PsiElement): Pair<String, String> {
        val file = element.containingFile ?: return "TestSuite" to ""
        val pattern = Regex("([A-Za-z_]\\w*)\\s*\\[\\s*['\"]([^'\"]*)['\"]\\s*\\.\\.\\s*[^\\]]+?]")
        var above: Pair<String, String>? = null
        var below: Pair<String, String>? = null
        for (match in pattern.findAll(file.text)) {
            val found = match.groupValues[1] to match.groupValues[2]
            if (match.range.first < element.textOffset) {
                above = found
            } else if (below == null) {
                below = found
            }
        }
        return above ?: below ?: ("TestSuite" to "")
    }

    /** `desc = '...'` of a data row: it overrides the row's `id` as the case name. */
    private val DESC = Regex("""\bdesc\s*=\s*['"]([^'"]+)['"]""")
}

/**
 * A case found at the caret: its LuaUnit name, the Lua pattern that selects it
 * in `--pattern` and the short name shown in the run configuration.
 */
data class CaseAtCaret(val name: String, val pattern: String, val displayName: String)
