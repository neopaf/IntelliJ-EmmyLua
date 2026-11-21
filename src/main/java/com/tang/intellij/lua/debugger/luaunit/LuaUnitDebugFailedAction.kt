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

import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import java.nio.file.Path

/**
 * "Debug Failed Tests" in the toolbar of the tests console.
 *
 * <p>Runs the failed cases again, but under the debugger: a copy of the
 * configuration is built that holds the union of each affected suite file's
 * failed case names as one LuaUnit `--pattern`. The suites are debugged one
 * after another (they all share the single mobdebug port of the session), so
 * a breakpoint in any failed case is reached without the rest of the file
 * running first.
 */
class LuaUnitDebugFailedAction(
    private val configuration: LuaUnitRunConfiguration,
    private val console: LuaUnitTestConsole
) : AnAction(
    "Debug Failed Tests",
    "Debug the failed cases, one suite file after another",
    AllIcons.Actions.StartDebugger
) {

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = console.failures.isNotEmpty()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val failed = failedCases()
        if (failed.isEmpty()) {
            // The toolbar icon is laid out when the run starts and is not
            // redrawn when the run ends, so a click on a green run lands here.
            // Staying silent looked like "did not start at all".
            com.intellij.notification.Notifications.Bus.notify(
                com.intellij.notification.Notification(
                    "LuaUnit",
                    "LuaUnit",
                    "No failed cases in this run",
                    com.intellij.notification.NotificationType.INFORMATION
                ),
                project
            )
            return
        }
        val copy = configuration.withFailedCases(failed)
        val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
        val settings = RunManager.getInstance(project)
            .createConfiguration(copy, factory)
            .also { it.isTemporary = true }
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
    }

    /** The failed cases of the run, grouped by their suite file, by their names. */
    private fun failedCases(): Map<Path, List<String>> = failedCaseNames(console.failures)
}
