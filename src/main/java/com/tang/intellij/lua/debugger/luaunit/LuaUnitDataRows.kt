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
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.psi.LuaTableExpr
import com.tang.intellij.lua.psi.LuaTableField

/**
 * The rows of a data module as the editor needs them: a generated case is
 * declared by an `id = "..."` field, its assertion is a line of the generator
 * every row shares, and a failure message may name the field it compared.
 *
 * <p>The three places that need this — the code vision above a row, the inlay
 * at the failed field and the navigation of a failed case — ask the same
 * questions, so the answers live here.
 */
internal object LuaUnitDataRows {

    /** The `id = "..."` field declared at [line] (1-based), or null. */
    fun idField(file: PsiFile, document: Document, line: Int): LuaTableField? =
        PsiTreeUtil.findChildrenOfType(file, LuaTableField::class.java)
            .firstOrNull {
                it.name == "id" && lineOf(document, it) == line
            }

    /**
     * True when [literal] is the value of a row's `id` or `desc` field.
     *
     * <p>The key of the field must be exactly `id`/`desc` (or `["id"]` /
     * `['id']`), not a mere prefix like `identifier`; the leading name is
     * matched whole. `desc` is the row's case name when the generator reads it
     * first, so its literal is a case-declaration anchor too.
     */
    fun isEntryLiteral(literal: com.tang.intellij.lua.psi.LuaLiteralExpr): Boolean {
        val text = literal.text
        if (text.length < 2 || (text.first() != '"' && text.first() != '\'')) {
            return false
        }
        val field = literal.parent?.text?.trimStart() ?: return false
        val name = field.takeWhile { it.isLetterOrDigit() || it == '_' }
        return name == "id" || name == "desc" ||
            field.startsWith("[\"id\"]") || field.startsWith("['id']") ||
            field.startsWith("[\"desc\"]") || field.startsWith("['desc']")
    }

    /** True when the literal is the value of the row's `desc` field. */
    fun isDescField(literal: com.tang.intellij.lua.psi.LuaLiteralExpr): Boolean {
        val field = literal.parent?.text?.trimStart() ?: return false
        val name = field.takeWhile { it.isLetterOrDigit() || it == '_' }
        return name == "desc" || field.startsWith("[\"desc\"]") || field.startsWith("['desc']")
    }

    /** True when the row of the literal has a `desc` field besides the `id`. */
    fun hasDescSibling(literal: com.tang.intellij.lua.psi.LuaLiteralExpr): Boolean {
        val row = PsiTreeUtil.getParentOfType(
            literal, LuaTableExpr::class.java, false
        ) ?: return false
        return row.findField("desc") != null
    }

    /** The table of the row that declares its `id` at [line], or null. */
    fun rowOf(file: PsiFile, document: Document, line: Int): LuaTableExpr? {
        val id = idField(file, document, line) ?: return null
        return PsiTreeUtil.getParentOfType(id, LuaTableExpr::class.java, false)
    }

    /**
     * The line of the field the failure named (`Test failed: NAME: out_id`),
     * or 0 when the message names no field of the row. The field is often
     * nested (`metrics = { SLA_2001 = 2 }`): the counter's own line is the one
     * the reader wants.
     */
    fun namedFieldLine(file: PsiFile, document: Document, line: Int, message: String): Int {
        val name = LuaUnitFailureText.fieldName(message) ?: return 0
        val id = idField(file, document, line) ?: return 0
        val row = PsiTreeUtil.getParentOfType(id, LuaTableExpr::class.java, false) ?: return 0
        val target = row.findField(name)
            ?: PsiTreeUtil.findChildrenOfType(row, LuaTableField::class.java)
                .firstOrNull { it !== id && it.name == name }
            ?: return 0
        return lineOf(document, target)
    }

    /** True when the failure named a field that really is inside the row. */
    fun hasNamedField(file: PsiFile, document: Document, line: Int, message: String): Boolean =
        namedFieldLine(file, document, line, message) > 0

    private fun lineOf(document: Document, element: com.intellij.psi.PsiElement): Int =
        document.getLineNumber(element.textRange.startOffset) + 1
}
