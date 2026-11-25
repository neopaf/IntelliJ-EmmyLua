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

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A failing case as the console saw it. */
data class LuaUnitFailure(val suite: String, val caseName: String)

/**
 * One suite: a test file, its Lua process and the nodes of its cases.
 */
class LuaUnitSuite(
    val section: String?,
    val file: Path,
    val displayName: String,
    val cases: List<LuaUnitNode.Case>,
    /** The LuaUnit `--pattern`; null runs every case of the file. */
    val pattern: String?,
    /**
     * Exact case names to run (a rerun of the failed ones). `--pattern` is a
     * Lua pattern with no alternation, so a list of names cannot be squeezed
     * into one; the runner takes them one by one.
     */
    val caseNames: List<String> = emptyList()
) {
    /** Stable identity of the suite (its display name), used as the node key. */
    val key: String get() = displayName

    @Volatile
    var proxy: SMTestProxy? = null
}

/**
 * The test console of one LuaUnit configuration run.
 *
 * <p>Every suite — a test file — is a separate Lua process. They all start at
 * once and feed this one tree; the first one is the root process of the console
 * (that is what makes the platform create the tree, its toolbar and the
 * counters), the others feed the same tree through their nodes.
 *
 * <p>The tree is built from discovery (section → file → case) when the platform
 * reports the console's root proxy, so a red case is one click from its
 * declaration; case nodes are updated live as the suites report. A failure
 * carries the two compared values as a comparison failure, which the console
 * renders as the click-to-diff pair.
 *
 * <p>A case's output — including the `at fixture(path:line)` frames of the files
 * it opened — is printed into the case node, the way a JVM test runner does it.
 */
class LuaUnitTestConsole(
    private val configuration: LuaUnitRunConfiguration,
    private val properties: com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties,
    private val debug: Boolean,
    private val tree: LuaUnitNode.Root,
    private val suites: List<LuaUnitSuite>
) {

    private val lock = Any()

    /**
     * The events that arrived before the tree was built. The suite processes
     * start (and report their first cases) before the platform reports the
     * console's root proxy, so the tree may not exist yet; the events are
     * kept here and replayed on the nodes IN THEIR ARRIVAL ORDER. They used
     * to sit in three buffers replayed states-first, and a case that finished
     * before the tree was built got its green "PASSED" printed above its own
     * output — the marker belongs under it.
     */
    private val journal = ArrayList<() -> Unit>()

    private val caseProxies = ConcurrentHashMap<String, SMTestProxy>()

    @Volatile
    private var root: SMTestProxy.SMRootTestProxy? = null

    /** The group nodes (manifest sections), closed when every suite is done. */
    private val sectionProxies = ArrayList<SMTestProxy>()

    /**
     * The suite-table nodes of the files that hold more than one suite
     * (`ABCGroup`, `Tests`, ...), closed with the sections.
     */
    private val classProxies = ArrayList<SMTestProxy>()

    /** How many suite processes have reported their end. */
    private var finishedSuites = 0

    /** When each running case started, to give the node its duration. */
    private val caseStarts = ConcurrentHashMap<String, Long>()

    lateinit var consoleView: SMTRunnerConsoleView
        private set

    /** The failed cases of the run, in the order they failed. */
    val failures: MutableList<LuaUnitFailure> = java.util.Collections.synchronizedList(ArrayList())

    /**
     * The case nodes that reported their end (green or red): a case that has
     * merely started, or has not started at all, counts as "running" — never
     * as "passed" — in the counters of its groups.
     */
    private val finishedCases: MutableSet<SMTestProxy> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * The cases of this run, as the editor integrations will see them: where
     * each case is declared, how it ended and the files it opened. Published
     * into [LuaUnitRunIndex] once every suite is over — a file of the project
     * must not flash green from the first case of a run still going.
     */
    private val runResults = LinkedHashMap<String, LuaUnitRunIndex.CaseResult>()

    /** Port of the mobdebug server under Debug, null otherwise. */
    @Volatile
    var debugPort: Int? = null

    /** The console with the summary line; set by the run state before the tree is built. */
    @Volatile
    var environment: com.intellij.execution.runners.ExecutionEnvironment? = null

    /** The group nodes (sections, suite tables, suite files) with their plain label, for the counters. */
    private val groupLabels = LinkedHashMap<SMTestProxy, String>()

    /** A suite (file) node -> the file's path from the project root, what
      * Cmd+C on the node puts on the clipboard (no ":1" line suffix). */
    private val suiteNodePaths = LinkedHashMap<SMTestProxy, String>()

    /** The tests trees of the view (a debug tab keeps its own), found once. */
    private val testTrees =
        ArrayList<com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView>()

    /**
     * The "(3 passed, 1 failed, 2 ignored)" of a group. A category is shown
     * when the tree shows it (the failed tests have no filter of their own); a
     * zero block is dropped. A case that has not finished yet is not counted
     * at all: the group's number moves only with the results. A group with
     * nothing to count yet (the run has just started) has no parentheses at
     * all — "(0)" would read as a result.
     */
    private fun countSuffix(node: SMTestProxy): String {
        val showPassed = !com.intellij.execution.testframework.TestConsoleProperties
            .HIDE_PASSED_TESTS.value(properties)
        val showIgnored = !com.intellij.execution.testframework.TestConsoleProperties
            .HIDE_IGNORED_TEST.value(properties)
        var passed = 0
        var failed = 0
        var ignored = 0
        fun walk(current: SMTestProxy) {
            for (child in current.children) {
                if (!child.isLeaf) {
                    walk(child)
                } else when {
                    child.isIgnored -> ignored++
                    child.isDefect -> failed++
                    finishedCases.contains(child) -> passed++
                }
            }
        }
        walk(node)
        val parts = ArrayList<String>()
        if (showPassed && passed > 0) parts.add("$passed passed")
        if (failed > 0) parts.add("$failed failed")
        if (showIgnored && ignored > 0) parts.add("$ignored ignored")
        return if (parts.isEmpty()) "" else "(${parts.joinToString(", ")})"
    }

    /** The name of a group node: its label and the counters, when there are some. */
    private fun groupName(label: String, node: SMTestProxy): String {
        val suffix = countSuffix(node)
        return if (suffix.isEmpty()) label else "$label $suffix"
    }

    /** Writes the counters of every group node, on the EDT. */
    private fun applyGroupCounts() {
        installHiddenIgnoredRenderer()
        for ((proxy, label) in groupLabels) {
            proxy.setPresentableName(groupName(label, proxy))
        }
    }

    /**
     * Recounts the groups on the view mode flip (hide passed / hide ignored)
     * and repaints the tree: a group that holds ignored cases must lose the
     * "has ignored" mark once those cases are hidden (see
     * [LuaUnitHiddenIgnoredRenderer]). The icon is picked at paint time, so
     * the rows are repainted even when their texts did not change.
     */
    private fun updateGroupCounts() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            applyGroupCounts()
            testTrees.forEach { it.repaint() }
        }
    }

    /**
     * Puts the icon-correcting wrapper onto every tests tree. The view may
     * replace its renderer when the model is attached, so this runs again on
     * every recount — a second wrap is a no-op.
     */
    private fun installHiddenIgnoredRenderer() {
        for (tree in testTrees) {
            val current = tree.cellRenderer ?: continue
            if (current !is LuaUnitHiddenIgnoredRenderer) {
                tree.cellRenderer = LuaUnitHiddenIgnoredRenderer(current, properties)
                tree.repaint()
            }
        }
    }

    /**
     * Recounts the groups above [node] (its suite table, its file and its
     * section): a new failed or ignored case changes their counters, and the
     * run itself shows its progress through them.
     */
    private fun refreshCountsUp(node: SMTestProxy?) {
        val chain = ArrayList<SMTestProxy>()
        var current = node
        while (current != null) {
            if (groupLabels.containsKey(current)) {
                chain.add(current)
            }
            current = current.parent
        }
        if (chain.isEmpty()) {
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            for (proxy in chain) {
                val label = groupLabels[proxy] ?: continue
                proxy.setPresentableName(groupName(label, proxy))
            }
        }
    }

    fun createView(): SMTRunnerConsoleView {
        // The SM overload: it returns the SM view itself (the TestConsoleProperties
        // overload wraps it into a Base view), and the SM view is the one whose
        // tree double-click goes through the test locator.
        consoleView = SMTestRunnerConnectionUtil.createConsole(properties)
        // The `file:line` references of a case's output become clickable here:
        // the SM view prints a node's output through the console, and the
        // filter turns them into hyperlinks on their way.
        consoleView.addMessageFilter(LuaUnitConsoleFilter(configuration.project))
        // The platform reports the root proxy on start; the tree is built then.
        val bus = configuration.project.messageBus.connect(consoleView)
        bus.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(rootTestProxy: SMTestProxy.SMRootTestProxy) {
                buildTree(rootTestProxy)
            }

            override fun onTestingFinished(rootTestProxy: SMTestProxy.SMRootTestProxy) {
                // The form redraws the status line here (its own subscriber
                // runs first, ours is registered later): writing right after it
                // keeps our wording the last word on the line. The summary of
                // the last suite alone is written from onSuiteFinished.
                showStatusLine()
            }
        })
        // The "(N)" of the groups follows the view mode: the counters are
        // rewritten when the user flips "hide passed" or "hide ignored".
        properties.addListener(
            com.intellij.execution.testframework.TestConsoleProperties.HIDE_PASSED_TESTS
        ) { updateGroupCounts() }
        properties.addListener(
            com.intellij.execution.testframework.TestConsoleProperties.HIDE_IGNORED_TEST
        ) { updateGroupCounts() }
        // Cmd+C in the tests tree copies the node's shown name — the Java
        // way. The view may hold MORE THAN ONE tests tree (a debug tab keeps
        // its own), and the handler must sit on each of them: installed on
        // the first one only, the click in the other served a drag location
        // or nothing at all. The proxy of a path comes from the tree's own
        // API — a tree node is NOT the proxy itself.
        findTestTrees(consoleView.component, testTrees)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("copy handler installed on ${testTrees.size} test tree(s)")
        for (tree in testTrees) {
            tree.transferHandler = object : javax.swing.TransferHandler() {
                override fun createTransferable(component: javax.swing.JComponent): java.awt.datatransfer.Transferable? {
                    val paths = tree.selectionPaths ?: return null
                    val names = paths.mapNotNull { path -> tree.getSelectedTest(path)?.let(::copyableName) }
                        .filter { it.isNotEmpty() }
                    com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                        .info("copy: paths=${paths.size} names=${names.size} text='${names.joinToString("\\n").takeLast(120)}'")
                    if (names.isEmpty()) {
                        return null
                    }
                    return java.awt.datatransfer.StringSelection(names.joinToString("\n"))
                }

                override fun getSourceActions(component: javax.swing.JComponent): Int = COPY
            }
            // The copy action gets the platform's native shortcut (Cmd+C on
            // macOS, Ctrl+C elsewhere) RIGHT ON THE TREE: a shortcut
            // registered on the component wins over the global $Copy while
            // the tree has the focus, and the menu shows it by the item.
            val copyAction = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                .getAction("LuaUnit.CopyTestName")
            if (copyAction != null) {
                val mask = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMask
                copyAction.registerCustomShortcutSet(
                    com.intellij.openapi.actionSystem.CustomShortcutSet(
                        com.intellij.openapi.actionSystem.KeyboardShortcut(
                            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, mask), null
                        )
                    ),
                    tree
                )
            }
        }
        return consoleView
    }

    /**
     * What Cmd+C copies: a suite (file) node copies the file's path from the
     * project root, as a failure message names it and with no line suffix;
     * a case keeps everything it shows (its description included), with no
     * "test_" prefix — the shown name is already without one; a group's name
     * carries the live "(N passed, ...)" counter — it is dropped.
     */
    private fun copyableName(proxy: SMTestProxy): String {
        suiteNodePaths[proxy]?.let { return it }
        val shown = proxy.presentableName ?: proxy.name ?: return ""
        return if (proxy.isLeaf) shown else shown.replace(Regex("""\s+\([^)]*\)$"""), "")
    }

    /** Every tests tree of the view, anywhere among its components. */
    private fun findTestTrees(
        component: java.awt.Component?,
        found: MutableList<com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView>
    ) {
        if (component is com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView) {
            found.add(component)
            return
        }
        if (component is java.awt.Container) {
            for (child in component.components) {
                findTestTrees(child, found)
            }
        }
    }

    /**
     * The "Rerun Failed Tests" action for the run's tab. The platform puts the
     * actions of an ExecutionResult into the tab's toolbar — this is the way
     * a JVM runner's rerun-failed button gets there.
     */
    fun rerunFailedAction(): com.intellij.openapi.actionSystem.AnAction =
        LuaUnitRerunFailedTestsAction(consoleView, this).also {
            it.init(properties)
            // Without a model the button never enables: the results viewer is
            // the model of the finished tree (it knows the failed leaves).
            it.setModelProvider { consoleView.resultsViewer }
        }

    /**
     * The run was stopped: the suite processes are destroyed, no case result
     * will ever arrive, and the tree would keep its nodes spinning and the
     * root at "Running tests..." forever (the Stop button is already grey
     * by then). Close everything that is still open.
     */
    fun onStopped() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (root == null) {
                return@invokeLater
            }
            // The run was stopped: the marks stay hidden no longer. The index
            // was not touched, so the previous run's results come back.
            LuaUnitHighlights.finish(configuration.project)
            // A case that has not reported its end never will: mark it not
            // run, the way the end of the whole run does.
            for (proxy in caseProxies.values) {
                if (!proxy.isDefect && !proxy.isIgnored && !finishedCases.contains(proxy)) {
                    proxy.setTestIgnored("not run", "")
                }
            }
            // Close the files and the sections: an open group node is
            // painted as failed by the platform. setFinished on an already
            // finished node is what the normal end of a suite does too.
            for (node in groupLabels.keys) {
                node.setFinished()
            }
            root?.setFinished()
            writeStatusLine(final = true)
        }
    }

    /** The summary wording and the color on the status line, on the EDT. */
    private fun showStatusLine() {
        // One write right away: the platform's final repaints of the line
        // happen around the process termination, and a long double-deferred
        // write left the line dark for a visible moment in between.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            writeStatusLine(final = true)
        }
        // Two short-delay rewrites follow: whatever late repaint the platform
        // schedules (the tab's "Terminated" pass among them), our wording is
        // back within a blink, not after a dark second.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            // The tab may be closed before the rewrite runs: an Alarm on a
            // disposed console is an error the platform logs as SEVERE.
            if (com.intellij.openapi.util.Disposer.isDisposed(consoleView)) {
                return@invokeLater
            }
            com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, consoleView)
                .addRequest({ writeStatusLine(final = true) }, 150)
            com.intellij.util.Alarm(com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, consoleView)
                .addRequest({ writeStatusLine(final = true) }, 600)
        }
    }

    /** The live counters on the line while the run goes on. */
    private fun updateStatusLineLive() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            writeStatusLine(final = false)
        }
    }

    /** The one place that writes the wording, the colors and the icon. */
    private fun writeStatusLine(final: Boolean) {
        if (!::consoleView.isInitialized) {
            return
        }
        var passed = 0
        var failed = 0
        var ignoredCount = 0
        var running = 0
        var millis = 0L
        for (proxy in caseProxies.values) {
            when {
                proxy.isIgnored -> ignoredCount++
                proxy.isDefect -> failed++
                finishedCases.contains(proxy) -> passed++
                else -> running++
            }
            proxy.duration?.let { millis += it }
        }
        val line = consoleView.resultsViewer?.statusLine
        if (line == null) {
            return
        }
        if (final) {
            // Diagnostic: the exact state of every node when the verdict is
            // written, to see where a verdict does not match the events.
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
                caseProxies.values.joinToString(
                    prefix = "status nodes: ", separator = " | "
                ) { proxy ->
                    val state = when {
                        proxy.isIgnored -> "ignored"
                        proxy.isDefect -> "defect"
                        finishedCases.contains(proxy) -> "passed"
                        else -> "running"
                    }
                    "${proxy.name.takeLast(40)}=$state"
                }
            )
        }
        val summary = summaryLine(passed, failed, ignoredCount, millis)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("status line: '$summary' running=$running final=$final line=true")
        if (!final) {
            // While the run goes on there is no verdict yet: no icon, no
            // status — only the live counters, the way a JUnit line ticks.
            writeStatusPieces(line, passed, failed, ignoredCount, running, null, null)
            return
        }
        writeStatusPieces(
            line, passed, failed, ignoredCount, running,
            if (failed > 0) "failed" else "passed",
            if (failed > 0) {
                com.intellij.icons.AllIcons.RunConfigurations.TestState.Red2
            } else {
                com.intellij.icons.AllIcons.RunConfigurations.TestState.Green2
            }
        )
    }

    /** The colored pieces of the line, the way the platform paints its own. */
    private fun writeStatusPieces(
        line: com.intellij.execution.testframework.ui.TestStatusLine,
        passed: Int,
        failed: Int,
        ignored: Int,
        running: Int,
        status: String?,
        icon: javax.swing.Icon?
    ) {
        val state = statusStateOf(line)
        if (state == null) {
            line.setText(summaryLine(passed, failed, ignored, 0))
            if (status != null) {
                line.setStatus(status)
                line.onTestsDone { icon }
            }
            return
        }
        val failedAttrs = com.intellij.ui.SimpleTextAttributes(
            com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
            com.intellij.util.ui.JBUI.CurrentTheme.Label.errorForeground()
        )
        val runningAttrs = com.intellij.ui.SimpleTextAttributes(
            com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
            com.intellij.util.ui.JBUI.CurrentTheme.Label.warningForeground()
        )
        val passedAttrs = com.intellij.ui.SimpleTextAttributes(
            com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN,
            // There is no success color in the theme labels; this green reads
            // well on both the light and the dark one.
            com.intellij.ui.JBColor(java.awt.Color(0x1E, 0x8C, 0x3C), java.awt.Color(0x49, 0x9C, 0x54))
        )
        val grayed = com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES
        state.clear()
        icon?.let { state.setIcon(it) }
        var wrote = false
        if (failed > 0) {
            state.append("${plural(failed, "test")} failed", failedAttrs)
            wrote = true
        }
        if (passed > 0) {
            if (wrote) state.append(", ", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
            state.append("$passed passed", passedAttrs)
            wrote = true
        }
        if (ignored > 0) {
            if (wrote) state.append(", ", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
            // The ignored cases did not run — a secondary fact, gray like the
            // totals, not a warning color.
            state.append("$ignored ignored", grayed)
            wrote = true
        }
        if (!wrote) {
            state.append("no tests", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        if (status != null) {
            state.append(
                "  ${plural(passed + failed + ignored, "test")} total",
                grayed
            )
            line.setStatus(status)
        }
    }

    /**
     * The component the wording of the line is painted on. It is protected in
     * the platform class — the only colored write into the line is through
     * it, so the field is taken reflectively and cached; on any failure the
     * plain setText is the fallback.
     */
    @Volatile
    private var cachedStatusState: com.intellij.ui.SimpleColoredComponent? = null

    @Volatile
    private var statusStateLookupDone = false

    private fun statusStateOf(line: com.intellij.execution.testframework.ui.TestStatusLine):
            com.intellij.ui.SimpleColoredComponent? {
        if (statusStateLookupDone) {
            return cachedStatusState
        }
        val state = try {
            val field = line.javaClass.getDeclaredField("myState")
            field.isAccessible = true
            field.get(line) as? com.intellij.ui.SimpleColoredComponent
        } catch (e: Exception) {
            null
        }
        cachedStatusState = state
        statusStateLookupDone = true
        return state
    }

    /**
     * Starts every suite and returns their processes. The suites run in
     * parallel: they are independent Lua processes (one per test file).
     *
     * <p>Under Debug the suites run one at a time — there is a single mobdebug
     * port — and each Lua process is created only when its turn comes:
     * `OSProcessHandler` starts the process in its constructor, so creating
     * them all up front would run the whole suite in parallel anyway (a crowd
     * of lua processes, none of them debugged yet). The stop button breaks the
     * chain: a destroyed process must not start the next one.
     */
    fun start(
        suites: List<LuaUnitSuite>,
        processGroup: LuaUnitProcessGroupHandler? = null
    ): List<ProcessHandler> {
        // The run begins: the marks of the previous one must not be read as
        // this run's results while it goes.
        LuaUnitHighlights.start(configuration.project)
        if (debugPort != null) {
            val handlers = ArrayList<ProcessHandler>(suites.size)
            fun startNext(index: Int) {
                if (index >= suites.size) {
                    return
                }
                val suite = suites[index]
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("debug chain: starting suite ${suite.key} (${index + 1}/${suites.size})")
                val handler = LuaUnitSuiteProcessHandler(
                    suite, this,
                    LuaUnitRunnerScript.commandLine(
                        configuration, suite.key, suite.pattern,
                        debugPort = debugPort, caseNames = suite.caseNames
                    )
                )
                processGroup?.track(handler)
                handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                    override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                        // The platform fires processWillTerminate(true) for a
                        // process that ends on its own just as well, so "was it
                        // stopped?" cannot be read from that flag — the
                        // group's own stopping state is the only reliable
                        // "the user pressed stop" signal.
                        if (processGroup?.isStopping != true) {
                            startNext(index + 1)
                        }
                    }
                })
                handlers.add(handler)
                handler.startNotify()
            }
            startNext(0)
            return handlers
        }
        val handlers = suites.map { suite ->
            LuaUnitSuiteProcessHandler(suite, this, LuaUnitRunnerScript.commandLine(
                configuration,
                suite.key,
                suite.pattern,
                debugPort = debugPort,
                caseNames = suite.caseNames
            ))
        }
        // The stop button goes through the group (it destroys every tracked
        // process); an own "kill the others" listener here is not only
        // redundant — it misfires: the platform fires processWillTerminate(true)
        // for a process that ends on its own as well, so a first suite that
        // finishes normally used to kill every still-running one.
        handlers.forEach { it.startNotify() }
        return handlers
    }

    // ---------------------------------------------------------------- tree

    private fun buildTree(rootProxy: SMTestProxy.SMRootTestProxy) = synchronized(lock) {
        if (root != null) {
            return
        }
        root = rootProxy
        rootProxy.setTreeBuildBeforeStart()
        // The SM runner names the root by its state ("Running tests", then
        // "Terminated"); a fixed name keeps the header calm while the run goes.
        rootProxy.setPresentableName("Lua tests")
        // The nodes are built from `suites`, not from the discovered tree: a
        // one-case run filters its cases there, and the tree must show exactly
        // what runs.
        class Group(val title: String?, val suites: MutableList<LuaUnitSuite> = ArrayList())
        val groups = LinkedHashMap<String?, Group>()
        for (suite in suites) {
            groups.getOrPut(suite.section) { Group(suite.section) }.suites.add(suite)
        }
        for (group in groups.values) {
            // The suites are filled with their cases BEFORE they are added to
            // the section (and the section — to the root): SMTestProxy caches
            // "is this suite empty" on the first question, and a node asked
            // while it still has no children stays "empty" forever — a dot
            // icon on a fully green group.
            val sectionChildren = ArrayList<SMTestProxy>()
            for (suite in group.suites) {
                // No location URL on a file node that has cases: a double-click
                // on it toggles the node, the way the user expects it to. A
                // file without a single case (a plain script the runner
                // executes on load) has nothing to toggle: the click opens the
                // file at its first line instead.
                val suiteUrl = if (suite.cases.isEmpty()) {
                    "file:///" + absolutePathOf(suite.file).trimStart('/') + ":1"
                } else {
                    null
                }
                val suiteNode = SMTestProxy(suite.displayName, true, suiteUrl)
                suiteNode.setLocator(LuaUnitTestLocator())
                // The node shows the file's own name with its counter: the
                // section already says which directory it lives in.
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("tree suite=${suite.key.takeLast(45)} cases=${suite.cases.size}")
                groupLabels[suiteNode] = suite.file.fileName.toString()
                // Cmd+C on the node copies the file's path from the project
                // root (the display name already is one), never a drag URL
                // with a ":1" line suffix.
                suiteNodePaths[suiteNode] = suite.displayName
                suiteById(suite.displayName)?.proxy = suiteNode
                // The suite tables of the whole FILE decide whether the cases
                // are grouped: a run of one table of a two-table file still
                // shows that table as a group. Only a file with a single table
                // keeps its cases directly on the file node. The groups follow
                // the file: the line of a table's first test, the way a reader
                // meets them (a table declared above, but with its tests
                // below another one's, comes second — the tests are what the
                // reader looks for).
                val discovered = discoveredCases(suite)
                val owners = LinkedHashSet<String>()
                val firstTestLine = HashMap<String, Int>()
                for (case in discovered) {
                    val owner = ownerOf(case.name)
                    if (owner.isEmpty()) {
                        continue
                    }
                    owners.add(owner)
                    val line = case.source?.line ?: continue
                    val known = firstTestLine[owner]
                    if (known == null || line < known) {
                        firstTestLine[owner] = line
                    }
                }
                val grouped = owners.size > 1
                val byOwner = LinkedHashMap<String, MutableList<LuaUnitNode.Case>>()
                for (case in suite.cases) {
                    byOwner.getOrPut(ownerOf(case.name)) { ArrayList() }.add(case)
                }
                // A plain global test belongs to no table: it stays on the file.
                for (case in byOwner[""].orEmpty()) {
                    suiteNode.addChild(caseProxyOf(suite, case))
                }
                val ordered = owners.filter { byOwner.containsKey(it) }.sortedWith(
                    compareBy({ firstTestLine[it] ?: Int.MAX_VALUE }, { it })
                )
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("tree groups ${suite.displayName}: owners=$owners lines=$firstTestLine cases=${byOwner.keys}")
                for (owner in ordered) {
                    val ownerCases = byOwner[owner] ?: continue
                    val ownerNode: SMTestProxy? = if (grouped) {
                        SMTestProxy(owner, true, null).also {
                            it.setLocator(LuaUnitTestLocator())
                            groupLabels[it] = owner
                            classProxies.add(it)
                            // Started right away, like a section: the group
                            // reports progress instead of a red cross of
                            // "never finished" while the run goes.
                            it.setSuiteStarted()
                        }
                    } else {
                        null
                    }
                    // The children go in before their group is added to the
                    // file: SMTestProxy caches "is this suite empty" on the
                    // first question (see the section loop below).
                    for (case in ownerCases) {
                        (ownerNode ?: suiteNode).addChild(caseProxyOf(suite, case))
                    }
                    if (ownerNode != null) {
                        suiteNode.addChild(ownerNode)
                    }
                }
                sectionChildren.add(suiteNode)
            }
            val sectionProxy = if (group.title.isNullOrEmpty()) rootProxy else {
                SMTestProxy(group.title, true, null).also {
                    groupLabels[it] = group.title
                    sectionChildren.forEach { child -> it.addChild(child) }
                    rootProxy.addChild(it)
                    sectionProxies.add(it)
                    // Start it right away so the group reports progress, not a
                    // red cross of "never finished".
                    it.setSuiteStarted()
                }
            }
            if (sectionProxy === rootProxy) {
                sectionChildren.forEach { rootProxy.addChild(it) }
            }
        }
        // Replay the events that arrived before the tree was built, in the
        // order they arrived: a case's output goes under its "PASSED" line
        // only then. (The states, the suite finishes and the output used to
        // sit in three buffers replayed states-first, which put the marker
        // above the output of the very first cases of a run.)
        val replay = ArrayList(journal)
        journal.clear()
        for (event in replay) {
            event()
        }
        // The names of the groups (with their counters) are set once every
        // child is in place; deferred to the EDT like every other tree edit.
        updateGroupCounts()
    }

    /** A suite's or case's file as an absolute path: relative files (the
      * manifest's entries, a discovery source) resolve from the project root,
      * the only base they are relative to. */
    private fun absolutePathOf(file: java.nio.file.Path): String {
        val text = file.toString()
        if (java.io.File(text).isAbsolute || file.isAbsolute) {
            return text
        }
        val base = configuration.project.basePath ?: return text
        return "$base/$text"
    }

    private fun suiteById(name: String): LuaUnitSuite? = suites.firstOrNull { it.key == name }

    /** The suite table a case is declared on: "ABCGroup" for
      * "ABCGroup.test_x", an empty string for a plain global test. */
    private fun ownerOf(caseName: String): String = caseName.substringBeforeLast('.', "")

    /** Every case the discovery found for [suite]'s file, not the run's subset. */
    private fun discoveredCases(suite: LuaUnitSuite): List<LuaUnitNode.Case> {
        for (section in tree.sections) {
            for (known in section.suites) {
                if (sameFile(known.file, suite.file)) {
                    return known.cases
                }
            }
        }
        return suite.cases
    }

    private fun sameFile(a: java.nio.file.Path, b: java.nio.file.Path): Boolean =
        try {
            a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
        } catch (e: Exception) {
            a == b
        }

    /**
     * The tree node of one discovered case: named by the full LuaUnit name (the
     * SM runner matches the events by it), linked to its declaration, its copy
     * name and its ignored state.
     */
    private fun caseProxyOf(suite: LuaUnitSuite, case: LuaUnitNode.Case): SMTestProxy {
        val source = case.source
        val locationUrl = when {
            source != null -> "file:///" + absolutePathOf(source.file).trimStart('/') + ":${source.line}"
            else -> null
        }
        // The proxy is named by the full test name: the short name is only
        // what the tree shows.
        val caseProxy = SMTestProxy(case.name, false, locationUrl)
        // Without a locator the proxy answers "no location" and a double-click
        // on the node opens nothing.
        caseProxy.setLocator(LuaUnitTestLocator())
        caseProxy.setPresentableName(
            if (case.description.isBlank()) case.displayName
            else "${case.displayName} (${case.description})"
        )
        LuaUnitCopyTestNameAction.remember(caseProxy, case)
        if (case.ignored) {
            // A commented-out case: no run. The tree paints it as ignored by
            // its state; the name stays the case's own
            // ("CCR_HDAS-52 (HDAS-93: disabled)").
            caseProxy.setTestIgnored("", "")
        }
        caseProxies[key(suite.displayName, case.name)] = caseProxy
        return caseProxy
    }

    private fun key(suite: String, case: String) = "$suite\u0000$case"

    /** Prints the green "PASSED" marker of a case into the console. */
    private fun printPassed(node: SMTestProxy) {
        // Into the node's own output, green: that is what a click on the test shows.
        node.addOutput("PASSED\n", SuiteOutputKeys.PASSED)
    }

    // -------------------------------------------------------------- events

    /** Runs [block] on the built tree, or queues it for the replay in
      * [buildTree] when the tree does not exist yet. Called under [lock]. */
    private fun nowOrDefer(block: () -> Unit) {
        if (root == null) {
            journal.add(block)
        } else {
            block()
        }
    }

    fun onSuiteStarted(suite: LuaUnitSuite) = synchronized(lock) {
        nowOrDefer { onSuiteStartedNow(suite) }
    }

    private fun onSuiteStartedNow(suite: LuaUnitSuite) {
        // The debugger runs the suites one after another, and a Lua process
        // under the mobdebug hook is slow: say which one is starting and
        // where in the queue it is, so the long quiet stretches between two
        // suites are explained. The line goes to the suite's own node (shown
        // when selected) and, under Debug, to the console of the session.
        if (debugPort != null) {
            val progress = "(${finishedSuites + 1}/${suites.size})"
            onOutput(suite, null, "Suite ${suite.key}: starting $progress\n", false)
        }
        suite.proxy?.setSuiteStarted()
    }

    /** luaunit.skip("reason"): the case is ignored, not failed. */
    fun onCaseSkipped(suite: LuaUnitSuite, name: String, reason: String) = synchronized(lock) {
        nowOrDefer { onCaseSkippedNow(suite, name, reason) }
    }

    private fun onCaseSkippedNow(suite: LuaUnitSuite, name: String, reason: String) {
        val node = caseProxies[key(suite.key, name)]
        if (node != null) {
            node.setTestIgnored(reason, "")
            // The reason goes into the tree as the case's explanation.
            val shown = node.presentableName ?: name
            node.setPresentableName("$shown ($reason)")
            node.setFinished()
            refreshCountsUp(node)
        }
    }

    /**
     * The file itself failed before any case could start: it did not load (a
     * missing module, a syntax error). No case node can take such a failure,
     * and once it is dropped every case of the file stays silently "not run" —
     * the tree then reads as "everything is ignored" instead of "the file
     * failed". The file's own node turns red with the message, which also goes
     * to the console.
     */
    fun onSuiteFailure(suite: LuaUnitSuite, failure: LuaUnitProtocol.Failure) = synchronized(lock) {
        nowOrDefer { onSuiteFailureNow(suite, failure) }
    }

    private fun onSuiteFailureNow(suite: LuaUnitSuite, failure: LuaUnitProtocol.Failure) {
        // The failure belongs to the file: no case was open when it happened
        // (a missing module, a syntax error, a plain script that threw while
        // loading). The tree shows it on the file's node; the editor marks it
        // on the first line of the file — see [LuaUnitFailureInlayHints].
        rememberFileFailure(suite, failure)
        val node = suite.proxy ?: return
        // The message and the trace are the node's own state: a double-click
        // still goes to the file, not to the failing line of the runner, and
        // the console prints the state once. Printing them by hand as well put
        // a plain script's load failure into the output twice — once when the
        // event arrived and once more on the replay after the process exit.
        node.setTestFailed(failure.message, failure.trace, failure.isError)
        refreshCountsUp(node)
    }

    fun onCaseStarted(suite: LuaUnitSuite, name: String) {
        // Captured at the event, not at the replay: a deferred start must not
        // zero the case's duration.
        val started = System.currentTimeMillis()
        synchronized(lock) {
            nowOrDefer { onCaseStartedAt(suite, name, started) }
        }
    }

    private fun onCaseStartedAt(suite: LuaUnitSuite, name: String, started: Long) {
        caseStarts[key(suite.key, name)] = started
        val node = caseProxies[key(suite.key, name)] ?: return
        node.setStarted()
    }

    fun onCaseFinished(
        suite: LuaUnitSuite,
        name: String,
        failure: LuaUnitProtocol.Failure?,
        fixtures: List<String> = emptyList()
    ) = synchronized(lock) {
        nowOrDefer { onCaseFinishedNow(suite, name, failure, fixtures) }
    }

    private fun onCaseFinishedNow(
        suite: LuaUnitSuite,
        name: String,
        failure: LuaUnitProtocol.Failure?,
        fixtures: List<String>
    ) {
        val node = caseProxies[key(suite.key, name)]
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("onCaseFinished name=$name node=${node != null} failure=${failure != null}")
        if (node == null) {
            // The case is not of this run's tree (it was filtered, say): no
            // node will ever take it.
            return
        }
        applyCaseFinished(node, suite, name, failure, fixtures)
    }

    /** Puts a finished case onto its node: the red/green state and the output. */
    private fun applyCaseFinished(
        node: SMTestProxy,
        suite: LuaUnitSuite,
        name: String,
        failure: LuaUnitProtocol.Failure?,
        fixtures: List<String>
    ) {
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
            "applyCaseFinished name=${name.takeLast(45)} suite=${suite.key.takeLast(45)} " +
                "failure=${failure?.message?.take(60)} defectBefore=${node.isDefect}"
        )
        if (failure != null) {
            // Fixtures are not printed into the case's output any more: the
            // list duplicated the fixtures the editor already shows (code
            // vision above the case, the fixture's own panel) and cluttered
            // the trace. The record still reaches the run index below, so the
            // editor integrations keep working. To bring the block back,
            // uncomment the three lines and restore the `trace` expression.
            // val fixturesText = SuiteOutputKeys.fixturesBlock(fixtures)
            // // The trace of a failure does not end with a newline of its own:
            // // the fixtures start on a line of their own.
            // val trace = if (fixturesText.isEmpty()) failure.trace
            // else (failure.trace?.let { it + (if (it.endsWith("\n")) "" else "\n") } ?: "") + fixturesText
            val trace = failure.trace
            // The message and the trace go to the node's failed state, not to
            // the raw output: the state prints them in that order (message,
            // then the trace), while a hand-printed trace could land before
            // the message. The tree node still opens the case's declaration —
            // see [LuaUnitConsoleProperties.getErrorNavigatable].
            if (failure.expected != null && failure.actual != null) {
                // The platform's comparison panel with its "<Click to see
                // difference>" link: (message, stackTrace, actual, expected).
                // The message is cut to its first line: a table comparison
                // carries two whole JSON documents, the panel shows the diff
                // anyway, and the platform refuses a test state of that size.
                // The comparison state prints the message, the diff and the
                // traceback it was given: printing the trace here as well put
                // it twice into the case's output.
                // The comparison state prints the message, the diff and then
                // the traceback it was given: handing the trace over keeps that
                // order. Printing it separately put it before the message.
                node.setTestComparisonFailed(shortMessage(failure.message), trace, failure.actual, failure.expected)
            } else {
                node.setTestFailed(failure.message, trace, failure.isError)
            }
            // A group (the suite, the section, the root) does not turn red by
            // itself: SMTestProxy does not propagate a child's defect upwards,
            // and a group's icon follows its own state. Mark every ancestor,
            // the way the platform runners' trees do.
            var ancestor = node.parent
            while (ancestor != null) {
                if (!ancestor.isDefect) {
                    ancestor.setTestFailed("Contains failed tests", null, false)
                }
                ancestor = ancestor.parent
            }
            failures.add(LuaUnitFailure(suite.displayName, failure.name))
        } else {
            // A green case prints neither its "PASSED" line nor its fixtures
            // any more (the fixtures of a failure are dropped the same way —
            // see the branch above): the console keeps the failures only, the
            // results are in the tree. Uncomment the lines to bring the output
            // back.
            // printPassed(node)
            // val fixturesText = SuiteOutputKeys.fixturesBlock(fixtures)
            // if (fixturesText.isNotEmpty()) {
            //     printTo(node, fixturesText, false)
            // }
        }
        node.setFinished()
        finishedCases.add(node)
        // The suite's and the section's counters move with every finished case.
        refreshCountsUp(node)
        // The status line ticks with the run too, the way a JUnit line does.
        updateStatusLineLive()
        // The SM proxy does not derive a case's duration from its start and
        // end times: without an explicit value the summary would always read
        // "0 ms", so the wall time between the CASE marker and the result is
        // what the node (and, summed by the parents, the whole tree) shows.
        caseStarts.remove(key(suite.key, name))?.let { started ->
            node.setDuration(System.currentTimeMillis() - started)
        }
        // The editor integrations (code vision, the fixture panel, the project
        // view) read this map after the run; it is filled per case so a partial
        // run keeps the records of the cases it did not touch.
        rememberCaseResult(suite, name, failure, fixtures)
    }

    /** The file a fixture reference of the protocol points at, in canonical form. */
    private fun resolveFixture(reference: String): String? {
        // The runner appends the line it was read at ("path:1"); a fixture is
        // a whole file, so the suffix goes away.
        val path = reference.replace(Regex(""":\d+$"""), "").trim()
        if (path.isEmpty()) {
            return null
        }
        val file = java.io.File(path)
        val resolved = if (file.isAbsolute) {
            file
        } else {
            // The case opens its files from the working directory of the
            // configuration, which is not always the project root.
            val base = configuration.workingDirOrDefault()
                ?: configuration.project.basePath
            base?.let { java.io.File(it, path) } ?: file
        }
        if (resolved.exists()) {
            return LuaUnitRunIndex.canonical(resolved.absolutePath)
        }
        return LuaUnitTestLocator().findFile(configuration.project, path)
            ?.let { LuaUnitRunIndex.canonical(it.path) }
    }

    /** One case of this run: its declaration line, result and fixtures. */
    private fun rememberCaseResult(
        suite: LuaUnitSuite,
        name: String,
        failure: LuaUnitProtocol.Failure?,
        fixtures: List<String>
    ) {
        // A file may keep an old copy of a test under `--[[ ... ]]` next to the
        // one that really runs; both carry the same LuaUnit name. The run's
        // case is the live one — a commented-out twin is never executed, so its
        // line must not get the result, the fixtures or the failure mark.
        val case = suite.cases.firstOrNull {
            !it.ignored && (it.name == name || it.displayName == name)
        } ?: suite.cases.firstOrNull { it.name == name || it.displayName == name }
        val testFile = LuaUnitRunIndex.canonical(absolutePathOf(suite.file))
        val result = runResults.getOrPut(key(testFile, name)) { LuaUnitRunIndex.CaseResult() }
        result.testFile = testFile
        result.caseName = name
        result.displayName = case?.displayName ?: name
        result.line = case?.source?.line ?: 1
        // A generated case is declared by the row of a data module, not by the
        // suite that runs it: the click must open the table the row is in.
        result.declarationFile = case?.source?.file
            ?.let { LuaUnitRunIndex.canonical(absolutePathOf(it)) }
            ?: testFile
        result.declarationLine = case?.source?.line ?: 1
        result.generated = case?.generated == true
        result.failed = failure != null
        result.comparison = failure?.expected != null && failure?.actual != null
        // The fixtures are exactly what this run saw. A record of a previous
        // run must not carry over: a case that fails before it reads a file,
        // or a file taken out of the test's own reading, would keep a stale
        // fixture visible in its block. The list of one case lives in memory
        // per case (see [runResults]) and replaces the previous one whole.
        result.fixtures = fixtures.mapNotNull { resolveFixture(it) }
            .distinct().toMutableList()
        // The failing line of the trace is the assertion the test wrote, not
        // the declaration of the case: the editor marks that line and shows
        // the message there.
        result.errorFile = ""
        result.errorLine = 0
        result.errorText = ""
        result.message = ""
        result.expected = null
        result.actual = null
        if (failure != null) {
            // The record is stored in the workspace file: a table comparison
            // carries two whole JSON documents, and a state that big takes
            // long to save (and cannot even be written to the test history).
            // The editor only needs the beginning of each value.
            result.message = limit(failure.message)
            result.expected = failure.expected?.let { limit(it) }
            result.actual = failure.actual?.let { limit(it) }
            failureSite(failure, result.declarationFile)?.let { (file, line) ->
                result.errorFile = file
                result.errorLine = line
                result.errorText = lineTextOf(file, line)
            } ?: run {
                // The trace had no frame of the project (a plain `error()` with
                // no file, say): the declaration is the nearest place to mark.
                result.errorFile = result.declarationFile
                result.errorLine = result.declarationLine
            }
        }
    }

    /** The first line of a failure message, trimmed: the diff panel shows the rest. */
    private fun shortMessage(message: String): String =
        message.lineSequence().firstOrNull()?.trim().orEmpty()

    /**
     * The files the setUp of [className] opened: a record of its own. The block
     * above `function <className>:setUp()` shows them, and a fixture's header
     * links to that method — the setup is not the case's own input.
     */
    fun onSetUpFixtures(
        suite: LuaUnitSuite,
        className: String,
        fixtures: List<String>
    ) = synchronized(lock) {
        nowOrDefer { onSetUpFixturesNow(suite, className, fixtures) }
    }

    private fun onSetUpFixturesNow(suite: LuaUnitSuite, className: String, fixtures: List<String>) {
        if (fixtures.isEmpty()) {
            return
        }
        val line = setUpLine(suite, className) ?: return
        val testFile = LuaUnitRunIndex.canonical(absolutePathOf(suite.file))
        val result = runResults.getOrPut(key(testFile, "$className.setUp")) {
            LuaUnitRunIndex.CaseResult()
        }
        result.testFile = testFile
        result.caseName = "$className.setUp"
        result.displayName = "setUp"
        result.declarationFile = testFile
        result.declarationLine = line
        result.line = line
        result.setup = true
        result.generated = false
        result.failed = false
        result.comparison = false
        result.fixtures = fixtures.mapNotNull { resolveFixture(it) }.distinct().toMutableList()
    }

    /**
     * The record of the file itself: what it read while loading, and whether
     * it finished at all. A plain script — a file without a single test
     * function — runs entirely on load, and its fixtures and its result used
     * to be dropped: no case ever reported for it.
     *
     * <p>[completed] is the runner's own DONE marker: a file that was stopped
     * before it reported its end gets no green record (a colour would claim a
     * result that never came), unless its load fixtures are worth showing.
     */
    fun onFileFinished(
        suite: LuaUnitSuite,
        fixtures: List<String>,
        completed: Boolean
    ) = synchronized(lock) {
        nowOrDefer { rememberFileFinished(suite, fixtures, completed) }
    }

    private fun rememberFileFinished(suite: LuaUnitSuite, fixtures: List<String>, completed: Boolean) {
        // A suite with cases is coloured by its cases; the file record is only
        // for the things that have no case — the load fixtures of any file and
        // the result of a plain script.
        val worth = fixtures.isNotEmpty() || (completed && suite.cases.isEmpty())
        if (!worth) {
            return
        }
        val result = fileRecord(suite)
        result.fixtures = fixtures.mapNotNull { resolveFixture(it) }.distinct().toMutableList()
    }

    /**
     * The record of a file that failed before any case could start. It keeps
     * the message, the comparison (when the failure carried one) and the first
     * line as the place the editor marks.
     */
    private fun rememberFileFailure(suite: LuaUnitSuite, failure: LuaUnitProtocol.Failure) {
        val result = fileRecord(suite)
        result.failed = true
        result.comparison = failure.expected != null && failure.actual != null
        result.message = limit(failure.message)
        result.expected = failure.expected?.let { limit(it) }
        result.actual = failure.actual?.let { limit(it) }
        // The traceback names the line of the file that threw (a plain script
        // runs on load, so there is no case to carry the place). The first
        // line is the fallback: a failure without a trace names no better
        // place than the start of the file.
        val site = failureSite(failure, result.declarationFile)
            ?: (result.declarationFile to 1)
        result.errorFile = site.first
        result.errorLine = site.second
        result.errorText = lineTextOf(site.first, site.second)
    }

    /**
     * The record that stands for the whole file: [LuaUnitRunIndex.FILE_RECORD],
     * marked as such, declared on the first line. It is created once per file
     * and then only updated — the load fixtures and a load failure arrive as
     * separate events.
     */
    private fun fileRecord(suite: LuaUnitSuite): LuaUnitRunIndex.CaseResult {
        val testFile = LuaUnitRunIndex.canonical(absolutePathOf(suite.file))
        val result = runResults.getOrPut(key(testFile, LuaUnitRunIndex.FILE_RECORD)) {
            LuaUnitRunIndex.CaseResult()
        }
        result.testFile = testFile
        result.caseName = LuaUnitRunIndex.FILE_RECORD
        if (result.displayName.isEmpty()) {
            result.displayName = suite.file.fileName.toString()
        }
        result.fileLevel = true
        result.declarationFile = testFile
        result.declarationLine = 1
        result.line = 1
        return result
    }

    /** The line of `function <className>:setUp(` in the suite's file, or null. */
    private fun setUpLine(suite: LuaUnitSuite, className: String): Int? {
        val text = LuaUnitTexts.read(suite.file) ?: return null
        val pattern = Regex(
            "(?m)^[ \\t]*function[ \\t]+" + Regex.escape(className) + "[ \\t]*[.:][ \\t]*setUp[ \\t]*\\("
        )
        val match = pattern.find(text) ?: return null
        return text.substring(0, match.range.first).count { it == '\n' } + 1
    }

    /** The text of [line] (1-based) of the file at [path], trimmed; "" when unreadable. */
    private fun lineTextOf(path: String, line: Int): String {
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return ""
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(file) ?: return ""
        if (line < 1 || line > document.lineCount) {
            return ""
        }
        return document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line - 1),
                document.getLineEndOffset(line - 1)
            )
        ).trim()
    }

    /**
     * The `file:line` of a failure trace that resolves to a real file. A test
     * often calls a helper of its own (`assertMetrics`); the failure happened
     * in that helper, but the line the user wants to see is the call of the
     * test, so a frame of such a helper is skipped when a user frame follows.
     */
    private fun failureSite(failure: LuaUnitProtocol.Failure, declarationFile: String): Pair<String, Int>? {
        val trace = failure.trace ?: return null
        val frames = ArrayList<Pair<Pair<String, Int>, String>>()
        for (line in trace.lineSequence()) {
            val match = FRAME.find(line) ?: continue
            val file = resolveFixture(match.groupValues[1]) ?: continue
            val at = match.groupValues[2].toIntOrNull() ?: continue
            frames.add((file to at) to line)
        }
        if (frames.isEmpty()) {
            return null
        }
        return (frames.firstOrNull { !HELPER_FRAME.containsMatchIn(it.second) } ?: frames.first()).first
    }

    /** Hands the run's cases to the index and repaints everything that reads it. */
    private fun publishRunResults() {
        val results = ArrayList(runResults.values)
        if (results.isEmpty()) {
            return
        }
        val project = configuration.project
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            val log = com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            val started = System.currentTimeMillis()
            LuaUnitRunIndex.getInstance(project).publish(results)
            log.info(
                "publish: ${results.size} results in ${System.currentTimeMillis() - started} ms; " +
                    results.take(6).joinToString { "${it.caseName}=${it.fixtures.size}" }
            )
            // The code vision, the inlay hints and the fixture panels are
            // cached per editor and know nothing about the index: each of them
            // is invalidated explicitly, or the marks keep the previous run's
            // state until the IDE is restarted.
            LuaUnitHighlights.finish(project)
            log.info("UI update requested in ${System.currentTimeMillis() - started} ms")
        }
    }


    fun onOutput(suite: LuaUnitSuite, caseName: String?, text: String, error: Boolean) {
        val suiteKey = suite.key
        synchronized(lock) {
            nowOrDefer { appendOutputNow(suiteKey, caseName, text, error) }
        }
        if (debug) {
            // The Java way: the Console tab of the debug session shows the
            // tests' own stdout/stderr, not only the debugger chatter. The
            // tests tree keeps its copy too (a node's own output).
            val session = com.intellij.xdebugger.XDebuggerManager
                .getInstance(configuration.project).currentSession
            session?.consoleView?.print(
                text,
                if (error) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT
            )
        }
    }

    /** The node-taking half of [onOutput]; during the replay the debug
      * session already printed the text when the event arrived. */
    private fun appendOutputNow(suiteKey: String, caseName: String?, text: String, error: Boolean) {
        val node = if (caseName != null) caseProxies[key(suiteKey, caseName)] else suiteById(suiteKey)?.proxy
        if (node != null) {
            printTo(node, text, error)
        }
    }

    /** Called when every suite of the run has finished. */
    var onAllSuitesFinished: (() -> Unit)? = null

    fun onSuiteFinished(suite: LuaUnitSuite) = synchronized(lock) {
        nowOrDefer { onSuiteFinishedNow(suite) }
    }

    private fun onSuiteFinishedNow(suite: LuaUnitSuite) {
        val proxy = suite.proxy
        if (proxy == null) {
            // Not of this run's tree: no node will ever take the finish.
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                .info("suite finish without a node: ${suite.key}")
            return
        }
        proxy.setFinished()
        // Telemetry for the "dot on a green file node" hunt: what the node
        // answers right after its finish.
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
            "suite finish state: file=${suite.key.takeLast(45)} leaf=${proxy.isLeaf} " +
                "empty=${proxy.isEmptySuite} defect=${proxy.isDefect} ignored=${proxy.isIgnored}"
        )
        finishedSuites++
        if (finishedSuites >= suites.size) {
            // A case that has not reported by now never will: LuaUnit never ran
            // it (the global _G-style tests and the like). The old runner
            // showed those as "skipped" — mark them not run, so they end up in
            // the totals instead of hanging "running" forever.
            for (proxy in caseProxies.values) {
                if (!proxy.isDefect && !proxy.isIgnored && !finishedCases.contains(proxy)) {
                    proxy.setTestIgnored("not run", "")
                }
            }
            // Every suite is done: close the sections too (an open group node
            // is painted as failed by the platform) and keep the root's name.
            for (section in sectionProxies) {
                section.setFinished()
            }
            // The suite-table groups live inside the file node and have no
            // event of their own: the end of the run closes them.
            for (group in classProxies) {
                group.setFinished()
            }
            // The totals line, the way a JUnit run ends: passed / failed /
            // ignored, each block only when it is not empty.
            var passed = 0
            var failed = 0
            var ignoredCount = 0
            var millis = 0L
            for (proxy in caseProxies.values) {
                when {
                    proxy.isIgnored -> ignoredCount++
                    proxy.isDefect -> failed++
                    else -> passed++
                }
                proxy.duration?.let { millis += it }
            }
            // The summary line, in one wording everywhere it is shown: the
            // status line under the tree and the root's title. "1 test
            // failed, 520 passed, 5 ignored  521 tests total, 512 ms". The
            // status line itself is written from onTestingFinished — after
            // the platform's own final redraw of it.
            val summary = summaryLine(passed, failed, ignoredCount, millis)
            root?.setFinished()
            val title = if (summary.isEmpty()) "Lua tests" else "Lua tests — $summary"
            root?.setPresentableName(title)
            // The SM runner renames the root when the process ends ("Terminated"):
            // set the totals once more after that.
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                root?.setPresentableName(title)
            }
            // The counters follow the final states of the cases as well.
            updateGroupCounts()
            // One line per group in the log: what the counter itself sees —
            // a group claiming "(0)" with a green case inside means the walk
            // lost the node, and this is the fastest way to see it.
            for ((proxy, label) in groupLabels) {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("group counters: $label ${countSuffix(proxy)}")
            }
            // The status line: written here and not from a bus event, for the
            // suites are plain Lua processes the platform does not parse —
            // this is the only "the run is over" moment there is.
            showStatusLine()
            // Everything the editor shows about the run is written at once,
            // when the run is really over: the case records, the code vision
            // of the tests, the panels of the fixtures and the tree colours.
            publishRunResults()
            onAllSuitesFinished?.invoke()
        }
    }

    /**
     * Appends a chunk to a node's own output. The key selects the stream, so
     * the console can colour stderr differently from stdout.
     */
    private fun printTo(node: SMTestProxy, text: String, error: Boolean) {
        node.addOutput(text, if (error) SuiteOutputKeys.STDERR else SuiteOutputKeys.STDOUT)
    }

    /**
     * The run's summary in the agreed wording:
     * `1 test failed, 520 passed, 5 ignored  521 tests total, 512 ms`.
     * A block that is zero is dropped; a run with nothing to count says so.
     */
    private fun summaryLine(passed: Int, failed: Int, ignored: Int, millis: Long): String {
        val parts = ArrayList<String>()
        if (failed > 0) parts.add("${plural(failed, "test")} failed")
        if (passed > 0) parts.add("$passed passed")
        if (ignored > 0) parts.add("$ignored ignored")
        if (parts.isEmpty()) {
            return "no tests"
        }
        val total = passed + failed + ignored
        return parts.joinToString(", ") + "  ${plural(total, "test")} total, $millis ms"
    }

    private fun plural(count: Int, word: String): String =
        "$count $word" + (if (count == 1) "" else "s")

    companion object {
        /** How much of a failure value the stored record keeps. */
        private const val VALUE_LIMIT = 4000

        private fun limit(value: String): String =
            if (value.length <= VALUE_LIMIT) value else value.take(VALUE_LIMIT) + "…"

        /** A `file:line` frame of a trace, in the shapes the console links. */
        private val FRAME = Regex("""([^\s(]+\.(?:lua|json|txt|xml|yaml|yml)(?:\.txt)?):(\d+)""")

        /**
         * A frame of a helper the test called (`in function 'assertMetrics'`):
         * the error is inside it, but the assertion is where the test called
         * it, so the frame itself is not the place to mark.
         */
        private val HELPER_FRAME = Regex("""in function '([^']*assert[^']*)'""")

        /** Green "PASSED" text of a successful case. */
        private val PASSED_CONTENT_TYPE = ConsoleViewContentType(
            "luaunit.passed",
            com.intellij.openapi.editor.markup.TextAttributes(
                java.awt.Color(0x08, 0x8B, 0x08), null, null, null,
                java.awt.Font.PLAIN
            )
        )

        /** The suites of a discovered tree, in console order. */
        fun suitesOf(configuration: LuaUnitRunConfiguration, tree: LuaUnitNode.Root): List<LuaUnitSuite> {
            val list = ArrayList<LuaUnitSuite>()
            // A one-case run shows that one case, not the whole file: the tree
            // would report every case of the file otherwise.
            val singleCase = configuration.scopeKind == LuaUnitRunConfiguration.Kind.CASE
            val wanted = configuration.scopeName
            com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
                "suitesOf kind=${configuration.scopeKind} wanted=$wanted pattern=${configuration.casePattern} " +
                    "suiteCases=${tree.sections.flatMap { it.suites }.map { it.cases.size }}"
            )
            for (section in tree.sections) {
                for (suite in section.suites) {
                    val cases = if (singleCase && wanted != null) {
                        // A one-case scope names the case itself; a suite scope
                        // (the gutter icon on `Suite = {}`) names the table its
                        // cases are declared on, so the prefix keeps them all.
                        // Both shapes filter the discovered cases — never the
                        // file's whole list.
                        suite.cases.filter {
                            it.name == wanted || it.name.startsWith("$wanted.") ||
                                it.displayName == wanted
                        }
                    } else {
                        suite.cases
                    }
                    list.add(LuaUnitSuite(
                        section.title.ifEmpty { null },
                        suite.file,
                        suite.displayName,
                        cases,
                        configuration.casePattern.takeIf { singleCase }
                    ))
                }
            }
            return list
        }
    }
}

/** The pseudo-frame a fixture is printed as: `at fixture(path:line)`. */
object SuiteOutputKeys {

    /** Keys of the two output streams of a node: the console colours them. */
    val STDOUT: Key<Unit> = Key.create("LuaUnit.stdout")
    val STDERR: Key<Unit> = Key.create("LuaUnit.stderr")

    /** The green "PASSED" line of a successful case. */
    val PASSED: Key<Unit> = Key.create("LuaUnit.passed")

    init {
        com.intellij.execution.ui.ConsoleViewContentType.registerNewConsoleViewType(
            PASSED,
            com.intellij.execution.ui.ConsoleViewContentType(
                "luaunit.passed",
                com.intellij.openapi.editor.markup.TextAttributes(
                    java.awt.Color(0x08, 0x8B, 0x08), null, null, null, java.awt.Font.PLAIN
                )
            )
        )
        // An unregistered key prints as "Unregistered" and the output of the
        // node may be dropped: map both streams onto real content types.
        com.intellij.execution.ui.ConsoleViewContentType.registerNewConsoleViewType(
            STDOUT, com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT
        )
        com.intellij.execution.ui.ConsoleViewContentType.registerNewConsoleViewType(
            STDERR, com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
        )
    }

    /**
     * The files the case opened, as a block of its own:
     *
     * ```
     * fixtures:
     *     tests/resources/...
     * ```
     *
     * <p>The paths stay `file:line` references the console filter turns into
     * links; the ":1" of a first line is dropped, it says nothing.
     */
    fun fixturesBlock(fixtures: List<String>): String {
        if (fixtures.isEmpty()) {
            return ""
        }
        return "fixtures:\n" + fixtures.joinToString("") { reference ->
            val path = reference.substringBeforeLast(':')
            val line = reference.substringAfterLast(':', "1").toIntOrNull() ?: 1
            val at = if (line > 1) "$path:$line" else path
            "\t$at\n"
        }
    }
}
