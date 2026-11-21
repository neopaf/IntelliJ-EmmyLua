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
 * The line protocol of `emmylua_luaunit.lua`.
 *
 * <p>Every record is one line starting with a marker; everything else on stdout
 * belongs to the case that last started. The parser is a plain line splitter,
 * so a case's own output — including lines that look like markers — never
 * confuses a half-written line with a record.
 */
object LuaUnitProtocol {

    const val CASE = "@@EMLUA_LUAUNIT_CASE@@"
    const val FAIL = "@@EMLUA_LUAUNIT_FAIL@@"
    const val DONE = "@@EMLUA_LUAUNIT_DONE@@"
    const val FILE = "@@EMLUA_LUAUNIT_FILE@@"
    const val SKIP = "@@EMLUA_LUAUNIT_SKIP@@"
    const val EXPECTED = "@@EMLUA_LUAUNIT_EXPECTED@@"
    const val ACTUAL = "@@EMLUA_LUAUNIT_ACTUAL@@"

    /** A failure record. */
    data class Failure(
        val name: String,
        val isError: Boolean,
        val message: String,
        val expected: String?,
        val actual: String?,
        val trace: String?
    )

    /** The summary of a suite: every discovered case name and the failure count. */
    data class Done(val cases: List<String>, val failed: Int, val discover: Boolean)

    /** A discovered case of a file: its name and where it is declared. */
    data class Discovered(val name: String, val source: String?)

    sealed class Event {
        data class CaseStarted(val name: String) : Event()
        data class CaseFailed(val failure: Failure) : Event()
        data class CaseSkipped(val reason: String) : Event()
        data class Fixture(val file: String) : Event()
        data class Finished(val done: Done, val discovered: List<Discovered>) : Event()
    }

    fun parseLine(line: String): Event? {
        return when {
            line.startsWith(CASE) -> Event.CaseStarted(line.substring(CASE.length).trim())
            line.startsWith(SKIP) -> Event.CaseSkipped(line.substring(SKIP.length).trim())
            line.startsWith(FILE) -> Event.Fixture(line.substring(FILE.length).trim())
            line.startsWith(FAIL) -> Json.decodeFailure(line.substring(FAIL.length))
                ?.let { Event.CaseFailed(it) }
            line.startsWith(DONE) -> Json.decodeDone(line.substring(DONE.length))
            else -> null
        }
    }

    /** "path:line" as a source reference; nil when the line is not a number. */
    fun parseSource(text: String?): LuaUnitNode.LuaUnitSource? {
        if (text.isNullOrBlank()) {
            return null
        }
        val colon = text.lastIndexOf(':')
        if (colon <= 0) {
            return null
        }
        val line = text.substring(colon + 1).trim().toIntOrNull() ?: return null
        return LuaUnitNode.LuaUnitSource(java.nio.file.Paths.get(text.substring(0, colon)), line)
    }

    /**
     * A minimal JSON reader for the two record shapes the runner emits.
     *
     * <p>The runner writes plain single-line JSON, so a full parser would only
     * add a dependency (and a failure mode of its own). Values are read
     * left-to-right: a string with the usual escapes, null, a number, a
     * boolean, or a bracket-nested structure which is skipped as a whole (the
     * expected/actual dumps are strings, never nested objects).
     */
    internal object Json {

        fun decodeFailure(text: String): Failure? {
            val fields = objectOf(text) ?: return null
            val name = fields["name"] as? String ?: return null
            val status = fields["status"] as? String ?: "FAIL"
            return Failure(
                name = name,
                isError = status != "FAIL",
                message = fields["message"] as? String ?: "",
                expected = fields["expected"] as? String,
                actual = fields["actual"] as? String,
                trace = fields["trace"] as? String
            )
        }

        fun decodeDone(text: String): Event.Finished? {
            val fields = objectOf(text) ?: return null
            val discover = fields["discover"] == true
            val failed = (fields["failed"] as? String)?.toIntOrNull() ?: (fields["failed"] as? Int) ?: 0
            val cases = mutableListOf<String>()
            val discovered = mutableListOf<Discovered>()
            (fields["cases"] as? List<*>)?.forEach { item ->
                when (item) {
                    is String -> cases.add(item)
                    is Map<*, *> -> {
                        val name = item["name"] as? String ?: return@forEach
                        cases.add(name)
                        discovered.add(Discovered(name, item["source"] as? String))
                    }
                }
            }
            return Event.Finished(Done(cases, failed, discover), discovered)
        }

        private fun objectOf(text: String): Map<String, Any?>? {
            val reader = Reader(text)
            reader.skipWhitespace()
            val value = reader.readValue() as? Map<*, *> ?: return null
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }

        private class Reader(private val text: String) {
            private var index = 0

            fun skipWhitespace() {
                while (index < text.length && text[index].isWhitespace()) {
                    index++
                }
            }

            fun readValue(): Any? {
                skipWhitespace()
                if (index >= text.length) {
                    return null
                }
                return when (text[index]) {
                    '{' -> readObject()
                    '[' -> readArray()
                    '"' -> readString()
                    'n' -> readLiteral("null", null)
                    't' -> readLiteral("true", true)
                    'f' -> readLiteral("false", false)
                    else -> readNumber()
                }
            }

            private fun readObject(): Map<String, Any?> {
                val result = LinkedHashMap<String, Any?>()
                index++ // '{'
                skipWhitespace()
                if (index < text.length && text[index] == '}') {
                    index++
                    return result
                }
                while (index < text.length) {
                    skipWhitespace()
                    val key = readString()
                    skipWhitespace()
                    if (index < text.length && text[index] == ':') {
                        index++
                    }
                    result[key] = readValue()
                    skipWhitespace()
                    if (index < text.length && text[index] == ',') {
                        index++
                        continue
                    }
                    if (index < text.length && text[index] == '}') {
                        index++
                    }
                    break
                }
                return result
            }

            private fun readArray(): List<Any?> {
                val result = ArrayList<Any?>()
                index++ // '['
                skipWhitespace()
                if (index < text.length && text[index] == ']') {
                    index++
                    return result
                }
                while (index < text.length) {
                    result.add(readValue())
                    skipWhitespace()
                    if (index < text.length && text[index] == ',') {
                        index++
                        continue
                    }
                    if (index < text.length && text[index] == ']') {
                        index++
                    }
                    break
                }
                return result
            }

            private fun readString(): String {
                val out = StringBuilder()
                index++ // opening quote
                while (index < text.length) {
                    val ch = text[index++]
                    when {
                        ch == '"' -> return out.toString()
                        ch == '\\' && index < text.length -> {
                            when (val escaped = text[index++]) {
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'u' -> {
                                    val hex = text.substring(index, minOf(index + 4, text.length))
                                    index += hex.length
                                    out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                }
                                else -> out.append(escaped)
                            }
                        }
                        else -> out.append(ch)
                    }
                }
                return out.toString()
            }

            private fun readLiteral(literal: String, value: Any?): Any? {
                if (text.startsWith(literal, index)) {
                    index += literal.length
                }
                return value
            }

            private fun readNumber(): Any? {
                val start = index
                while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) {
                    index++
                }
                return text.substring(start, index)
            }
        }
    }
}
