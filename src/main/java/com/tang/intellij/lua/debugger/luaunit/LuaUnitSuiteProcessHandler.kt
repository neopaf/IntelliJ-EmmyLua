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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key

/**
 * The process of one test file. Its stdout is the marker protocol of
 * `emmylua_luaunit.lua`; the console turns it into tree updates and output.
 *
 * <p>The root process of the console is the first suite's; the others are
 * plain handlers attached to the same console view.
 */
class LuaUnitSuiteProcessHandler(
    private val suite: LuaUnitSuite,
    private val console: LuaUnitTestConsole,
    commandLine: GeneralCommandLine
) : OSProcessHandler(commandLine) {

    private val converter = LuaUnitOutputConverter(suite, console)

    /**
     * The tail of a line that has not arrived whole. stdout reaches the process
     * listener in arbitrary chunks, so a long marker line (the DONE record with
     * every case name) can be split; without this buffer each chunk would be
     * parsed as a line of its own and the record would never be recognized.
     */
    private var partial = StringBuilder()

    /**
     * The raw stdout must not reach the console: it is the marker protocol, and
     * the console shows the parsed events instead. Overriding this without
     * calling super keeps the raw lines off the console view.
     */
    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        if (outputType == ProcessOutputTypes.SYSTEM) {
            // The platform echoes the command line here; the tree already
            // says what runs — a bare "lua /var/folders/..." line is noise.
            return
        }
        if (outputType != ProcessOutputTypes.STDOUT) {
            converter.errorText(text)
            return
        }
        val buffer = partial.append(text)
        var start = 0
        while (true) {
            val newline = buffer.indexOf("\n", start)
            if (newline < 0) {
                break
            }
            converter.acceptLine(buffer.substring(start, newline))
            start = newline + 1
        }
        buffer.delete(0, start)
    }

    init {
        addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                console.onSuiteStarted(suite)
                converter.start()
            }

            override fun processTerminated(event: ProcessEvent) {
                if (partial.isNotEmpty()) {
                    converter.acceptLine(partial.toString())
                    partial = StringBuilder()
                }
                converter.finish(event.exitCode)
            }
        })
    }
}

/**
 * Turns the lines of one suite's stdout into console updates.
 *
 * <p>Everything between markers belongs to the case that last started (or to
 * the suite, before the first one); a `CodeFailed` record always belongs to
 * that same case. The converter keeps that state, so the console never has to.
 */
class LuaUnitOutputConverter(
    private val suite: LuaUnitSuite,
    private val console: LuaUnitTestConsole
) {

    private var currentCase: String? = null

    /** Failure by case name, reported when the case finishes (the last line of
     *  its block is the FAIL record, after its output). */
    private var pendingFailure: LuaUnitProtocol.Failure? = null

    /** The skip reason of the current case, if luaunit.skip() was called. */
    private var pendingSkip: String? = null

    /**
     * The files the current case opened (`at fixture(...)`). They are kept
     * until the case finishes, so they are printed under the final status:
     * under "PASSED" for a green case, at the end of the trace for a red one —
     * not in the middle of the case's own output.
     */
    private val pendingFixtures = ArrayList<String>()

    private var finished = false

    /** True once a case-level failure record arrived: the suite's own "N
     *  failure(s)" line is then only a duplicate of what the tree shows. */
    private var sawCaseFailure = false

    /** True once the DONE marker arrived: the suite reported its own end. */
    private var sawDone = false

    private val log = com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")

    fun start() = Unit

    fun acceptLine(line: String) {
        val event = LuaUnitProtocol.parseLine(line)
        if (event == null) {
            console.onOutput(suite, currentCase, line + "\n", false)
            return
        }
        when (event) {
            is LuaUnitProtocol.Event.CaseStarted -> {
                closeCase()
                currentCase = event.name
                console.onCaseStarted(suite, event.name)
            }
            is LuaUnitProtocol.Event.Fixture -> {
                if (currentCase != null) {
                    pendingFixtures.add(event.file)
                }
            }
            is LuaUnitProtocol.Event.CaseFailed -> {
                // The trace belongs to the case that just failed; the earlier
                // output of the case has already been printed.
                log.info("suite ${suite.key}: FAIL ${event.failure.name}: ${event.failure.message.take(60)}")
                pendingFailure = event.failure
                sawCaseFailure = true
            }
            is LuaUnitProtocol.Event.CaseSkipped -> {
                // luaunit.skip("reason"): the case is ignored, not failed.
                pendingSkip = event.reason
            }
            is LuaUnitProtocol.Event.Finished -> {
                log.info("suite ${suite.key}: DONE cases=${event.done.cases.size} failed=${event.done.failed}")
                sawDone = true
                closeCase()
                if (event.done.failed > 0 && !sawCaseFailure) {
                    // A load-time failure or a crash: the summary says the run
                    // is red but no case-level record explains it.
                    console.onOutput(suite, null, "The suite finished with ${event.done.failed} failure(s)\n", true)
                }
            }
        }
    }

    /** stderr of the suite goes to the current case (or the suite) in red. */
    fun errorText(text: String) {
        if (text.isNotEmpty()) {
            console.onOutput(suite, currentCase, text, true)
        }
    }

    private fun closeCase() {
        val name = currentCase ?: return
        val skip = pendingSkip
        if (skip != null) {
            console.onCaseSkipped(suite, name, skip)
        } else {
            console.onCaseFinished(suite, name, pendingFailure, ArrayList(pendingFixtures))
        }
        currentCase = null
        pendingFailure = null
        pendingSkip = null
        pendingFixtures.clear()
    }

    fun finish(exitCode: Int) {
        if (finished) {
            return
        }
        finished = true
        val open = currentCase
        if (open != null) {
            // The process died before the case reported its end — no DONE
            // marker ever closed it. Closing it green would lie about a dead
            // suite (and any "terminated" paint the platform already put on
            // the node would stay); an explicit failure resets the node to
            // an honest red and says what happened.
            val abrupt = pendingFailure ?: LuaUnitProtocol.Failure(
                open, true,
                "The Lua process exited (code $exitCode) before the case finished", null, null, null
            )
            console.onCaseFinished(suite, open, abrupt, ArrayList(pendingFixtures))
            currentCase = null
            pendingFailure = null
            pendingSkip = null
            pendingFixtures.clear()
        }
        log.info(
            "suite finished: file=${suite.key} exit=$exitCode done=$sawDone " +
                "openCase=${open != null} caseFailures=$sawCaseFailure"
        )
        console.onSuiteFinished(suite)
        if (exitCode != 0 && suite.cases.isEmpty()) {
            console.onOutput(suite, null, "The Lua process exited with code $exitCode\n", true)
        }
    }
}
