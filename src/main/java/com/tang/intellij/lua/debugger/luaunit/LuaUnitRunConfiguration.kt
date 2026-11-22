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
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.WriteExternalException
import com.tang.intellij.lua.debugger.LuaRunConfiguration
import org.jdom.Element
import java.io.File
import java.nio.file.Path

/**
 * A LuaUnit run configuration: the `run_tests.lua`-style tree of suites and
 * cases, executed the way a JVM test runner does it — one process per suite,
 * failures with clickable expected/actual pairs, and the same tree under Run
 * and Debug.
 *
 * <p>The scope narrows from a whole manifest to a section, a file or a single
 * case; the same configuration type also backs the "Run all tests"-style
 * entries on the manifest, so every entry of the tree is a plain run
 * configuration that can be rerun, pinned to a tab and exported.
 *
 * <p>`scopePath` is the file (or manifest) the entry was created from,
 * `scopeKind` says what `scopePath` means and `scopeName` filters it further
 * (a section title or a case name).
 */
class LuaUnitRunConfiguration(project: Project, factory: ConfigurationFactory)
    : LuaRunConfiguration(project, factory) {

    override fun getValidModules(): Collection<com.intellij.openapi.module.Module> = emptyList()

    /**
     * The failed cases of the console, as "fileNULcaseName" entries.
     * When non-empty, the state runs those cases instead of the configured
     * scope — that is what "Rerun Failed Tests" needs.
     */
    var failedCases: List<String> = emptyList()

    /** A copy of this configuration that runs only [failedCases]. */
    fun withFailedCases(cases: Map<Path, List<String>>): LuaUnitRunConfiguration {
        val copy = clone() as LuaUnitRunConfiguration
        // ALL, not CASE: the discovery must walk the whole manifest — the
        // failed cases may live in any of its files, and the tree of the
        // rerun is built from that discovery (a CASE scope would discover
        // only the first failed file and leave the others "(0)").
        copy.scopeKind = Kind.ALL
        copy.failedCases = cases.flatMap { (file, names) -> names.map { "$file\u0000$it" } }
        if (cases.isNotEmpty()) {
            copy.scopePath = cases.keys.first().toString()
            copy.scopeName = cases.values.first().firstOrNull()
            copy.casePattern = cases.values.first().firstOrNull()
        }
        return copy
    }

    enum class Kind {
        /** Every file of the manifest. */
        ALL,

        /** The files of one section of the manifest (`scopeName`). */
        SECTION,

        /** One test file. */
        FILE,

        /** One case of one test file: the LuaUnit `--pattern` is `casePattern`. */
        CASE
    }

    var interpreter: String = defaultInterpreter()
    var workingDir: String? = null
    var scopePath: String? = null
    var scopeKind: Kind = Kind.ALL
    var scopeName: String? = null
    var casePattern: String? = null
    var envs: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
    /** Passed to the interpreter before the runner script. */
    var extraArgs: String? = null

    /**
     * Points this configuration at one generated case: [file] is the generator
     * (a file of the manifest), the case's name is `suite.prefix + id`.
     */
    fun configureDataCase(file: Path, suite: String, prefix: String, id: String) {
        scopePath = file.toString()
        scopeKind = Kind.CASE
        // "_G" generates globals: LuaUnit (and the discovery tree) name them
        // "test_x" with no class part, so the pattern must not carry "_G.".
        val full = if (suite == "_G") "$prefix$id" else "$suite.$prefix$id"
        scopeName = full
        casePattern = full
        workingDir = LuaUnitProjectRoot.of(file)?.toString() ?: workingDir
        name = full
    }

    /** Points this configuration at one section of the manifest. */
    fun configureSection(manifest: Path, section: String) {
        scopePath = manifest.toString()
        scopeKind = Kind.SECTION
        scopeName = section
        casePattern = null
        workingDir = LuaUnitProjectRoot.of(manifest)?.toString() ?: workingDir
        name = section
    }

    /** Points this configuration at one test file of the manifest. */
    fun configureFile(file: Path) {
        scopePath = file.toString()
        scopeKind = Kind.FILE
        scopeName = null
        casePattern = null
        workingDir = LuaUnitProjectRoot.of(file)?.toString() ?: workingDir
        // The configuration is named the way the tree shows the file: its
        // path from the project root.
        val root = LuaUnitProjectRoot.of(file)
        name = if (root != null) {
            try {
                root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
            } catch (e: IllegalArgumentException) {
                file.fileName.toString()
            }
        } else {
            file.fileName.toString()
        }
    }

    /** Absolute path of the file or the manifest the entry starts from. */
    val scopeFile: Path?
        get() {
            val path = scopePath ?: return null
            val expanded = expandMacros(path)
            val file = File(expanded)
            val base = workingDir?.takeIf { it.isNotBlank() }?.let { expandMacros(it) }
            return (if (file.isAbsolute) file else File(base ?: project.basePath ?: ".", expanded))
                .toPath().toAbsolutePath().normalize()
        }

    override fun getState(executor: Executor, executionEnvironment: ExecutionEnvironment): RunProfileState {
        val debug = executor.id == DefaultDebugExecutor.EXECUTOR_ID
        // The runner allocates the debugger port before this state runs; the
        // port is handed over through the user data of the environment.
        val port = executionEnvironment.getUserData(LuaUnitDebugger.PORT_KEY)
        return LuaUnitRunState(this, executor, debug, port, executionEnvironment)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return LuaUnitSettingsEditor(project)
    }

    override fun checkConfiguration() {
        // Nothing of the IDE has to be built before a Lua run: the Build step
        // the platform keeps adding to a JVM project only slows it down.
        setBeforeRunTasks(emptyList())
        val interpreter = interpreter.trim()
        if (interpreter.isEmpty()) {
            throw RuntimeConfigurationError("The Lua interpreter is not set")
        }
        val workingDir = workingDirOrDefault()
        if (workingDir == null || !File(workingDir).isDirectory) {
            throw RuntimeConfigurationError("Working directory does not exist")
        }
        val scope = scopeFile
        if (scope == null || !scope.toFile().exists()) {
            throw RuntimeConfigurationError("The test file or manifest does not exist: ${scopePath ?: ""}")
        }
        when (scopeKind) {
            Kind.CASE -> {
                if (scopeName.isNullOrBlank()) {
                    throw RuntimeConfigurationError("No case is selected for a case run")
                }
                if (casePattern.isNullOrBlank()) {
                    throw RuntimeConfigurationError("No LuaUnit pattern is set for a case run")
                }
            }
            Kind.SECTION -> if (scopeName.isNullOrBlank()) {
                throw RuntimeConfigurationError("No section is selected for a section run")
            }
            else -> Unit
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("interpreter", interpreter)
        JDOMExternalizerUtil.writeField(element, "workingDir", workingDir)
        JDOMExternalizerUtil.writeField(element, "scopePath", scopePath)
        JDOMExternalizerUtil.writeField(element, "scopeKind", scopeKind.name)
        JDOMExternalizerUtil.writeField(element, "scopeName", scopeName)
        JDOMExternalizerUtil.writeField(element, "casePattern", casePattern)
        JDOMExternalizerUtil.writeField(element, "extraArgs", extraArgs)
        envs.writeExternal(element)
    }

    @Throws(InvalidDataException::class)
    override fun readExternal(element: Element) {
        super.readExternal(element)
        JDOMExternalizerUtil.readField(element, "interpreter")?.let { interpreter = it }
        workingDir = JDOMExternalizerUtil.readField(element, "workingDir")
        scopePath = JDOMExternalizerUtil.readField(element, "scopePath")
        JDOMExternalizerUtil.readField(element, "scopeKind")?.let {
            scopeKind = try {
                Kind.valueOf(it)
            } catch (e: IllegalArgumentException) {
                Kind.ALL
            }
        }
        scopeName = JDOMExternalizerUtil.readField(element, "scopeName")
        casePattern = JDOMExternalizerUtil.readField(element, "casePattern")
        extraArgs = JDOMExternalizerUtil.readField(element, "extraArgs")
        envs = EnvironmentVariablesData.readExternal(element)
    }

    /** Working directory of the configuration; the scope directory by default. */
    fun workingDirOrDefault(): String? {
        val explicit = workingDir
        if (!explicit.isNullOrBlank()) {
            val expanded = expandMacros(explicit)
            val file = java.io.File(expanded)
            // An explicit directory is honoured as long as the test files
            // resolve from it; a stale one (the project moved, or a
            // configuration created before the paths were project-root based)
            // must not break every run.
            val scope = scopeFile?.toAbsolutePath()?.normalize()
            if (file.isDirectory && (scope == null
                    || scope.startsWith(file.toPath().toAbsolutePath().normalize()))) {
                return expanded
            }
        }
        // The tests run from the project root: `lua tests/run_tests.lua` and
        // `require('tests/...')` are both relative to it.
        val parent = scopeFile?.parent
        if (parent != null) {
            LuaUnitProjectRoot.of(parent)?.let { return it.toString() }
            return parent.toString()
        }
        return project.basePath
    }

    /**
     * Expands `$PROJECT_DIR$` and friends in a configured path.
     *
     * <p>The settings are stored with macros (the platform's file choosers and
     * the JDOM externalizer both do that), so every path that reaches the
     * process must go through here first.
     */
    fun expandMacros(value: String): String {
        var result = value
        val basePath = project.basePath
        if (basePath != null) {
            result = result.replace("\$PROJECT_DIR\$", basePath)
        }
        return com.intellij.openapi.util.io.FileUtil.toSystemDependentName(result)
    }

    companion object {
        fun defaultInterpreter(): String {
            val name = if (SystemInfoRt.isWindows) "lua.exe" else "lua"
            val onPath = com.intellij.execution.configurations.PathEnvironmentVariableUtil
                .findInPath(name)
            return onPath?.absolutePath ?: name
        }

    }
}
