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
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.tang.intellij.lua.debugger.remote.LuaMobDebugProcess
import java.net.ServerSocket

/**
 * The debug session of a LuaUnit run.
 *
 * <p>Debugging goes through the same mobdebug bridge as "Lua Remote(Mobdebug)":
 * [LuaMobDebugProcess] owns a [com.tang.intellij.lua.debugger.remote.MobServer],
 * accepts the connections of the suite processes, applies breakpoints, resolves
 * stack frames and evaluates expressions. What differs is only the transport
 * direction — a LuaUnit run starts the Lua processes itself instead of waiting
 * for an external one to connect, so:
 *
 * <ol>
 *   <li>the port is chosen here, before the session starts;</li>
 *   <li>the session listens on it and the suite processes connect to it
 *       (`mobdebug.start('127.0.0.1', port)`, from [LuaUnitRunnerScript.debugPrelude]);</li>
 *   <li>the console of the tests tree is created by the run state, while the
 *       debugger keeps its own console for the mobdebug chatter.</li>
 * </ol>
 *
 * <p>Several suites connect to the same server one after another: [MobServer]
 * serves the latest connection, so stepping into a test goes to whichever suite
 * suspended last — which is the test the user is looking at.
 */
object LuaUnitDebugger {

    /** Where the runner leaves the port for the run state. */
    val PORT_KEY = com.intellij.openapi.util.Key.create<Int>("lua.luaunit.debug.port")

    /**
     * The tests tree a debug session shows as its Console tab: the prepared
     * view waits here between [LuaUnitRunState.prepare] and the session
     * picking it up (by the port of its run).
     */
    private val consoles = java.util.concurrent.ConcurrentHashMap<Int, com.intellij.execution.ui.ExecutionConsole>()

    /** Remembers [view] as the console of the debug session on [port]. */
    fun offerConsole(port: Int, view: com.intellij.execution.ui.ExecutionConsole) {
        consoles[port] = view
    }

    /** The suite processes of the debug session on [port], for its stop. */
    private val processGroups =
        java.util.concurrent.ConcurrentHashMap<Int, LuaUnitProcessGroupHandler>()

    /** Remembers [group] as the processes to stop when the session stops. */
    fun offerProcesses(port: Int, group: LuaUnitProcessGroupHandler) {
        processGroups[port] = group
    }

    /** Stops the suite processes of the debug session on [port]. */
    fun stopProcesses(port: Int) {
        processGroups.remove(port)?.destroyProcess()
    }

    /** Takes the console offered for [port], if any. */
    fun takeConsole(port: Int): com.intellij.execution.ui.ExecutionConsole? = consoles.remove(port)

    /** The mobdebug port of a run; a free one, so several runs do not clash. */
    fun freePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    /** Starts the debug session that listens on [port]. */
    // Deprecated in IDEA 2026.2; no replacement exists in 2025.3, so the
    // common API stays for both.
    @Suppress("DEPRECATION")
    @Throws(ExecutionException::class)
    fun startSession(environment: ExecutionEnvironment, port: Int): XDebugSession {
        return XDebuggerManager.getInstance(environment.project).startSession(
            environment,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    return LuaMobDebugProcess(session, port)
                }
            }
        )
    }

    /** True when the executor of [environment] asks for a debugger. */
    fun isDebug(environment: ExecutionEnvironment): Boolean =
        environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
}
