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
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import javax.swing.Icon

/**
 * How the last run of a case (or of the cases behind a line) ended.
 *
 * <p>The three states the test tree paints differently: a pass, a failed
 * assertion (an expected/actual pair) and any other failure (an error of the
 * runner, a missing fixture). A line no run has touched has no state at all.
 */
internal enum class LuaUnitTestState { PASSED, FAILED, ERROR }

/**
 * The result a gutter marker shows on a line: which cases decide it and how
 * they ended.
 *
 * <p>The same answer feeds the icon of the run marker and its tooltip. The
 * cases are always the ones the marker's own run would execute: the declaration
 * of a case (a suite function or a data row), the table of a suite, or the
 * files a manifest line runs.
 */
internal object LuaUnitGutterStatus {

    /**
     * The state of [cases]: any failure wins, a plain assertion failure is
     * painted with the "failed" cross and every other one with the error
     * circle — the very rule the test tree follows.
     */
    fun stateOf(cases: Collection<LuaUnitRunIndex.CaseResult>): LuaUnitTestState? {
        if (cases.isEmpty()) {
            return null
        }
        val failed = cases.any { it.failed }
        return when {
            !failed -> LuaUnitTestState.PASSED
            cases.any { it.failed && !it.comparison } -> LuaUnitTestState.ERROR
            else -> LuaUnitTestState.FAILED
        }
    }

    /** The one gutter icon of [cases]: the arrow, with the result when known. */
    fun iconOf(cases: Collection<LuaUnitRunIndex.CaseResult>): Icon {
        // A run in progress clears the previous result: the user looks at the
        // run that just began, not at what the last one left.
        if (LuaUnitHighlights.isRunning()) {
            return LuaUnitStatusIcons.NOT_RUN
        }
        return LuaUnitStatusIcons.of(stateOf(cases))
    }

    /** The tail of the marker tooltip: " — passed", " — failed", " — error". */
    fun suffixOf(cases: Collection<LuaUnitRunIndex.CaseResult>): String {
        if (LuaUnitHighlights.isRunning()) {
            return ""
        }
        return when (stateOf(cases)) {
            LuaUnitTestState.PASSED -> " — passed"
            LuaUnitTestState.FAILED -> " — failed"
            LuaUnitTestState.ERROR -> " — error"
            null -> ""
        }
    }

    /**
     * The cases declared on [line] of [virtualFile]. A record whose line no
     * longer declares the case (the file was edited after the run) is dropped:
     * the answer comes from the document, never from the stale line number.
     */
    fun casesOnLine(
        project: Project,
        virtualFile: VirtualFile,
        document: Document?,
        line: Int
    ): List<LuaUnitRunIndex.CaseResult> {
        if (line < 1) {
            return emptyList()
        }
        val cases = LuaUnitRunIndex.getInstance(project).casesByLine(virtualFile.path)[line]
            ?: return emptyList()
        if (document == null) {
            return cases
        }
        return cases.filter { LuaUnitCaseLines.declares(document, line, it) }
    }

    /** The cases of the suite table [suite] declared in [path]. */
    fun casesOfSuite(project: Project, path: String, suite: String): List<LuaUnitRunIndex.CaseResult> =
        LuaUnitRunIndex.getInstance(project).casesDeclaredIn(path)
            .filter { !it.setup && !it.fileLevel && it.caseName.startsWith("$suite.") }

    /** The cases behind [files]: their own, or the tests that read them. */
    fun casesBehind(
        project: Project,
        files: Collection<Path>
    ): List<LuaUnitRunIndex.CaseResult> {
        val index = LuaUnitRunIndex.getInstance(project)
        return files.flatMap { index.casesBehind(it.toString()) }
    }
}
