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
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Function
import com.tang.intellij.lua.psi.LuaClassMethodDef
import com.tang.intellij.lua.psi.LuaFuncDef
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLocalFuncDef
import com.tang.intellij.lua.psi.LuaPsiFile

/**
 * The Run/Debug gutter icons of a LuaUnit test file.
 *
 * <p>The markers sit on the entry points of a run: the manifest, a test
 * function (`function TestSuite:test_x()`) and the `id = "..."` row of a
 * generated case, in a suite file as well as in a plain data module (where the
 * row declares the case of every suite that requires the module).
 *
 * <p>The icon offers exactly two entries, Run and Debug. They build the
 * configuration from the element the icon sits on (through
 * [LuaUnitRunConfigurationProducer.configureFor]) and reuse the configuration
 * of the previous run of the same scope, so the Run menu does not grow a new
 * entry on every click.
 *
 * <p>One line, one marker: the platform asks this contributor twice — the fast
 * pass walks the leaves of the file, the slow one the composite nodes — and a
 * marker created on both appeared in the gutter twice, its menus glued into
 * "Run, Debug, Run, Debug". Only a leaf that starts its own anchor (the
 * declaration, the literal of a data row) may answer.
 */
class LuaUnitRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // Leaves only: a composite node is asked in the slow pass, and its
        // first leaf has already answered in the fast one.
        if (element.firstChild != null) {
            return null
        }
        val file = element.containingFile as? LuaPsiFile ?: return null
        val path = file.virtualFile?.path ?: return null
        val diskPath = java.nio.file.Path.of(path)
        val manifest = LuaTestManifest.findManifest(diskPath) ?: return null

        if (manifest.toString() == path) {
            // The manifest is the entry point of the whole suite. The first
            // leaf of the file runs everything (ONE marker: the shebang line
            // alone holds three leaves, and one per line was noise). Further
            // down, a section title line runs its section, and an entry line
            // runs that file. Entries without a section comment have no
            // group — there is nothing above the file to run.
            if (isFirstLeaf(element)) {
                return info("Run all LuaUnit tests", element)
            }
            if (element is com.intellij.psi.PsiWhiteSpace || !isFirstElementOfLine(element)) {
                return null
            }
            val line = file.text.substring(0, element.textRange.startOffset).count { it == '\n' } + 1
            // The manifest is parsed from the editor's copy: unsaved edits
            // must not leave the markers behind until the file is saved.
            val directory = LuaUnitProjectRoot.of(manifest)
                ?: manifest.parent ?: manifest.toAbsolutePath().parent
            val title = LuaTestManifest.sectionTitleAt(file.text, directory, line)
            if (title != null) {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("manifest gutter: line=$line TITLE '$title'")
                return scopeInfo("Run $title tests") { it.configureSection(manifest, title) }
            }
            val entry = LuaTestManifest.entryAt(file.text, directory, line)
            if (entry != null) {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("manifest gutter: line=$line ENTRY $entry")
                return scopeInfo("Run ${entry.fileName}") { it.configureFile(entry) }
            }
            return null
        }

        val inManifest = try {
            val normalized = diskPath.toAbsolutePath().normalize()
            LuaTestManifest.files(manifest).any { it.toAbsolutePath().normalize() == normalized }
        } catch (e: Exception) {
            false
        }
        if (!inManifest) {
            return dataEntryInfo(element, manifest, diskPath)
        }

        // A case's function: "function T:test_x()" parses as LuaClassMethodDef,
        // not LuaFuncDef. Only the leaf the declaration starts with answers.
        val func: PsiElement? = PsiTreeUtil.getParentOfType(element, LuaClassMethodDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaFuncDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaLocalFuncDef::class.java, false)
        if (func != null && isTopLevelTest(func)) {
            if (element.textRange.startOffset != func.textRange.startOffset) {
                return null
            }
            val name = LuaUnitCaseAtCaret.find(func.containingFile, func)?.displayName
                ?: funcName(func)
                ?: return null
            return info("Run $name", element)
        }

        // The data entry of a generated case: the marker is on its literal —
        // but only on the `id` line of a row the discovery really turns into
        // a case. An `id` inside a nested table (a pipe dictionary, a
        // counter list) is plain data: no marker there.
        val literal = PsiTreeUtil.getParentOfType(element, LuaLiteralExpr::class.java, false)
        if (literal != null && isDataEntry(literal) &&
            element.textRange.startOffset == literal.textRange.startOffset
        ) {
            val root = LuaUnitProjectRoot.of(manifest)
            val line = file.text.substring(0, literal.textRange.startOffset).count { it == '\n' } + 1
            val isCaseLine = root != null && LuaUnitTextDiscovery.casesCached(manifest, diskPath, root)
                .any { it.idLine == line }
            if (isCaseLine) {
                return info("Run this case", element)
            }
        }
        return null
    }

    /** True when [element] is the very first leaf of its file. */
    private fun isFirstLeaf(element: PsiElement): Boolean =
        element == com.intellij.psi.util.PsiTreeUtil.getDeepestFirst(element.containingFile)

    /** True when [element] is the first non-blank element of its line. */
    private fun isFirstElementOfLine(element: PsiElement): Boolean {
        val text = element.containingFile.text
        val offset = element.textRange.startOffset
        if (offset > text.length) {
            return false
        }
        var lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        if (lineStart > offset) {
            lineStart = 0
        }
        var index = lineStart
        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) {
            index++
        }
        return index == offset
    }

    /** One marker: a Run/Debug pair for the scope [configure] sets up. */
    private fun scopeInfo(tooltip: String, configure: (LuaUnitRunConfiguration) -> Unit): Info =
        Info(
            AllIcons.RunConfigurations.TestState.Run,
            arrayOf(
                scopeAction("Run", false, configure),
                scopeAction("Debug", true, configure)
            ),
            Function { tooltip }
        )

    /** Runs (or debugs) the scope a fresh configuration [configure] points at. */
    private fun scopeAction(text: String, debug: Boolean, configure: (LuaUnitRunConfiguration) -> Unit): AnAction {
        return object : AnAction(text, text, icon(debug)) {
            init {
                // Without mnemonic parsing, to keep the "_" of test names.
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                configure(template)
                val settings = reuseOrCreate(project, template) ?: return
                ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
            }
        }
    }

    private fun funcName(func: PsiElement): String? = when (func) {
        is LuaClassMethodDef -> func.name
        is LuaFuncDef -> func.name
        is LuaLocalFuncDef -> func.name
        else -> null
    }

    /** A test function declared at file level (not a helper inside a body). */
    private fun isTopLevelTest(func: PsiElement): Boolean {
        val name = funcName(func) ?: return false
        if (!name.startsWith("test")) {
            return false
        }
        // A function declared inside another function body is a helper, not a case.
        return PsiTreeUtil.getParentOfType(func, LuaFuncDef::class.java, true) == null &&
                PsiTreeUtil.getParentOfType(func, LuaClassMethodDef::class.java, true) == null
    }

    private fun isDataEntry(literal: LuaLiteralExpr): Boolean {
        val text = literal.text
        if (text.length < 2 || (text.first() != '"' && text.first() != '\'')) {
            return false
        }
        // The key of the field must be exactly `id` (or ["id"] / ['id']), not a
        // mere prefix like `identifier`; the leading name is matched whole.
        val field = literal.parent?.text?.trimStart() ?: return false
        val name = field.takeWhile { it.isLetterOrDigit() || it == '_' }
        return name == "id" || field.startsWith("[\"id\"]") || field.startsWith("['id']")
    }

    /** One icon, a Run and a Debug entry. */
    private fun info(tooltip: String, element: PsiElement): Info {
        val provider = Function<PsiElement, String> { tooltip }
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            arrayOf(runAction(element, false), runAction(element, true)),
            provider
        )
    }

    /** Runs (or debugs) the case of [element], reusing the configuration it already has. */
    private fun runAction(element: PsiElement, debug: Boolean): AnAction {
        val text = if (debug) "Debug" else "Run"
        return object : AnAction(text, text, icon(debug)) {
            init {
                // Without mnemonic parsing, to keep the "_" of test names.
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                execute(project, element, debug)
            }
        }
    }

    /**
     * A marker in a plain data module: a row `id = "..."` there is the
     * declaration line of a case in every test file that builds cases from the
     * module (there may be several). The icon runs those generated cases.
     */
    private fun dataEntryInfo(
        element: PsiElement,
        manifest: java.nio.file.Path,
        dataFile: java.nio.file.Path
    ): Info? {
        val literal = PsiTreeUtil.getParentOfType(element, LuaLiteralExpr::class.java, false) ?: return null
        if (!isDataEntry(literal) || element.textRange.startOffset != literal.textRange.startOffset) {
            return null
        }
        val raw = literal.text
        if (raw.length < 2) return null
        // The case name is the row's `desc` when it has one, otherwise its `id`.
        val id = LuaUnitDataSources.rowName(dataFile, raw.substring(1, raw.length - 1), literal.textRange.startOffset)
            ?: return null
        if (id.isEmpty()) return null
        val root = LuaUnitProjectRoot.of(manifest) ?: return null
        val generators = LuaUnitDataSources.find(manifest, dataFile, root)
        if (generators.isEmpty()) return null
        // The row declares a case of every generator that requires the
        // module; two generators of one data module name their cases alike
        // (test_ss7_data feeds test_decode_ss7 and test_encode_ss7 the same
        // "TestSuite.test_<id>"), so the generator's file tells them apart.
        fun caseName(generator: LuaUnitDataSources.Generator): String =
            if (generator.suite == "_G") generator.prefix + id
            else generator.suite + "." + generator.prefix + id
        val tooltip = "Run " + generators.joinToString("; ") { generator ->
            caseName(generator) + " (" + generator.file.fileName + ")"
        }
        // One Run/Debug pair per generator, labelled by the generator's file
        // (its leading "test_" says nothing here) and separated by a rule,
        // the way the platform separates unrelated actions.
        val actions = ArrayList<AnAction>()
        for ((index, generator) in generators.withIndex()) {
            if (index > 0) {
                actions.add(com.intellij.openapi.actionSystem.Separator.create())
            }
            // The label keeps the file's "_" — the action text is stored
            // without mnemonic parsing below, so it is not eaten.
            val pair = if (generators.size == 1) null
                else generator.file.fileName.toString().removeSuffix(".lua").removePrefix("test_")
            actions.add(dataAction(element, generator, id, pair, false))
            actions.add(dataAction(element, generator, id, pair, true))
            actions.add(modifyAction(element, generator, id, pair))
        }
        return Info(AllIcons.RunConfigurations.TestState.Run, actions.toTypedArray(), Function { tooltip })
    }

    /** Opens the settings of the configuration the gutter would run. */
    private fun modifyAction(
        element: PsiElement,
        generator: LuaUnitDataSources.Generator,
        id: String,
        label: String?
    ): AnAction {
        val text = if (label == null) "Modify Run Configuration..."
        else "Modify Run Configuration ($label)..."
        return object : AnAction(text, text, AllIcons.General.GearPlain) {
            init {
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                template.configureDataCase(generator.file, generator.suite, generator.prefix, id)
                val settings = reuseOrCreate(project, template) ?: return
                // A temporary configuration must be known to the manager
                // before it can be selected; the settings dialog then opens
                // with THIS configuration already chosen.
                val runManager = RunManager.getInstance(project)
                if (settings !in runManager.allSettings) {
                    runManager.addConfiguration(settings)
                }
                runManager.selectedConfiguration = settings
                com.intellij.execution.actions.EditRunConfigurationsAction().actionPerformed(event)
            }
        }
    }

    private fun dataAction(
        element: PsiElement,
        generator: LuaUnitDataSources.Generator,
        id: String,
        label: String?,
        debug: Boolean
    ): AnAction {
        val text = when {
            debug && label != null -> "Debug $label"
            debug -> "Debug"
            label != null -> "Run $label"
            else -> "Run"
        }
        return object : AnAction(text, text, icon(debug)) {
            init {
                // Without mnemonic parsing: the default setText eats every
                // "_" of the text (a mnemonic marker), and the file names
                // are full of them ("Run decode_dtcap").
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                template.configureDataCase(generator.file, generator.suite, generator.prefix, id)
                val settings = reuseOrCreate(project, template) ?: return
                ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
            }
        }
    }

    /** Builds the configuration of [element] and runs it. */
    private fun execute(project: Project, element: PsiElement, debug: Boolean) {
        val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
        val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration ?: return
        if (!LuaUnitRunConfigurationProducer().configureFor(element, template)) {
            return
        }
        val settings = reuseOrCreate(project, template) ?: return
        ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
    }

    /**
     * The configuration of the previous run of the same scope, or a new
     * temporary one.
     *
     * <p>A configuration of an older build may carry the same scope with an
     * empty `--pattern`: reusing it as is would quietly widen the run to every
     * case of the file, so the scope fields of this click are copied into the
     * reused configuration too.
     */
    private fun reuseOrCreate(
        project: Project,
        template: LuaUnitRunConfiguration
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
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

    /** The Run entry keeps the green triangle, the Debug one shows the bug. */
    private fun icon(debug: Boolean): javax.swing.Icon =
        if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute

    private fun executor(debug: Boolean) =
        if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
}
