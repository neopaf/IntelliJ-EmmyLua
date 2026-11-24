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

/**
 * The suite table of a test file, read from its text.
 *
 * <p>A suite is the table its test functions are declared on:
 *
 * ```lua
 * SomeSuite = {}                      -- the declaration
 * function SomeSuite:test_x() ... end -- the tests
 * ```
 *
 * <p>The name of the table is whatever the tests call it — there is no fixed
 * `TestSuite`: a file may use `Tests`, `S7Suite` or anything else. The
 * declaration answers only when the very same name owns a `function
 * <name>:test_...()` of the file, so a plain empty table of a helper does not
 * become a runnable suite. The blanks around `=` and inside `{}` are free.
 */
object LuaUnitSuites {

    /** A Lua identifier (Latin or not: a test may be named in the project's own language). */
    private const val IDENT = """[\p{L}_][\p{L}\p{N}_]*"""

    /**
     * `function Suite:test_x(` / `function Suite.test_x(`: the owner of a test
     * function. Leading blanks are allowed, a newline is not.
     */
    private val TEST_OWNER = Regex(
        """(?m)^[ \t]*function[ \t]+($IDENT)[ \t]*[.:][ \t]*test"""
    )

    /** `Suite.test_x = function(` — the same test, declared as an assignment. */
    private val TEST_ASSIGNED = Regex(
        """(?m)^[ \t]*($IDENT)[ \t]*\.[ \t]*test[\p{L}\p{N}_]*[ \t]*=[ \t]*function[ \t]*\("""
    )

    /** `Suite = {}` / `local Suite = { }` — a plain empty table the tests are put into. */
    private val DECLARATION = Regex("""(?:local[ \t]+)?($IDENT)[ \t]*=[ \t]*\{[ \t]*}""")

    /** The same declaration, anchored to the start of its line. */
    private val DECLARATION_LINE = Regex("""(?m)^[ \t]*(?:local[ \t]+)?($IDENT)[ \t]*=[ \t]*\{[ \t]*}""")

    /** The names of the tables that own a test function of [text]. */
    fun names(text: CharSequence): Set<String> {
        val result = HashSet<String>()
        for (regex in arrayOf(TEST_OWNER, TEST_ASSIGNED)) {
            for (match in regex.findAll(text)) {
                result.add(match.groupValues[1])
            }
        }
        return result
    }

    /**
     * The suite tables declared by [text], in the order their declarations
     * appear (`A = {}` above `B = {}`). Tables without a test of their own are
     * not suites and stay out.
     */
    fun declarations(text: CharSequence): List<String> {
        val owners = names(text)
        val result = ArrayList<String>()
        for (match in DECLARATION_LINE.findAll(text)) {
            val name = match.groupValues[1]
            if (name in owners && name !in result) {
                result.add(name)
            }
        }
        return result
    }

    /**
     * The name of the suite declared on the line that holds [offset], or null.
     *
     * <p>Only the first non-blank character of the line answers: the callers
     * put one marker per line, and any other leaf of the line would add a
     * second one. A commented-out declaration is text, not a suite.
     */
    fun declaredAt(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset >= text.length) {
            return null
        }
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') {
            lineStart--
        }
        var first = lineStart
        while (first < offset && (text[first] == ' ' || text[first] == '\t')) {
            first++
        }
        if (first != offset) {
            return null
        }
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') {
            lineEnd++
        }
        val line = text.subSequence(lineStart, lineEnd).toString().trim()
        if (line.startsWith("--")) {
            return null
        }
        val name = DECLARATION.matchEntire(line)?.groupValues?.get(1) ?: return null
        return if (name in names(text)) name else null
    }
}
