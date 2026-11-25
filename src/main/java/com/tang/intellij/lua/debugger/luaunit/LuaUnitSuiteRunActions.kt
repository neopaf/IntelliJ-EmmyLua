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

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.EditRunConfigurationsAction
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.nio.file.Path

/**
 * The file a suite (file) node of the tests tree stands for.
 *
 * <p>A suite node that has cases deliberately carries no location URL (a
 * double-click toggles its children), and the platform builds its Run entries
 * from that URL: such a node would have no way to run its whole file. The file
 * itself is remembered here, so the actions below start it.
 *
 * <p>A file node without a single case (a plain script the runner executes on
 * load) is not registered: it keeps its URL, the platform's own Run / Debug
 * entries work from it, and adding ours would only duplicate them.
 */
object LuaUnitSuiteNodeFiles {

    private val FILE: Key<Path> = Key.create("luaunit.suiteFile")

    /** Remembers that [proxy] is the tree node of [file]. */
    fun remember(proxy: SMTestProxy, file: Path) {
        proxy.putUserData(FILE, file)
    }

    /** The file of a suite node, or null for any other node. */
    fun savedFile(proxy: SMTestProxy): Path? = proxy.getUserData(FILE)
}

/**
 * "Run `<file>`" of a suite node: the whole test file as one Lua process.
 *
 * <p>The length of the run is the FILE scope — the scope the manifest's entry
 * line runs — not the location line of the node: a one-line run of a
 * file-with-cases node would start the whole suite instead.
 */
class LuaUnitRunSuiteAction : LuaUnitSuiteAction(false)

/** "Debug `<file>`" of a suite node: the same file under the debugger. */
class LuaUnitDebugSuiteAction : LuaUnitSuiteAction(true)

/** The shared body of the two suite run actions. */
abstract class LuaUnitSuiteAction(private val debug: Boolean) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val file = selectedSuiteFile(event)
        event.presentation.isVisible = file != null
        event.presentation.isEnabled = file != null
        if (file != null) {
            event.presentation.text = (if (debug) "Debug '" else "Run '") + file.fileName + "'"
            event.presentation.icon = if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = selectedSuiteFile(event) ?: return
        val template = templateFor(project, file) ?: return
        val settings = LuaUnitRunConfigurations.reuseOrCreate(project, template) ?: return
        ProgramRunnerUtil.executeConfiguration(
            settings,
            if (debug) DefaultDebugExecutor.getDebugExecutorInstance()
            else DefaultRunExecutor.getRunExecutorInstance()
        )
    }
}

/** "Modify Run Configuration..." of a suite node: the same FILE scope, opened. */
class LuaUnitModifySuiteRunAction
    : AnAction("Modify Run Configuration...", null, AllIcons.General.GearPlain) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val file = selectedSuiteFile(event)
        event.presentation.isVisible = file != null
        event.presentation.isEnabled = file != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = selectedSuiteFile(event) ?: return
        val template = templateFor(project, file) ?: return
        LuaUnitRunConfigurations.openSettings(project, template, event)
    }
}

/** The file of the selected tests-tree node, when it is a suite node. */
private fun selectedSuiteFile(event: AnActionEvent): Path? {
    // Another framework's tree must keep its own menu: only a LuaUnit console
    // knows the file of its nodes.
    val model = event.getData(TestTreeView.MODEL_DATA_KEY) ?: return null
    if (model.properties?.configuration !is LuaUnitRunConfiguration) {
        return null
    }
    val tree = SMTRunnerTestTreeView.SM_TEST_RUNNER_VIEW.getData(event.dataContext) ?: return null
    val proxy = tree.selectedTest as? SMTestProxy ?: return null
    return LuaUnitSuiteNodeFiles.savedFile(proxy)
}

/** A fresh FILE-scope configuration for [file], named the way the tree shows it. */
private fun templateFor(project: Project, file: Path): LuaUnitRunConfiguration? {
    val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
    val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
        ?: return null
    template.configureFile(file)
    return template
}

/**
 * The run-configuration plumbing shared by the gutter icons and the tests tree.
 *
 * <p>A click reuses the configuration of the previous run of the same scope
 * (the same scope file and name) instead of stacking a new temporary one on
 * every click; "Modify Run Configuration" opens that same configuration, so a
 * change made in the dialog is the one the next click runs.
 */
object LuaUnitRunConfigurations {

    /**
     * The configuration of the previous run of [template]'s scope, or a new
     * temporary one.
     *
     * <p>A configuration of an older build may carry the same scope with an
     * empty `--pattern`: reusing it as is would quietly widen the run to every
     * case of the file, so the scope fields of this click are copied into the
     * reused configuration too.
     */
    fun reuseOrCreate(project: Project, template: LuaUnitRunConfiguration): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)
        val existing = runManager.allSettings.firstOrNull { settings ->
            val configuration = settings.configuration as? LuaUnitRunConfiguration
                ?: return@firstOrNull false
            configuration.scopeKind == template.scopeKind &&
                    configuration.scopeFile == template.scopeFile &&
                    configuration.scopeName == template.scopeName
        }
        if (existing != null) {
            (existing.configuration as? LuaUnitRunConfiguration)?.let { configuration ->
                configuration.casePattern = template.casePattern
                configuration.workingDir = template.workingDir
            }
            return existing
        }
        val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
        return runManager
            .createConfiguration(template as RunConfiguration, factory)
            .also { it.isTemporary = true }
    }

    /**
     * Opens the settings of [template]. The configuration is temporary until
     * the user saves it, so the entry creates it, selects it and opens the
     * dialog with it.
     */
    fun openSettings(project: Project, template: LuaUnitRunConfiguration, event: AnActionEvent) {
        val settings = reuseOrCreate(project, template) ?: return
        val runManager = RunManager.getInstance(project)
        if (settings !in runManager.allSettings) {
            runManager.addConfiguration(settings)
        }
        runManager.selectedConfiguration = settings
        EditRunConfigurationsAction().actionPerformed(event)
    }
}
