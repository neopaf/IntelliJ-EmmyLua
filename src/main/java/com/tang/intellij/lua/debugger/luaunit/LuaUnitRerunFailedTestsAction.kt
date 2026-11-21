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

import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.ui.ConsoleView

/**
 * "Rerun Failed Tests" of a LuaUnit console.
 *
 * <p>The base class collects the failed leaves of the tests tree and starts the
 * wrapper profile below. The wrapper builds a fresh copy of the configuration
 * that runs just those cases: one [LuaUnitRunConfiguration.Kind.CASE] run per
 * affected suite file, with the union of the suites' failed case names as the
 * LuaUnit `--pattern`s. Everything else (console, tree, diff) stays the
 * platform's.
 */
class LuaUnitRerunFailedTestsAction(
    consoleView: ConsoleView,
    private val console: LuaUnitTestConsole
) : AbstractRerunFailedTestsAction(consoleView) {

    override fun getRunProfile(environment: com.intellij.execution.runners.ExecutionEnvironment):
            AbstractRerunFailedTestsAction.MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? LuaUnitRunConfiguration
            ?: return null
        return MyRunProfile(configuration)
    }

    private inner class MyRunProfile(configuration: LuaUnitRunConfiguration) :
        AbstractRerunFailedTestsAction.MyRunProfile(configuration) {

        private val configuration: LuaUnitRunConfiguration = configuration

        override fun getPeer(): com.intellij.execution.configurations.RunConfigurationBase<*> =
            configuration

        override fun getState(
            executor: com.intellij.execution.Executor,
            environment: com.intellij.execution.runners.ExecutionEnvironment
        ): com.intellij.execution.configurations.RunProfileState? {
            // The console's own failure list is the source the "Debug failed"
            // button has always used: the platform's tree filter is only good
            // for enabling the button, not for naming our cases.
            val failed = failedCaseNames(console.failures)
            return if (failed.isEmpty()) configuration.getState(executor, environment)
            else LuaUnitRunState(configuration.withFailedCases(failed), executor, debug = false)
        }

        override fun createTestConsoleProperties(executor: com.intellij.execution.Executor):
                com.intellij.execution.testframework.TestConsoleProperties {
            return LuaUnitConsoleProperties(configuration, executor)
        }

        override fun clone(): com.intellij.execution.configurations.RunConfiguration = this

        override fun getConfigurationEditor():
                com.intellij.openapi.options.SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> =
            configuration.getConfigurationEditor()
    }
}

/** The failed cases of a run, grouped by their suite file, by their names. */
internal fun failedCaseNames(failures: List<LuaUnitFailure>): Map<java.nio.file.Path, List<String>> {
    val result = LinkedHashMap<java.nio.file.Path, LinkedHashSet<String>>()
    for (failure in failures) {
        result.getOrPut(java.nio.file.Path.of(failure.suite)) { LinkedHashSet() }.add(failure.caseName)
    }
    return result.mapValues { (_, names) -> names.toList() }
}
