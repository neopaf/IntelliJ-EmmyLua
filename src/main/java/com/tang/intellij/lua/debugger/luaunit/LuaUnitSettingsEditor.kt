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

import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Settings of a LuaUnit configuration: the interpreter, the working directory,
 * the scope (manifest / section / file / case) and the process environment.
 *
 * <p>Most entries are created by the run configuration producer, so the fields
 * are mostly for inspection and for the manual "LuaUnit" configuration.
 */
class LuaUnitSettingsEditor(private val project: Project) : SettingsEditor<LuaUnitRunConfiguration>() {

    private val interpreter = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Choose Lua Interpreter")
        )
    }
    private val workingDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }
    // The test entry point is always the project's tests/run_tests.lua manifest:
    // it is shown for reference, not edited. A case/section selected from the
    // editor becomes a separate configuration, so the field stays read-only.
    private val manifest = JTextField().apply {
        isEditable = false
        isFocusable = false
    }
    private val extraArgs = RawCommandLineEditor()
    // The no-argument constructor is deprecated in IDEA 2026.2 only; the
    // project-aware one does not exist in 2025.3.
    @Suppress("DEPRECATION")
    private val envs = EnvironmentVariablesTextFieldWithBrowseButton()
    // What the run actually narrows to, and the LuaUnit pattern of a case run:
    // a configuration created from the gutter must show both, otherwise a run
    // that executes a whole file looks exactly like a one-case run.
    private val scope = JTextField().apply {
        isEditable = false
        isFocusable = false
    }
    private val pattern = JTextField().apply {
        isEditable = false
        isFocusable = false
    }

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("&Interpreter:", interpreter)
        .addLabeledComponent("&Working directory:", workingDir)
        .addLabeledComponent("&Test manifest:", manifest)
        .addLabeledComponent("&Scope:", scope)
        .addLabeledComponent("&LuaUnit pattern (case runs):", pattern)
        .addLabeledComponent("&Interpreter arguments:", extraArgs)
        .addLabeledComponent("&Environment variables:", envs)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun resetEditorFrom(configuration: LuaUnitRunConfiguration) {
        interpreter.text = configuration.interpreter
        workingDir.text = configuration.workingDir ?: configuration.project.basePath ?: ""
        manifest.text = configuration.scopeFile?.let { LuaTestManifest.findManifest(it) }?.toString()
            ?: configuration.scopePath ?: ""
        scope.text = when (configuration.scopeKind) {
            LuaUnitRunConfiguration.Kind.ALL -> "all tests of the manifest"
            LuaUnitRunConfiguration.Kind.SECTION -> "section: ${configuration.scopeName ?: ""}"
            LuaUnitRunConfiguration.Kind.FILE -> "file: ${configuration.scopeFile?.fileName ?: ""}"
            LuaUnitRunConfiguration.Kind.CASE -> "case: ${configuration.scopeName ?: ""}"
        }
        // A case run without a pattern executes the whole file, which is never
        // what the entry was created for: show it loudly.
        pattern.text = configuration.casePattern ?: if (configuration.scopeKind == LuaUnitRunConfiguration.Kind.CASE)
            "— not set, the whole file will run —" else ""
        extraArgs.text = configuration.extraArgs ?: ""
        envs.data = configuration.envs
    }

    override fun applyEditorTo(configuration: LuaUnitRunConfiguration) {
        configuration.interpreter = interpreter.text
        configuration.workingDir = workingDir.text.ifBlank { null }
        configuration.extraArgs = extraArgs.text.ifBlank { null }
        configuration.envs = envs.data
    }

    override fun createEditor(): JComponent = panel
}
