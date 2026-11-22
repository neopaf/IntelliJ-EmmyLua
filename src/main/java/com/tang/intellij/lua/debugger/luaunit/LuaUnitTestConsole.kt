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
import com.intellij.openapi.util.Key
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
    val pattern: String?
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

    /** Output that arrived before the node it belongs to existed. */
    private val pending = HashMap<String, StringBuilder>()

    private val caseProxies = ConcurrentHashMap<String, SMTestProxy>()

    @Volatile
    private var root: SMTestProxy.SMRootTestProxy? = null

    /** The group nodes (manifest sections), closed when every suite is done. */
    private val sectionProxies = ArrayList<SMTestProxy>()

    /** How many suite processes have reported their end. */
    private var finishedSuites = 0

    /**
     * True once every suite has reported its end: after that the counters
     * must not claim running cases — script-style suites report no per-case
     * events, and their nodes would stay "running" forever.
     */
    @Volatile
    private var allSuitesDone = false

    /** When each running case started, to give the node its duration. */
    private val caseStarts = ConcurrentHashMap<String, Long>()

    /** The case events that arrived before the tree existed. The suites start
      * before the platform reports the console's root, so the first cases may
      * finish before their nodes are there; the outcome is kept here and
      * replayed on the nodes once the tree is built. */
    private val pendingStates = LinkedHashMap<String, Pair<Boolean, LuaUnitProtocol.Failure?>>()

    /** Suites that finished before their node existed (script-style files are
      * that quick): the finish is replayed when the tree is built, or the
      * file node keeps its "not run" dot forever. */
    private val pendingSuiteFinishes = ArrayList<LuaUnitSuite>()

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

    /** Port of the mobdebug server under Debug, null otherwise. */
    @Volatile
    var debugPort: Int? = null

    /** The console with the summary line; set by the run state before the tree is built. */
    @Volatile
    var environment: com.intellij.execution.runners.ExecutionEnvironment? = null

    /** The group nodes (sections, suite files) with their plain label, for the counters. */
    private val groupLabels = LinkedHashMap<SMTestProxy, String>()

    /** A suite (file) node -> the file's path from the project root, what
      * Cmd+C on the node puts on the clipboard (no ":1" line suffix). */
    private val suiteNodePaths = LinkedHashMap<SMTestProxy, String>()

    /**
     * The "(3 passed, 1 failed, 2 ignored, 15 running)" of a group. A category
     * is shown when the tree shows it (the failed and the still running tests
     * have no filter of their own); a zero block is dropped. A case that has
     * not finished yet is "running" — it may still turn either way.
     */
    private fun countSuffix(node: SMTestProxy): String {
        val showPassed = !com.intellij.execution.testframework.TestConsoleProperties
            .HIDE_PASSED_TESTS.value(properties)
        val showIgnored = !com.intellij.execution.testframework.TestConsoleProperties
            .HIDE_IGNORED_TEST.value(properties)
        var passed = 0
        var failed = 0
        var ignored = 0
        var running = 0
        fun walk(current: SMTestProxy) {
            for (child in current.children) {
                if (!child.isLeaf) {
                    walk(child)
                } else when {
                    child.isIgnored -> ignored++
                    child.isDefect -> failed++
                    finishedCases.contains(child) -> passed++
                    else -> running++
                }
            }
        }
        walk(node)
        val parts = ArrayList<String>()
        if (showPassed && passed > 0) parts.add("$passed passed")
        if (failed > 0) parts.add("$failed failed")
        if (showIgnored && ignored > 0) parts.add("$ignored ignored")
        if (running > 0 && !allSuitesDone) parts.add("$running running")
        return "(${parts.joinToString(", ").ifEmpty { "0" }})"
    }

    /** Writes the counters of every group node, on the EDT. */
    private fun applyGroupCounts() {
        for ((proxy, label) in groupLabels) {
            proxy.setPresentableName("$label ${countSuffix(proxy)}")
        }
    }

    /** Recounts the groups on the view mode flip (hide passed / hide ignored). */
    private fun updateGroupCounts() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            applyGroupCounts()
        }
    }

    /**
     * Recounts the groups above [node] (its suite and its section): a new
     * failed or ignored case changes their counters, and the run itself shows
     * its progress through them.
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
                proxy.setPresentableName("$label ${countSuffix(proxy)}")
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
        val trees = ArrayList<com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView>()
        findTestTrees(consoleView.component, trees)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("copy handler installed on ${trees.size} test tree(s)")
        for (tree in trees) {
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
                        configuration, suite.key, suite.pattern, discover = false, debugPort = debugPort
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
                discover = false,
                debugPort = debugPort
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
                // No location URL on the file node: a double-click on it
                // toggles the node, the way the user expects it to.
                val suiteNode = SMTestProxy(suite.displayName, true, null)
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
                for (case in suite.cases) {
                    val source = case.source
                    val locationUrl = when {
                        source != null -> "file:///" + absolutePathOf(source.file).trimStart('/') + ":${source.line}"
                        else -> null
                    }
                    // The proxy is named by the full test name: the SM runner
                    // matches the CASE events by that name; the short name is
                    // only what the tree shows.
                    val caseProxy = SMTestProxy(case.name, false, locationUrl)
                    // Without a locator the proxy answers "no location" and a
                    // double-click on the node opens nothing.
                    caseProxy.setLocator(LuaUnitTestLocator())
                    caseProxy.setPresentableName(
                        if (case.description.isBlank()) case.displayName
                        else "${case.displayName} (${case.description})"
                    )
                    if (case.ignored) {
                        // A commented-out case: no run. The tree paints it as
                        // ignored by its state; the name stays the case's own
                        // ("CCR_HDAS-52 (HDAS-93: disabled)").
                        caseProxy.setTestIgnored("", "")
                    }
                    suiteNode.addChild(caseProxy)
                    caseProxies[key(suite.displayName, case.name)] = caseProxy
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
        // Replay the case events that arrived before the tree was built. The
        // key is "suite\0case": different suites may run cases of the same
        // name (TestSuite.test_DWR lives in two files), and a bare-name map
        // kept only the last of them — the other node never closed and its
        // group's counter stayed at zero.
        for ((id, outcome) in pendingStates) {
            val node = caseProxies[id] ?: run {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("replay miss: ${id.substringAfter('\u0000')} of ${id.substringBefore('\u0000')}")
                continue
            }
            val (started, failure) = outcome
            if (started) {
                node.setStarted()
            } else {
                val suite = suiteById(id.substringBefore('\u0000'))
                if (suite != null) {
                    applyCaseFinished(node, suite, id.substringAfter('\u0000'), failure, emptyList())
                } else {
                    node.setFinished()
                }
            }
        }
        pendingStates.clear()
        // Replay the suites that finished before the tree existed: without
        // it their file nodes keep the "not run" dot while their cases are
        // green (the script-style files finish before the root is reported).
        if (pendingSuiteFinishes.isNotEmpty()) {
            val replay = ArrayList(pendingSuiteFinishes)
            pendingSuiteFinishes.clear()
            for (suite in replay) {
                onSuiteFinished(suite)
            }
        }
        flushPending()
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

    private fun key(suite: String, case: String) = "$suite\u0000$case"

    /** Prints the green "PASSED" marker of a case into the console. */
    private fun printPassed(node: SMTestProxy) {
        // Into the node's own output, green: that is what a click on the test shows.
        node.addOutput("PASSED\n", SuiteOutputKeys.PASSED)
    }

    // -------------------------------------------------------------- events

    fun onSuiteStarted(suite: LuaUnitSuite) = synchronized(lock) {
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

    fun onCaseStarted(suite: LuaUnitSuite, name: String) = synchronized(lock) {
        caseStarts[key(suite.key, name)] = System.currentTimeMillis()
        val node = caseProxies[key(suite.key, name)]
        if (node == null) {
            pendingStates[key(suite.key, name)] = Pair(true, null)
            return
        }
        node.setStarted()
    }

    fun onCaseFinished(
        suite: LuaUnitSuite,
        name: String,
        failure: LuaUnitProtocol.Failure?,
        fixtures: List<String> = emptyList()
    ) = synchronized(lock) {
        val node = caseProxies[key(suite.key, name)]
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("onCaseFinished name=$name node=${node != null} failure=${failure != null}")
        if (node == null) {
            pendingStates[key(suite.key, name)] = Pair(false, failure)
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
            // The fixtures the case opened go to the very end of its trace;
            // fixtureFrame drops the meaningless ":1" of a first line.
            val fixturesText = fixtures.joinToString("") { SuiteOutputKeys.fixtureFrame(it) }
            val stack = (failure.trace ?: "") + fixturesText
            // The tree node must always open the case's declaration: the SM
            // console navigates a failed node by the first frame of its stack
            // trace, which is the assert inside `test_common_core.lua`, not
            // the test. The trace is printed below (with clickable links), so
            // the frames stay one click away without hijacking the node.
            if (failure.expected != null && failure.actual != null) {
                // The platform's comparison panel with its "<Click to see
                // difference>" link: (message, stackTrace, actual, expected).
                node.setTestComparisonFailed(failure.message, null, failure.actual, failure.expected)
            } else {
                node.setTestFailed(failure.message, null, failure.isError)
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
            // The message itself is already in the node's output (the failed
            // state prints it); what it lacks is the stack trace, so only the
            // trace is added here, once.
            printTo(node, (failure.trace ?: "") + fixturesText + "\n", failure.isError)
        } else {
            // A green case prints its status, then the files it opened. The
            // green word goes to the console; the fixtures are printed into the
            // case's own output, under it.
            printPassed(node)
            for (fixture in fixtures) {
                printTo(node, SuiteOutputKeys.fixtureFrame(fixture), false)
            }
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
    }


    fun onOutput(suite: LuaUnitSuite, caseName: String?, text: String, error: Boolean) {
        synchronized(lock) {
            val node = if (caseName != null) caseProxies[key(suite.key, caseName)] else suite.proxy
            if (node == null) {
                pending.getOrPut(suite.key + "\u0000" + (caseName ?: "")) { StringBuilder() }.append(text)
            } else {
                printTo(node, text, error)
            }
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

    /** Called when every suite of the run has finished. */
    var onAllSuitesFinished: (() -> Unit)? = null

    fun onSuiteFinished(suite: LuaUnitSuite) = synchronized(lock) {
        val proxy = suite.proxy
        if (proxy == null) {
            // The suite's process ended before the platform reported the
            // console root: there is no node to finish yet. The tree build
            // replays the finish on the node it creates.
            pendingSuiteFinishes.add(suite)
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
            // Every suite is done: the counters must not claim running cases
            // anymore — what has not reported its end never will.
            allSuitesDone = true
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

    /** Output that preceded the tree (a load-time print, say) goes to the suite. */
    private fun flushPending() {
        if (pending.isEmpty()) {
            return
        }
        for ((id, text) in pending) {
            val suiteName = id.substringBefore('\u0000')
            val caseName = id.substringAfter('\u0000').ifEmpty { null }
            val suite = suiteById(suiteName)
            val node = if (caseName != null) caseProxies[key(suiteName, caseName)] else suite?.proxy
            if (node != null) {
                printTo(node, text.toString(), false)
            }
        }
        pending.clear()
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
                        suite.cases.filter { it.name == wanted || it.displayName == wanted }
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

    fun fixtureFrame(reference: String): String {
        val path = reference.substringBeforeLast(':')
        val line = reference.substringAfterLast(':', "1").toIntOrNull() ?: 1
        // The line of a fixture is almost always the first one, and ":1"
        // says nothing — the link without it opens the file just as well.
        val at = if (line > 1) "$path:$line" else path
        return "\tat fixture($at)\n"
    }
}
