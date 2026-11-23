package com.tang.intellij.lua.debugger.luaunit

/**
 * The short text of a failure: what an inlay above the row says, and what the
 * code vision entry of a long row shows.
 */
object LuaUnitFailureText {

    /** How much of the message a short text keeps. */
    private const val MAX_TEXT = 64

    private val VALUE_EXPECTED = Regex("""(?m)^expected:\s*(?!$)(.+)$""")
    private val VALUE_ACTUAL = Regex("""(?m)^actual:\s*(.+)$""")

    fun text(failure: LuaUnitRunIndex.CaseResult): String {
        val expected = failure.expected
        val actual = failure.actual
        val message = failure.message
        val detail = when {
            expected != null && actual != null -> firstDifference(expected, actual)
            else -> {
                val exp = VALUE_EXPECTED.find(message)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?m)^expected:\s*\n\s*(.+)""").find(message)?.groupValues?.get(1)?.trim()
                val act = VALUE_ACTUAL.find(message)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?m)^but was:\s*\n\s*(.+)""").find(message)?.groupValues?.get(1)?.trim()
                if (!exp.isNullOrEmpty() && act != null) "Expected ${exp}, got ${act}"
                else essence(message)
            }
        }
        val oneLine = detail.replace('\n', ' ').trim()
        // No "(click to see diff)" tail: the hint itself is a link (the mouse
        // cursor and the underline say so), and an `error()` has no diff.
        return if (oneLine.length > MAX_TEXT) oneLine.take(MAX_TEXT).trimEnd() + "…" else oneLine
    }

    /**
     * The first line where the two values differ, for a table comparison: the
     * whole documents are shown by the diff behind the click.
     */
    private fun firstDifference(expected: String, actual: String): String {
        val left = expected.lines()
        val right = actual.lines()
        if (left.size == 1 && right.size == 1) {
            return "Expected ${left[0].trim()}, got ${right[0].trim()}"
        }
        val common = minOf(left.size, right.size)
        var index = 0
        while (index < common && left[index] == right[index]) {
            index++
        }
        val at = left.getOrNull(index)?.trim().orEmpty()
        val was = right.getOrNull(index)?.trim().orEmpty()
        return "Expected: $at got $was"
    }

    /** `Test failed: NAME: out_id` names the field the case compared. */
    fun fieldName(message: String): String? {
        val first = message.lineSequence().firstOrNull() ?: return null
        val name = first.substringAfterLast(": ").trim()
        return name.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
    }

    /**
     * The part of a failure message that says what went wrong. A wrapped
     * message starts with `file:line: Test failed: NAME: field`; the file, the
     * line, the marker and the case name only repeat what the editor already
     * shows, so the last segment of that first line is kept.
     */
    private fun essence(message: String): String {
        var line = message.lineSequence().firstOrNull().orEmpty().trim()
        line = line.replace(Regex("""^\S+\.lua:\d+:\s*"""), "")
        val at = line.indexOf("Test failed:")
        if (at >= 0) {
            line = line.substring(at + "Test failed:".length).trim()
            val colon = line.lastIndexOf(": ")
            if (colon > 0) {
                line = line.substring(colon + 2).trim()
            }
        }
        return line
    }
}
