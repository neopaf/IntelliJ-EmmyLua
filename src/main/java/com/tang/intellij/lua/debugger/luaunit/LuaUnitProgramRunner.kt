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

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.tang.intellij.lua.debugger.LuaRunner

/**
 * Runs the LuaUnit configuration under either executor.
 *
 * <p>Under Run the test console is the only tab. Under Debug the Lua processes
 * of the suites are started with the bundled mobdebug connected to an IDE-side
 * server ([LuaUnitDebugServer]), and the console is shown next to the debug
 * session, the way a "Lua Application" debug run looks.
 */
class LuaUnitProgramRunner : LuaRunner() {

    override fun getRunnerId(): String = ID

    override fun canRun(executorId: String, runProfile: RunProfile): Boolean {
        // "Rerun failed tests" runs its own wrapper around the configuration:
        // unwrap it, or the runner registry finds no runner for the action and
        // the button does nothing.
        val profile = runProfile as? LuaUnitRunConfiguration
            ?: com.intellij.execution.configurations.WrappingRunConfiguration
                .unwrapRunProfile(runProfile) as? LuaUnitRunConfiguration
        return profile != null &&
                (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID)
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        FileDocumentManager.getInstance().saveAllDocuments()
        if (LuaUnitDebugger.isDebug(environment)) {
            // The debugger listens first: the port is known before the suites
            // start, and the session owns the tab of the debug run.
            val port = LuaUnitDebugger.freePort()
            environment.putUserData(LuaUnitDebugger.PORT_KEY, port)
            val luaUnitState = state as? LuaUnitRunState
            if (luaUnitState != null) {
                luaUnitState.debugPort = port
                // The tree is built before the session starts: the session's
                // Console tab is the tests tree itself (the Java way), not a
                // log console of its own.
                val prepared = luaUnitState.prepare()
                LuaUnitDebugger.offerConsole(port, prepared.view)
                // The console follows the group of the (lazily created) suite
                // processes, not the first one: attached to the first, its
                // termination ended the run for the platform — a "terminated"
                // paint over the not-yet-started suites and a premature
                // summary — while the chain had not even started them.
                val group = LuaUnitProcessGroupHandler(prepared.suites.size)
                prepared.view.attachToProcess(group)
                group.startNotify()
                // The stop button of the debug session stops the Lua processes
                // through this group, the way a Run tab's stop does.
                LuaUnitDebugger.offerProcesses(port, group)
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit").info(
                    "debug run: port=$port, suites=${prepared.suites.size}, first=${prepared.suites.firstOrNull()?.key}"
                )
                val session = LuaUnitDebugger.startSession(environment, port)
                // Only now the processes may start: the mobdebug server of the
                // session is listening, and the suites run one after another.
                // The session ends with them — its processes are not attached
                // to it, so without this the debug tab would wait for a
                // connection that is never coming again.
                prepared.console.onAllSuitesFinished = { session.stop() }
                luaUnitState.start(prepared, group)
                return session.runContentDescriptor
            }
        }
        val result = state.execute(environment.executor, environment.runner) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    companion object {
        const val ID = "lua.luaunit.runner"
    }
}
