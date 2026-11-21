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

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.tang.intellij.lua.lang.LuaIcons
import javax.swing.Icon

/**
 * The "LuaUnit" run configuration type: a JUnit-style test tree for Lua test
 * suites driven by `tests/run_tests.lua`.
 */
class LuaUnitConfigurationType : ConfigurationType {

    private val factory = LuaUnitConfigurationFactory(this)

    override fun getDisplayName(): String = "LuaUnit"

    override fun getConfigurationTypeDescription(): String =
        "Runs LuaUnit test suites (tests/run_tests.lua) as a test tree"

    override fun getIcon(): Icon = LuaIcons.FILE

    override fun getId(): String = ID

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    companion object {
        const val ID = "lua.luaunit"

        fun getInstance(): LuaUnitConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(LuaUnitConfigurationType::class.java)
    }
}

class LuaUnitConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {

    /** The factory id, as the platform requires it to be localization-free. */
    override fun getId(): String = FACTORY_ID

    override fun getName(): String = "LuaUnit"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        LuaUnitRunConfiguration(project, this).apply {
            // A new configuration of the dialog is a copy of the template: no
            // Build step from the very first moment, not only after a check.
            setBeforeRunTasks(emptyList())
            // A readable name instead of "Unnamed": the run is "LuaUnit".
            name = "LuaUnit"
        }

    /**
     * A LuaUnit run starts a Lua interpreter over files that are already on
     * disk: the IDE has nothing to build, so the default Build step the platform
     * adds to a new configuration of a JVM project only slows the run down.
     */
    override fun configureDefaultSettings(settings: com.intellij.execution.RunnerAndConfigurationSettings) {
        super.configureDefaultSettings(settings)
        (settings.configuration as? LuaUnitRunConfiguration)?.apply {
            setBeforeRunTasks(emptyList())
            // The dialog shows what is stored: pre-fill the working directory
            // and the manifest with the project's own values.
            workingDir = project.basePath
            if (scopePath == null) {
                project.basePath?.let { base ->
                    val manifest = java.nio.file.Path.of(base, "tests", "run_tests.lua")
                    if (java.nio.file.Files.isRegularFile(manifest)) {
                        scopePath = manifest.toString()
                        scopeKind = LuaUnitRunConfiguration.Kind.ALL
                        name = "All tests"
                    }
                }
            }
        }
    }

    companion object {
        private const val FACTORY_ID = "LuaUnit"
    }
}
