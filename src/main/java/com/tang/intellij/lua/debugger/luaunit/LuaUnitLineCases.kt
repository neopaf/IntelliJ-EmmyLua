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

import java.nio.file.Path

/**
 * The cases one row of a data module declares.
 *
 * <p>A row feeds every generator that requires the module: `decode_ss7` and
 * `encode_ss7` both build a case from the same `id = "..."` line, and the cases
 * share the name but not the file they run from. A result is therefore matched
 * to its generator by the file the case ran from, not by its name: two
 * generators of one module name their cases alike, and only the generator file
 * tells them apart.
 *
 * <p>Only the run that touched the line last decides what the line shows: a
 * case whose record is older than the newest record of the line did not run in
 * that last run, so its old result says nothing about the line's current state.
 * The line is "covered" when every expected case ran in that last run.
 */
internal object LuaUnitLineCases {

    /** One case a data row declares: its generator file and its LuaUnit name. */
    data class ExpectedCase(val file: Path, val caseName: String) {

        /** The canonical file of the generator: the case record carries it. */
        fun key(): String = LuaUnitRunIndex.canonical(file.toString())

        /**
         * The generator as the gutter menu and the hint name it: the file
         * without the `test_` prefix, which says nothing about the case.
         */
        val label: String
            get() = file.fileName.toString().removeSuffix(".lua").removePrefix("test_")
    }

    /** The cases [generators] build from the row named [rowName]. */
    fun expected(
        generators: List<LuaUnitDataSources.Generator>,
        rowName: String
    ): List<ExpectedCase> = generators.map {
        ExpectedCase(it.file, LuaUnitDataSources.caseName(it, rowName))
    }

    /** The record of [expected] among the cases of the line, of any run. */
    fun resultOf(
        expected: ExpectedCase,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): LuaUnitRunIndex.CaseResult? {
        val key = expected.key()
        return cases.firstOrNull { LuaUnitRunIndex.canonical(it.testFile) == key }
    }

    /** The newest run of the records of one line; 0 for records of an old build. */
    fun latestRun(cases: Collection<LuaUnitRunIndex.CaseResult>): Long =
        cases.maxOfOrNull { it.runId } ?: 0L

    /**
     * The records the newest run of the line left: the cases that did run in
     * it. The older ones did not run then, so they must not colour the line —
     * a failure of a previous run is not what the newest run measured.
     */
    fun casesInLatestRun(
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): List<LuaUnitRunIndex.CaseResult> {
        val latest = latestRun(cases)
        if (latest == 0L) {
            return cases.toList()
        }
        return cases.filter { it.runId == latest }
    }

    /**
     * The record of [expected] in the newest run of the line, or null when the
     * case did not run there.
     *
     * <p>A record of an older build carries no run number at all; one such
     * record next to others of the same old build is the best answer there is,
     * so the whole line is read the old way until the next run gives it a
     * number.
     */
    fun resultInLatestRun(
        expected: ExpectedCase,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): LuaUnitRunIndex.CaseResult? {
        val result = resultOf(expected, cases) ?: return null
        val latest = latestRun(cases)
        if (latest == 0L) {
            return result
        }
        return result.takeIf { it.runId == latest }
    }

    /**
     * The cases of the line with their results: a null state means the case did
     * not run in the newest run of the line.
     */
    fun statuses(
        expected: List<ExpectedCase>,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): List<Pair<ExpectedCase, LuaUnitTestState?>> = expected.map { case ->
        val result = resultInLatestRun(case, cases)
        case to (result?.let { LuaUnitGutterStatus.stateOf(listOf(it)) })
    }

    /**
     * True when the line declares more than one case and some of them did not
     * run in its newest run. A line with no result at all is not partial: it
     * never ran, and the bare run arrow already says so.
     */
    fun partial(
        expected: List<ExpectedCase>,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): Boolean = expected.size > 1 && cases.isNotEmpty() &&
        expected.any { resultInLatestRun(it, cases) == null }

    /**
     * "1 of 2 ran, not run: encode_ss7" — the tail of a marker tooltip and the
     * summary line of the hint.
     */
    fun coverageText(
        expected: List<ExpectedCase>,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): String {
        val ran = expected.count { resultInLatestRun(it, cases) != null }
        val missing = expected.filter { resultInLatestRun(it, cases) == null }.map { it.label }
        val head = "$ran of ${expected.size} ran"
        return if (missing.isEmpty()) head else "$head, not run: ${missing.joinToString(", ")}"
    }
}
