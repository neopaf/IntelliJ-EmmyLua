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

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager

/**
 * Runs the whole configuration: discovery, then the suite processes and the
 * console.
 *
 * <p>1. The tree is discovered: the manifest gives the sections and files,
 *    then every file is asked for its cases (`--discover`, a Lua process that
 *    loads the file but runs nothing).
 * <p>2. One Lua process per file is started, all of them at once, all feeding
 *    one test console (see [LuaUnitTestConsole]).
 */
class LuaUnitRunState(
    private val configuration: LuaUnitRunConfiguration,
    private val executor: Executor,
    private val debug: Boolean,
    @Volatile var debugPort: Int? = null,
    /** The tab of this run: the summary label is added to it. */
    private val runEnvironment: com.intellij.execution.runners.ExecutionEnvironment? = null
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val prepared = prepare()
        val handlers = start(prepared)
        // The tab of the run must live as long as any of its suite processes:
        // the first one finishing early used to mark the whole run
        // terminated, and the still-arriving events painted stray crosses.
        val group = LuaUnitProcessGroupHandler(handlers.size) { prepared.console.onStopped() }
        handlers.forEach { group.track(it) }
        // The console follows the whole group, not the first suite's
        // process: attached to the first one, its early (and normal — a
        // failed suite exits at once) termination told the SM runner the
        // run was over, and it painted the still-running nodes of the other
        // suites red (TerminatedState) before their events could close them.
        // The group notifies only when every suite process is done.
        prepared.view.attachToProcess(group)
        group.startNotify()
        return LuaUnitExecutionResult(
            prepared.view, group,
            // The toolbar of the run's tab: rerunning and debugging the failed
            // cases again is what a user reaches for right after a red run.
            arrayOf(
                prepared.console.rerunFailedAction(),
                LuaUnitDebugFailedAction(configuration, prepared.console)
            )
        )
    }

    /**
     * Everything of a run except the processes: the discovery, the tree and
     * the console view. A debug run builds this first, hands the view to the
     * debug session (its Console tab is the tests tree itself, the Java way)
     * and only then starts the processes — they must not run before the
     * mobdebug server listens.
     */
    fun prepare(): Prepared {
        val project = configuration.project
        FileDocumentManager.getInstance().saveAllDocuments()

        // No modal progress dialog: the discovery is quick and the suites'
        // processes make the real progress visible in the console tree.
        val tree = LuaUnitDiscovery(configuration).discover()

        val suites = suitesFor(configuration, tree)
        if (suites.isEmpty()) {
            throw ExecutionException("No test files found for ${configuration.name}")
        }

        val console = LuaUnitTestConsole(
            configuration,
            LuaUnitConsoleProperties(configuration, executor),
            debug,
            tree,
            suites
        ).apply { environment = runEnvironment }
        console.debugPort = if (debug) debugPort else null
        val view = console.createView()
        return Prepared(console, view, suites)
    }

    /** Starts the suite processes of a [prepare]d run; a debug run hands its
     *  lazily created handlers to [processGroup] as it creates them. */
    fun start(
        prepared: Prepared,
        processGroup: LuaUnitProcessGroupHandler? = null
    ): List<ProcessHandler> = prepared.console.start(prepared.suites, processGroup)

    /** A run with everything but its processes. */
    class Prepared(
        val console: LuaUnitTestConsole,
        val view: com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView,
        val suites: List<LuaUnitSuite>
    )
}

/**
 * The suites to run. A "rerun failed" copy carries its cases instead of a
 * scope: each of its files becomes a suite whose pattern runs the union of the
 * failed case names of that file, and its tree nodes are exactly those cases.
 */
internal fun suitesFor(configuration: LuaUnitRunConfiguration, tree: LuaUnitNode.Root): List<LuaUnitSuite> {
    if (configuration.failedCases.isEmpty()) {
        return LuaUnitTestConsole.suitesOf(configuration, tree)
    }
    val byFile = LinkedHashMap<java.nio.file.Path, MutableList<String>>()
    for (entry in configuration.failedCases) {
        val file = entry.substringBefore('\u0000')
        val name = entry.substringAfter('\u0000')
        byFile.getOrPut(java.nio.file.Path.of(file)) { ArrayList() }.add(name)
    }
    // The rerun tree keeps the groups of the manifest: a suite knows its
    // section, and the console builds the section nodes from it. The order of
    // the manifest is kept as well (the map is filled in the failed-list
    // order, which is the console's order, not the manifest's).
    data class RerunSuite(val section: Int, val file: Int, val suite: LuaUnitSuite)

    val found = ArrayList<RerunSuite>()
    for ((file, names) in byFile) {
        // The discovered tree knows every case of the file: only the failed
        // ones become nodes — that is what runs and what the tree must show.
        // The failed list carries the suite's display path (often relative to
        // the project), the tree an absolute one — either may extend the other.
        var section: LuaUnitNode.Section? = null
        var known: LuaUnitNode.Suite? = null
        for (candidate in tree.sections) {
            val suite = candidate.suites.firstOrNull {
                it.file.endsWith(file) || file.endsWith(it.file)
            }
            if (suite != null) {
                section = candidate
                known = suite
                break
            }
        }
        val cases = known?.cases?.filter { it.name in names } ?: emptyList()
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
            "rerun suite file=$file section=${section?.title} known=${known?.file} " +
                "cases=${cases.map { it.name }} names=$names"
        )
        val sectionIndex = tree.sections.indexOf(section).coerceAtLeast(0)
        val fileIndex = section?.suites?.indexOf(known).takeIf { it != null && it >= 0 } ?: 0
        found.add(
            RerunSuite(
                sectionIndex, fileIndex,
                LuaUnitSuite(
                    section?.title?.ifEmpty { null },
                    file,
                    known?.displayName ?: file.toString(),
                    cases,
                    // Not one `|`-joined Lua pattern: `|` is a literal there,
                    // so the pattern matched nothing and every suite of a
                    // rerun reported (0).
                    pattern = null,
                    caseNames = names
                )
            )
        )
    }
    return found.sortedWith(compareBy({ it.section }, { it.file })).map { it.suite }
}

/**
 * A case name as a Lua pattern: the pattern magic characters must not read as
 * magic. Every one of them is prefixed with `%`; a regular expression with a
 * character class is not an option here — a dash at the end of the class
 * silently turned into a range (`%-?` covered `0`-`9`), and the pattern of a
 * case then contained `M%3UA` and `Frame %0%6`, which matched nothing.
 */
internal fun luaPattern(name: String): String {
    val magic = "%^$().[]*+-?"
    val text = StringBuilder(name.length * 2)
    for (c in name) {
        if (magic.indexOf(c) >= 0) {
            text.append('%')
        }
        text.append(c)
    }
    return text.toString()
}

/** The console, the suite processes (and the debugger) of the run's tab. */
class LuaUnitExecutionResult(
    private val console: ExecutionConsole,
    private val processHandler: ProcessHandler,
    private val actions: Array<AnAction> = emptyArray()
) : ExecutionResult {
    override fun getExecutionConsole() = console
    override fun getActions() = actions
    override fun getProcessHandler() = processHandler
}

/**
 * The process of a multi-suite run: one tab, many Lua processes. It stays
 * alive while any of them runs and finishes when the last one does, so the
 * tab is not marked terminated by the fastest suite; the stop button
 * destroys every process of the group.
 *
 * <p>The handlers are tracked one by one, as they are created: a debug run
 * starts its suites lazily, one after another, and the group is attached to
 * the console before the first of them exists. [expected] says how many
 * handlers will eventually be tracked.
 */
class LuaUnitProcessGroupHandler(
    private val expected: Int,
    /** Runs when the group is stopped: the tests tree has to close its still-spinning nodes. */
    private val onStopped: (() -> Unit)? = null
) : ProcessHandler() {

    private val tracked = java.util.concurrent.CopyOnWriteArrayList<ProcessHandler>()
    private val alive = java.util.concurrent.atomic.AtomicInteger(expected)
    private val notified = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True once the whole run (and with it the group) is being stopped. */
    @Volatile
    var isStopping = false
        private set

    constructor(handlers: List<ProcessHandler>) : this(handlers.size) {
        handlers.forEach { track(it) }
    }

    override fun startNotify() {
        super.startNotify()
        // Every process may have finished before the group was notified
        // (a notify before startNotify is refused by the platform).
        checkDone()
    }

    /** Adds [handler] to the group; it counts towards [expected]. */
    fun track(handler: ProcessHandler) {
        if (isStopping) {
            // The run is over — this handler must not run at all.
            handler.destroyProcess()
            return
        }
        tracked.add(handler)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                if (done.compareAndSet(false, true)) {
                    tracked.remove(handler)
                    alive.decrementAndGet()
                    checkDone()
                }
            }
        })
        if (handler.isProcessTerminated && done.compareAndSet(false, true)) {
            tracked.remove(handler)
            alive.decrementAndGet()
            checkDone()
        }
    }

    private fun checkDone() {
        if (alive.get() <= 0 && notified.compareAndSet(false, true)) {
            notifyProcessTerminated(0)
        }
    }

    override fun destroyProcessImpl() {
        isStopping = true
        tracked.forEach { it.destroyProcess() }
        // The Stop button kills the processes, but no case result arrives
        // anymore: without this callback the tree keeps spinning forever.
        onStopped?.invoke()
    }

    override fun detachProcessImpl() {
        isStopping = true
        tracked.forEach { it.detachProcess() }
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): java.io.OutputStream? = null
}
