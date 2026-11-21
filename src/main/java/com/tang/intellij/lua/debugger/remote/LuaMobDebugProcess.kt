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

package com.tang.intellij.lua.debugger.remote

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Processor
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.tang.intellij.lua.debugger.IRemoteConfiguration
import com.tang.intellij.lua.debugger.LogConsoleType
import com.tang.intellij.lua.debugger.LuaDebugProcess
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.tang.intellij.lua.debugger.remote.commands.DebugCommand
import com.tang.intellij.lua.debugger.remote.commands.GetStackCommand
import com.tang.intellij.lua.psi.LuaFileUtil
import java.net.BindException

/**

 * Created by TangZX on 2016/12/30.
 */
open class LuaMobDebugProcess @JvmOverloads constructor(
    session: XDebugSession,
    private val listenPort: Int? = null
) : LuaDebugProcess(session), MobServerListener {

    private val runProfile: IRemoteConfiguration? = session.runProfile as? IRemoteConfiguration
    private val editorsProvider: LuaDebuggerEditorsProvider = LuaDebuggerEditorsProvider()
    private var mobServer: MobServer? = null
    private var mobClient: MobClient? = null
    private var baseDir: String? = null

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    override fun sessionInitialized() {
        if (session.runProfile is com.tang.intellij.lua.debugger.luaunit.LuaUnitRunConfiguration) {
            // The console of the session is the tests tree: its own filter is
            // already installed there, and the generic traceback filter would
            // highlight every "word:number" in the test data.
            session.setPauseActionSupported(true)
        } else {
            super.sessionInitialized()
        }
        try {
            val port = listenPort ?: runProfile?.port ?: 8172
            mobServer = MobServer(this)
            mobServer?.start(port)
            println("Start mobdebug server at port:$port", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
            println("Waiting for process connection...", LogConsoleType.NORMAL, ConsoleViewContentType.SYSTEM_OUTPUT)
        } catch (e:BindException) {
            val port = listenPort ?: runProfile?.port ?: 8172
            error("Failed start mobdebug server at port:$port\n${e.message}")
        } catch (e: Exception) {
            e.message?.let { error(it) }
        }
    }

    /**
     * The tests tree of a LuaUnit debug run: the session's Console tab is the
     * tree itself (with the case output under it), the way a JVM debug run
     * shows its test results — not a bare log console.
     */
    override fun createConsole(): com.intellij.execution.ui.ExecutionConsole =
        listenPort?.let { com.tang.intellij.lua.debugger.luaunit.LuaUnitDebugger.takeConsole(it) }
            ?: super.createConsole()

    override fun stop() {
        mobServer?.stop()
        // The suite Lua processes of a LuaUnit debug run are not the session's
        // own process: stopping the session has to destroy them through their
        // group, or they keep running (the tab stays "running" forever).
        listenPort?.let {
            com.tang.intellij.lua.debugger.luaunit.LuaUnitDebugger.stopProcesses(it)
        }
    }

    override fun run() {
        // No reply is awaited: RUN answers "200 OK" at once and "202 Paused"
        // only when a stop happens; a command waiting for a reply here would
        // eat the pause event and the session would hang silently.
        mobClient?.addCommand("RUN", 0)
    }

    override fun startPausing() {
        mobClient?.addCommand("SUSPEND", 0)
    }

    override fun startStepOver(context: XSuspendContext?) {
        mobClient?.addCommand("OVER", 0)
    }

    override fun startStepInto(context: XSuspendContext?) {
        mobClient?.addCommand("STEP", 0)
    }

    override fun startStepOut(context: XSuspendContext?) {
        mobClient?.addCommand("OUT", 0)
    }

    private fun sendBreakpoint(sourcePosition: XSourcePosition) {
        val file = sourcePosition.file
        // A LuaUnit run loads files by their absolute path: the short
        // variants would also match identically named files of other suites,
        // so a breakpoint would fire in the wrong file.
        if (session.runProfile is com.tang.intellij.lua.debugger.luaunit.LuaUnitRunConfiguration) {
            file.canonicalPath?.let { path ->
                warnIfNeverFires(file, sourcePosition.line + 1)
                sentBreakpoints.incrementAndGet()
                mobClient?.sendAddBreakpoint(path, sourcePosition.line + 1)
            }
            return
        }
        val fileShortUrl: String? = getShortPath(file)
        if (fileShortUrl != null) {
            LuaFileUtil.getAllAvailablePathsForMob(fileShortUrl, file).forEach{ url ->
                mobClient?.sendAddBreakpoint(url, sourcePosition.line + 1)
            }
        }
    }

    /** Lines already reported as unbreakable, so the warning is printed once per run. */
    private val warnedLines = HashSet<String>()

    /** A warning that waits for the console of the debug session to exist. */
    private class PendingWarning(val text: String, val file: VirtualFile, val line: Int)

    /** Warnings collected while the console of the session does not exist yet. */
    private val pendingWarnings = ArrayList<PendingWarning>()

    /**
     * A breakpoint on an empty line or inside a comment never fires: mobdebug
     * matches the line the interpreter is about to execute, and such a line has
     * no instruction at all. Saying so in the debug console saves a long look
     * at a debugger that "does not work".
     *
     * <p>Called from `registerBreakpoint`, which the platform runs during the
     * session init — before the session has a console. Printing right there
     * was an NPE that killed the whole debug run, so the warning waits for
     * [onConnect] instead.
     */
    private fun warnIfNeverFires(file: VirtualFile, line: Int) {
        if (!warnedLines.add("${file.path}:$line")) {
            return
        }
        val text = try {
            com.intellij.openapi.vfs.VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            return
        }
        if (text.length > 2_000_000) {
            return
        }
        val content = text.split('\n').getOrNull(line - 1)?.trim() ?: return
        val reason = when {
            content.isEmpty() -> "an empty line"
            content.startsWith("--") -> "a comment"
            else -> return
        }
        pendingWarnings.add(
            PendingWarning("Breakpoint at ${file.name}:$line is on $reason: it will never fire", file, line)
        )
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        super.registerBreakpoint(sourcePosition, breakpoint)
        sendBreakpoint(sourcePosition)
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        super.unregisterBreakpoint(sourcePosition, breakpoint)
        val file = sourcePosition.file
        // A LuaUnit run sets its breakpoints by the absolute path: the short
        // variants would not cancel them (the keys would not match), and a
        // removed breakpoint would keep firing.
        if (session.runProfile is com.tang.intellij.lua.debugger.luaunit.LuaUnitRunConfiguration) {
            file.canonicalPath?.let { path ->
                mobClient?.sendRemoveBreakpoint(path, sourcePosition.line + 1)
            }
            return
        }
        val fileShortUrl = getShortPath(file)
        LuaFileUtil.getAllAvailablePathsForMob(fileShortUrl, file).forEach{ url ->
            mobClient?.sendRemoveBreakpoint(url, sourcePosition.line + 1)
        }
    }

    override fun handleResp(client: MobClient, code: Int, data: String?) {
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("mobdebug resp: code=$code data=${data?.take(120)}")
        when (code) {
            202 -> runCommand(GetStackCommand())
        }
    }

    override fun onDisconnect(client: MobClient) {
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("mobdebug disconnected: port=$listenPort")
        mobServer?.restart()
        mobClient = null
        baseDir = null
    }

    /** How many breakpoints were sent to mobdebug in this connection. */
    private val sentBreakpoints = java.util.concurrent.atomic.AtomicInteger()

    override fun onConnect(client: MobClient) {
        mobClient = client
        sentBreakpoints.set(0)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("mobdebug connected: port=$listenPort, run=${session.runProfile?.name}")
        print("mobdebug connected\n", LogConsoleType.EMMY, ConsoleViewContentType.SYSTEM_OUTPUT)
        // The warnings of the breakpoints registered before the console
        // existed (the platform registers them during the session init).
        // The reference is a link: a click opens the line the breakpoint sits
        // on, which is the fastest way to see why it cannot fire.
        pendingWarnings.forEach { warning ->
            // Only the reference itself is a link, the sentence around it is text.
            print("Breakpoint at ", LogConsoleType.EMMY, ConsoleViewContentType.SYSTEM_OUTPUT)
            printHyperlink("${warning.file.name}:${warning.line}", LogConsoleType.EMMY) { project ->
                com.intellij.openapi.fileEditor.OpenFileDescriptor(
                    project, warning.file, warning.line - 1, 0
                ).navigate(true)
            }
            print(" is on an empty line: it will never fire\n", LogConsoleType.EMMY, ConsoleViewContentType.SYSTEM_OUTPUT)
        }
        pendingWarnings.clear()
        client.addCommand("DELB * 0")
        sendAllBreakpoints()
        if (session.runProfile is com.tang.intellij.lua.debugger.luaunit.LuaUnitRunConfiguration) {
            // mobdebug.start() suspends right after connecting ("start with
            // step command"): for a test run that stop sits in the runner
            // script and only wastes time — run on to the first breakpoint.
            //
            // The command queue is kept as short as possible: a command that
            // waits for a reply blocks everything queued behind it, so the
            // stack is not asked for here (it is asked for when a pause
            // arrives) and the breakpoints go before the RUN.
            print(
                "Debug: port $listenPort, ${sentBreakpoints.get()} breakpoints, running to the first one\n",
                LogConsoleType.EMMY,
                ConsoleViewContentType.SYSTEM_OUTPUT
            )
            // rl=0: RUN must not wait for a reply — its "200 OK" can arrive in
            // one packet with the "202 Paused" of a breakpoint that sits right
            // in the loading part, and a waiting command would swallow the
            // pause, leaving the session hanging with no frames and no way on.
            client.addCommand("RUN", 0)
        }
    }

    fun findSourcePosition(chunkName: String, line: Int): XSourcePosition? {
        var position: XSourcePositionImpl? = null
        val virtualFile = LuaFileUtil.findFile(session.project, chunkName)
        if (virtualFile != null) {
            recognizeBaseDir(virtualFile, chunkName)
            position = XSourcePositionImpl.create(virtualFile, line - 1)
        }
        return position
    }

    private fun recognizeBaseDir(file: VirtualFile, chunkName: String) {
        val pathParts = file.canonicalPath?.split(Regex("[\\/]"))?.reversed() ?: return
        val chunkParts = chunkName.split(Regex("[\\/]")).reversed()
        if (pathParts.size < chunkParts.size)
            return
        var neq = 1
        for (i in 1 until chunkParts.size) {
            val chunkPart = chunkParts[i]
            val pathPart = pathParts[i]
            if (chunkPart.lowercase() != pathPart.lowercase()) {
                break
            }
            neq = i
        }
        val dir = pathParts.takeLast(pathParts.size - neq).reversed().joinToString("/")
        val myBaseDir = this.baseDir
        if (myBaseDir == null || myBaseDir.length > dir.length) {
            this.baseDir = dir
            print("Base dir: $dir\n", LogConsoleType.EMMY, ConsoleViewContentType.SYSTEM_OUTPUT)
            sendAllBreakpoints()
        }
    }

    private fun sendAllBreakpoints() {
        // No reply is awaited on the clearing either: the queue must reach
        // RUN even if a "200 OK" of these is delayed or coalesced.
        mobClient?.addCommand("DELB * 0", 0)
        processBreakpoint(Processor { bp ->
            bp.sourcePosition?.let { sendBreakpoint(it) }
            true
        })
    }

    private fun getShortPath(file: VirtualFile): String {
        val myBaseDir = this.baseDir
        val path = file.canonicalPath
        if (myBaseDir != null && path != null && path.lowercase().startsWith(myBaseDir.lowercase())) {
            return path.substring(myBaseDir.length + 1)
        }
        return LuaFileUtil.getShortPath(session.project, file)
    }

    override val process: LuaMobDebugProcess
        get() = this

    fun runCommand(command: DebugCommand) {
        mobClient?.addCommand(command)
    }
}
