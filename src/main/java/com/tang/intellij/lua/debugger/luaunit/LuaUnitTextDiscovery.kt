package com.tang.intellij.lua.debugger.luaunit

import java.nio.file.Files
import java.nio.file.Path

/**
 * The cases of a test file, read from the file's text on the IDE side.
 *
 * <p>No Lua process is started. The shapes this project uses are plain to read:
 *
 * ```lua
 * function TestSuite:test_x() ... end                 -- a declared case
 * local tests = require('tests/ss7/test_ss7_data')    -- data in another file
 * local testData = { { id = 'A', desc = 'B' }, ... }  -- data in this file
 * for _, v in pairs(tests) do
 *     TestSuite['test_' .. v.id] = function() ... end
 * end
 * ```
 *
 * <p>For a generator loop the case name is the loop's prefix plus the row's
 * `desc` (or `id` when the row has no `desc`); the row's line is the
 * declaration line of the case it generates — that is where a double-click in
 * the tree goes.
 *
 * <p>A case stays a case when it is commented out: every `-- id = 'x'` row and
 * every `--[[ function TestSuite:test_x() ... ]]` block is discovered as an
 * ignored case. The free `-- ...` lines that explain it (directly above the
 * declaration, above the row's `{`, or above the `--[[` opener — shared by
 * every case of that block) become the case's description.
 */
object LuaUnitTextDiscovery {

    /** `TestSuite.test_x`, declared on `file:line` (1-based); [ignored] rows
     *  are commented out and shown as ignored in the tree. */
    data class Case(
        val name: String,
        val source: String,
        val ignored: Boolean = false,
        /** The `-- ...` lines above the declaration, joined with "; ". */
        val description: String = ""
    )

    /**
     * A `function Suite:test_x(` declaration. Leading blanks are allowed but a
     * newline is not: the comment-aware pass decides on its own whether the
     * line is inside a block comment.
     */
    private val DECLARED = Regex("""(?m)^[ \t]*function[ \t]+([A-Za-z_]\w*(?:[ \t]*[.:][ \t]*[A-Za-z_]\w*)?)[ \t]*\(""")

    /** The opener of a block comment: `--[[`, `--[==[`, … (the `=` count must match the closer). */
    private val BLOCK_OPEN = Regex("""--\[(=*)\[""")

    /** `Suite['prefix' .. <expr>]` — the loop that builds one case per row. */
    private val LOOP = Regex("""([A-Za-z_]\w*)\s*\[\s*['"]([^'"]*)['"]\s*\.\.\s*[^\]]+?]""")

    /** `for _, v in pairs(TABLE)` (or `ipairs`) — the table the generator walks. */
    private val FOR = Regex("""for\s+_,\s*\w+\s+in\s+i?pairs\s*\(\s*([A-Za-z_]\w*)\s*\)""")

    private val NAME_PAIR = Regex("""\b(?:desc|id)\s*=\s*['"]([^'"]+)['"]""")

    /** Every case of [file]: its own declarations and the ones its loop builds. */
    fun cases(manifest: Path, file: Path, root: Path): List<Case> {
        val text = try {
            Files.readString(file)
        } catch (e: Exception) {
            return emptyList()
        }
        val lines = text.split('\n')
        val starts = lineStarts(text)
        val blocks = blockComments(text)
        val result = ArrayList<Case>()

        // function Suite:test_name() / function test_name(), outside comments
        for (match in DECLARED.findAll(text)) {
            if (blocks.any { match.range.first in it.first..it.second }) {
                continue
            }
            val name = caseName(match.groupValues[1]) ?: continue
            val line = lineOf(starts, match.range.first)
            result.add(
                Case(
                    name,
                    "${file.toAbsolutePath().normalize()}:$line",
                    false,
                    commentsAbove(lines, line).joinToString("; ")
                )
            )
        }

        // A function hidden by a block comment is still a case — an ignored
        // one, the way a commented-out data row is. The block's own opening
        // line and the free comments above it explain every case it hides.
        for (block in blocks) {
            val description = blockDescription(lines, starts, block)
            for (match in DECLARED.findAll(text.substring(block.first, block.second + 1))) {
                val name = caseName(match.groupValues[1]) ?: continue
                val offset = block.first + match.range.first
                val line = lineOf(starts, offset)
                // When the block started mid-line, its opener counts as the
                // case's own line only for the first declaration.
                result.add(
                    Case(
                        name,
                        "${file.toAbsolutePath().normalize()}:$line",
                        true,
                        description
                    )
                )
            }
        }

        // The generator loop: Suite['prefix' .. v.field] (or just a local name
        // computed from the row) over the table the for-loop walks.
        val loop = LOOP.find(text) ?: return result.sortedBy { it.source.substringAfterLast(':').toIntOrNull() ?: 0 }
        val suite = loop.groupValues[1]
        val prefix = loop.groupValues[2]

        // The table the rows come from: `for _, v in pairs(TABLE)`.
        val table = FOR.find(text)?.groupValues?.get(1)
            // A loop with no plain table name cannot be resolved to data.
            ?: return result.sortedBy { it.source.substringAfterLast(':').toIntOrNull() ?: 0 }

        // The data table lives in another module or in this very file.
        val dataModule = dataModuleOf(text, table)
        val dataFile = dataModule?.let { resolveModule(root, it) } ?: file
        val dataText = try {
            Files.readString(dataFile)
        } catch (e: Exception) {
            return result.sortedBy { it.source.substringAfterLast(':').toIntOrNull() ?: 0 }
        }
        for (row in rows(dataText)) {
            // A generator of globals ("_G['test_' .. v.id] = function()...")
            // names its cases without any class part — exactly how LuaUnit
            // reports them; a class generator ("Suite['prefix'..v.id]")
            // keeps the "Suite." part.
            val caseName = if (suite == "_G") "$prefix${row.name}" else "$suite.$prefix${row.name}"
            result.add(
                Case(
                    caseName,
                    "${dataFile.toAbsolutePath().normalize()}:${row.line}",
                    row.ignored,
                    row.description
                )
            )
        }
        // The tree of one file follows the order of the rows in the table.
        return result.sortedBy { it.source.substringAfterLast(':').toIntOrNull() ?: 0 }
    }

    /** `TestSuite:test_x` -> `TestSuite.test_x`, or null when it is not a test. */
    private fun caseName(raw: String): String? {
        val name = raw.replace(" ", "").replace("\t", "").replace(':', '.')
        // A global test ("function _G.test_x()" or one made by a generator
        // loop) is named "test_x" by LuaUnit — the "_G." prefix would not
        // match the run events, and the case would hang "running" forever.
        val stripped = name.removePrefix("_G.")
        return if (stripped.substringAfterLast('.').startsWith("test")) stripped else null
    }

    /** The [start, end] offsets of every block comment of [text]. */
    private fun blockComments(text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var index = 0
        while (index < text.length) {
            val open = BLOCK_OPEN.find(text, index) ?: break
            val closer = "]" + open.groupValues[1] + "]"
            val close = text.indexOf(closer, open.range.last + 1)
            if (close < 0) {
                // An unterminated block comment hides the rest of the file.
                result.add(open.range.first to text.length - 1)
                break
            }
            result.add(open.range.first to close + closer.length - 1)
            index = close + closer.length
        }
        return result
    }

    /**
     * What explains the cases of one block comment: the free `-- ...` lines
     * above its opener, then the text after `--[[` on the opener line itself.
     */
    private fun blockDescription(lines: List<String>, starts: IntArray, block: Pair<Int, Int>): String {
        val openerLine = lineOf(starts, block.first)
        val parts = ArrayList<String>(commentsAbove(lines, openerLine))
        val opener = lines.getOrNull(openerLine - 1)?.trimStart() ?: ""
        val rest = opener.substringAfter("[[", "").let { text ->
            // The `=` level ("--[[[") is not text.
            val afterLevel = text.trimStart('[', '=')
            afterLevel.trim()
        }
        if (rest.isNotEmpty() && !rest.startsWith("[") && !rest.startsWith("function") && !rest.startsWith("local")) {
            parts.add(rest)
        }
        return parts.joinToString("; ")
    }

    /**
     * The free comment lines directly above [line] (1-based), in source order.
     *
     * <p>A line belongs to the description only when it is a `--` comment with
     * text: the structural lines of a commented-out table (`--{`, `--},`,
     * `--[[`) are code, not prose, and the commented code inside a block must
     * not leak into the name.
     */
    private fun commentsAbove(lines: List<String>, line: Int): List<String> {
        val result = ArrayDeque<String>()
        var index = line - 2
        while (index >= 0) {
            val raw = lines[index].trim()
            if (raw.isEmpty()) {
                break
            }
            if (!raw.startsWith("--") || raw.startsWith("--[[") || raw.startsWith("--[")) {
                break
            }
            val text = raw.removePrefix("--").trim()
            if (!isProse(text)) {
                break
            }
            result.addFirst(text)
            index--
        }
        return result.toList()
    }

    /** True for a line of explanation, false for a commented-out fragment of code. */
    private fun isProse(text: String): Boolean {
        if (text.isEmpty()) {
            return false
        }
        val first = text.first()
        if (first in "{}[]()<>" || first == ',' ) {
            return false
        }
        // `id = '…'`, `desc = …`, `hexData = …`: a commented-out field.
        val word = text.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (word.isNotEmpty() && text.length > word.length && text[word.length] == '=') {
            return false
        }
        return true
    }

    /**
     * The rows of a data table: every `{ ... }` at depth 2 of the outermost
     * table, named by its first `desc` (or `id`). The rows hold nested tables,
     * so a plain regex cannot cut them: the brace depth is walked line by line.
     */
    private fun rows(text: String): List<Row> {
        val result = ArrayList<Row>()
        val lines = text.split('\n')
        var depth = 0
        var current: String? = null
        var currentIgnored = false
        var currentDescription = ""
        var currentLine = 0
        var openLine = 0
        var line = 0
        for (raw in lines) {
            line++
            // A row may be commented out line by line ("--{", "-- id = 'x',",
            // "--},"). The structure is read from the code without the leading
            // comment; `commented` remembers that the line was a comment.
            val trimmed = raw.trimStart()
            val commented = trimmed.startsWith("--")
            val code = if (commented) trimmed.removePrefix("--") else raw
            for (ch in code) {
                when (ch) {
                    '{' -> {
                        depth++
                        if (depth == 2) {
                            current = null
                            currentIgnored = commented
                            currentDescription = ""
                            currentLine = line
                            openLine = line
                        }
                    }
                    '}' -> {
                        if (depth == 2 && current != null) {
                            result.add(Row(current, currentLine, currentIgnored, currentDescription))
                            current = null
                        }
                        depth = (depth - 1).coerceAtLeast(0)
                    }
                }
            }
            if (depth >= 2) {
                val comment = code.indexOf("--")
                val body = if (comment >= 0) code.substring(0, comment) else code
                val pair = NAME_PAIR.find(body)
                if (pair != null) {
                    // `desc` overrides an `id` seen earlier in the same row:
                    // the generator loop names the case by the desc.
                    val isDesc = body.trimStart().startsWith("desc")
                    if (current == null || isDesc) {
                        // The trailing comment of the row, or the free comment
                        // lines above the `{` the row opened with (a commented
                        // row opens with "--{" and its explanation sits above
                        // that line, never on it).
                        var description = if (comment >= 0) code.substring(comment + 2).trim() else ""
                        if (description.isBlank()) {
                            description = commentsAbove(lines, openLine).joinToString("; ")
                        }
                        current = pair.groupValues[1]
                        currentDescription = description
                        currentLine = line
                    }
                }
            }
        }
        return result
    }

    private data class Row(
        val name: String,
        val line: Int,
        val ignored: Boolean = false,
        /** The explanation of the row: its trailing comment or the lines above it. */
        val description: String = ""
    )

    /** The offsets of the first character of every line of [text]. */
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

    /** The 1-based line of [offset], by a binary search in [starts]. */
    private fun lineOf(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low + 1
    }

    /** The module the [table] is required from, if it is not a local one. */
    private fun dataModuleOf(text: String, table: String): String? {
        val require = Regex("""local\s+""" + Regex.escape(table) + """\s*=\s*require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        return require.find(text)?.groupValues?.get(1)
    }

    /** `tests/ss7/test_ss7_data` -> the .lua file, relative to the project root. */
    private fun resolveModule(root: Path, module: String): Path? {
        for (candidate in listOf("$module.lua", module)) {
            val file = root.resolve(candidate).normalize()
            if (Files.isRegularFile(file)) {
                return file
            }
        }
        return null
    }
}
