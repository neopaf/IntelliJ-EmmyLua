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

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Function
import com.tang.intellij.lua.psi.LuaClassMethodDef
import com.tang.intellij.lua.psi.LuaFuncDef
import com.tang.intellij.lua.psi.LuaLiteralExpr
import com.tang.intellij.lua.psi.LuaLocalFuncDef
import com.tang.intellij.lua.psi.LuaPsiFile
import javax.swing.Icon

/**
 * The Run/Debug gutter icons of a LuaUnit test file.
 *
 * <p>The markers sit on the entry points of a run: the manifest, a test
 * function (`function TestSuite:test_x()`) and the `id = "..."` row of a
 * generated case, in a suite file as well as in a plain data module (where the
 * row declares the case of every suite that requires the module).
 *
 * <p>A plain left click runs the marker's run at once when there is exactly one
 * of it: the icon of one case (or of one scope — the whole suite, a section, a
 * file) needs no question, it is run. Only a data row that feeds more than one
 * suite has several runs behind one icon, and the click there opens the menu
 * instead. A right click always opens the menu (Run, Debug, Modify Run
 * Configuration), so the usual "run or debug?" question is asked only on
 * demand.
 *
 * <p>The entries build the configuration from the element the icon sits on
 * (through [LuaUnitRunConfigurationProducer.configureFor]) and reuse the
 * configuration of the previous run of the same scope, so the Run menu does
 * not grow a new entry on every click.
 *
 * <p>One line, one marker: the platform asks this provider for every element of
 * the file, and a marker created on both a composite node and its first leaf
 * appeared in the gutter twice, its menus glued into "Run, Debug, Run, Debug".
 * Only a leaf that starts its own anchor (the declaration, the literal of a
 * data row) may answer.
 */
class LuaUnitRunLineMarkerContributor : LineMarkerProvider {

    /**
     * What one gutter icon shows: the icon with the result, the menu entries of
     * a right click, and the entry a plain click runs ([clickAction]).
     */
    private class Marker(
        val icon: Icon,
        val actions: Array<AnAction>,
        val tooltip: Function<PsiElement, String>,
        /**
         * The entry a plain left click runs, or null when the click has to open
         * the menu: more than one case behind one icon leaves no single test to
         * run.
         */
        val clickAction: AnAction?
    )

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val marker = markerAt(element) ?: return null
        // The platform marks a file with run markers as runnable; the
        // contributor used to do it for this one.
        element.containingFile?.virtualFile?.let {
            com.intellij.execution.lineMarker.RunLineMarkerProvider.markRunnable(it, true)
        }
        return LuaUnitLineMarkerInfo(element, marker)
    }

    private fun markerAt(element: PsiElement): Marker? {
        // Leaves only: a composite node holds its first leaf, and the leaf has
        // already answered.
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
            // The manifest is parsed from the editor's copy: unsaved edits
            // must not leave the markers behind until the file is saved.
            val directory = LuaUnitProjectRoot.of(manifest)
                ?: manifest.parent ?: manifest.toAbsolutePath().parent
            if (isFirstLeaf(element)) {
                val cases = LuaUnitGutterStatus.casesBehind(
                    element.project,
                    LuaTestManifest.filesIn(file.text, directory)
                )
                return info("Run all LuaUnit tests", element, cases)
            }
            if (element is com.intellij.psi.PsiWhiteSpace || !isFirstElementOfLine(element)) {
                return null
            }
            val line = file.text.substring(0, element.textRange.startOffset).count { it == '\n' } + 1
            val title = LuaTestManifest.sectionTitleAt(file.text, directory, line)
            if (title != null) {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("manifest gutter: line=$line TITLE '$title'")
                val cases = LuaUnitGutterStatus.casesBehind(
                    element.project,
                    LuaTestManifest.sectionAt(file.text, directory, line)?.files ?: emptyList()
                )
                return scopeInfo("Run $title tests", cases) { it.configureSection(manifest, title) }
            }
            val entry = LuaTestManifest.entryAt(file.text, directory, line)
            if (entry != null) {
                com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
                    .info("manifest gutter: line=$line ENTRY $entry")
                val cases = LuaUnitGutterStatus.casesBehind(element.project, listOf(entry))
                return scopeInfo("Run ${entry.fileName}", cases) { it.configureFile(entry) }
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

        // The declaration of the suite itself ("SomeSuite = {}"): its line
        // runs the cases of that table only, not the whole file (a file may
        // hold more than one suite).
        suiteAt(element, file)?.let { name ->
            return scopeInfo(
                "Run $name",
                LuaUnitGutterStatus.casesOfSuite(element.project, path, name)
            ) { it.configureSuite(diskPath, name) }
        }

        // A case's function: "function T:test_x()" parses as LuaClassMethodDef,
        // not LuaFuncDef. Only the leaf the declaration starts with answers.
        val func: PsiElement? = PsiTreeUtil.getParentOfType(element, LuaClassMethodDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaFuncDef::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, LuaLocalFuncDef::class.java, false)
        if (func != null && isTopLevelTest(func)) {
            // A `---` doc comment is a CHILD of the definition (the binder
            // makes it the first element), so the node starts on the comment's
            // first line, not on the `function` keyword. The icon belongs to
            // the line of the declaration itself.
            if (!startsDeclaration(element, func)) {
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
        if (literal != null && LuaUnitDataRows.isEntryLiteral(literal) &&
            element.textRange.startOffset == literal.textRange.startOffset
        ) {
            val root = LuaUnitProjectRoot.of(manifest)
            val line = file.text.substring(0, literal.textRange.startOffset).count { it == '\n' } + 1
            // The marker sits on the row's *name*: the `desc` line when the
            // generator names the case by it, the `id` line otherwise. The
            // rest of the navigation (the case node, the fixtures, the result)
            // points at the same line.
            val normalized = diskPath.toAbsolutePath().normalize()
            val isCaseLine = root != null && LuaUnitTextDiscovery.casesCached(manifest, diskPath, root)
                .any { case ->
                    val source = case.source
                    val colon = source.lastIndexOf(':')
                    colon > 0 && source.substring(colon + 1).toIntOrNull() == line &&
                        java.nio.file.Path.of(source.substring(0, colon)).toAbsolutePath().normalize() == normalized
                }
            if (isCaseLine) {
                return info("Run this case", element)
            }
        }
        return null
    }

    /**
     * The name of the suite table declared on the element's line
     * (`SomeSuite = {}`, `local SomeSuite = {}`), or null. Only the first leaf
     * of the line answers: the marker must sit once.
     *
     * <p>The name is not fixed: it is the one the file's own test functions
     * are declared on (`function SomeSuite:test_x()`), see [LuaUnitSuites].
     */
    private fun suiteAt(element: PsiElement, file: LuaPsiFile): String? =
        LuaUnitSuites.declaredAt(file.text, element.textRange.startOffset)

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
    private fun scopeInfo(
        tooltip: String,
        cases: Collection<LuaUnitRunIndex.CaseResult>,
        configure: (LuaUnitRunConfiguration) -> Unit
    ): Marker {
        val run = scopeAction("Run", false, configure)
        return Marker(
            LuaUnitGutterStatus.iconOf(cases),
            arrayOf(
                run,
                scopeAction("Debug", true, configure),
                modifyScopeAction(configure)
            ),
            Function { tooltip + LuaUnitGutterStatus.suffixOf(cases) },
            // One scope, one run: a plain click runs it, no question.
            run
        )
    }

    /**
     * Opens the settings of the scope [configure] sets up. The configuration is
     * temporary until the user saves it, so the entry creates it, selects it
     * and opens the dialog with it.
     */
    private fun modifyScopeAction(configure: (LuaUnitRunConfiguration) -> Unit): AnAction {
        val text = "Modify Run Configuration..."
        return object : AnAction(text, text, AllIcons.General.GearPlain) {
            init {
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                configure(template)
                LuaUnitRunConfigurations.openSettings(project, template, event)
            }
        }
    }

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
                val settings = LuaUnitRunConfigurations.reuseOrCreate(project, template) ?: return
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

    /** The doc comment bound to [func] (it is a child of the definition), or null. */
    private fun boundComment(func: PsiElement): PsiElement? = when (func) {
        is LuaClassMethodDef -> func.comment
        is LuaFuncDef -> func.comment
        is LuaLocalFuncDef -> func.comment
        else -> null
    }

    /**
     * True when [element] is the first non-blank leaf of the declaration
     * itself — the `function` (or `local`) keyword below the doc comment. A
     * documented definition starts its text range on the comment, and a
     * marker there would sit on the prose, not on the function.
     */
    private fun startsDeclaration(element: PsiElement, func: PsiElement): Boolean {
        if (element is com.intellij.psi.PsiWhiteSpace) {
            return false
        }
        val start = boundComment(func)?.textRange?.endOffset ?: func.textRange.startOffset
        val offset = element.textRange.startOffset
        if (offset < start) {
            return false
        }
        val text = element.containingFile.text
        for (index in start until offset) {
            if (!text[index].isWhitespace()) {
                return false
            }
        }
        return true
    }

    /** One icon, a Run and a Debug entry. */
    private fun info(tooltip: String, element: PsiElement): Marker =
        info(tooltip, element, casesOnLine(element))

    /** One icon, a Run and a Debug entry, painted with the result of [cases]. */
    private fun info(
        tooltip: String,
        element: PsiElement,
        cases: Collection<LuaUnitRunIndex.CaseResult>
    ): Marker {
        val provider = Function<PsiElement, String> { tooltip + LuaUnitGutterStatus.suffixOf(cases) }
        val run = runAction(element, false)
        return Marker(
            LuaUnitGutterStatus.iconOf(cases),
            arrayOf(run, runAction(element, true), modifyElementAction(element)),
            provider,
            // The icon names one case: a plain click runs it.
            run
        )
    }

    /** The cases declared on the element's line, for the result of its marker. */
    private fun casesOnLine(element: PsiElement): List<LuaUnitRunIndex.CaseResult> {
        val file = element.containingFile ?: return emptyList()
        val virtualFile = file.virtualFile ?: return emptyList()
        val document = com.intellij.psi.PsiDocumentManager.getInstance(element.project)
            .getDocument(file) ?: return emptyList()
        val offset = element.textRange.startOffset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset) + 1
        return LuaUnitGutterStatus.casesOnLine(element.project, virtualFile, document, line)
    }

    /** Opens the settings of the case [element] declares. */
    private fun modifyElementAction(element: PsiElement): AnAction {
        val text = "Modify Run Configuration..."
        return object : AnAction(text, text, AllIcons.General.GearPlain) {
            init {
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                if (!LuaUnitRunConfigurationProducer().configureFor(element, template)) {
                    return
                }
                LuaUnitRunConfigurations.openSettings(project, template, event)
            }
        }
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
    ): Marker? {
        val literal = PsiTreeUtil.getParentOfType(element, LuaLiteralExpr::class.java, false) ?: return null
        if (!LuaUnitDataRows.isEntryLiteral(literal) || element.textRange.startOffset != literal.textRange.startOffset) {
            return null
        }
        // The `desc` of a row names the case: the marker sits there, and the
        // `id` literal of the same row stays plain data.
        if (!LuaUnitDataRows.isDescField(literal) && LuaUnitDataRows.hasDescSibling(literal)) {
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
        val expected = LuaUnitLineCases.expected(generators, id)
        val tooltip = "Run " + expected.joinToString("; ") { case ->
            case.caseName + " (" + case.file.fileName + ")"
        }
        val cases = casesOnLine(literal)
        // The cases of the line that did run in its newest run decide the
        // colour; the dot says that some of them did not, and the tooltip names
        // which.
        val latest = LuaUnitLineCases.casesInLatestRun(cases)
        val partial = LuaUnitLineCases.partial(expected, cases)
        val tail = if (partial) {
            " — " + LuaUnitLineCases.coverageText(expected, cases)
        } else {
            LuaUnitGutterStatus.suffixOf(latest)
        }
        // One Run/Debug pair per generator, labelled by the generator's file
        // (its leading "test_" says nothing here) and separated by a rule,
        // the way the platform separates unrelated actions.
        val actions = ArrayList<AnAction>()
        // A row that feeds several suites has one run of its own: all the
        // cases it declares, in one tab. A plain click runs exactly that.
        val runAll = if (generators.size > 1) dataCasesAction(element, expected, false) else null
        if (runAll != null) {
            actions.add(runAll)
            actions.add(dataCasesAction(element, expected, true))
            actions.add(com.intellij.openapi.actionSystem.Separator.create())
        }
        var singleRun: AnAction? = runAll
        for ((index, generator) in generators.withIndex()) {
            if (index > 0) {
                actions.add(com.intellij.openapi.actionSystem.Separator.create())
            }
            // The label keeps the file's "_" — the action text is stored
            // without mnemonic parsing below, so it is not eaten.
            val pair = if (generators.size == 1) null
                else generator.file.fileName.toString().removeSuffix(".lua").removePrefix("test_")
            val run = dataAction(element, generator, id, pair, false)
            actions.add(run)
            actions.add(dataAction(element, generator, id, pair, true))
            actions.add(modifyAction(element, generator, id, pair))
            if (generators.size == 1) {
                singleRun = run
            }
        }
        return Marker(
            LuaUnitGutterStatus.iconOf(latest, partial),
            actions.toTypedArray(),
            Function { tooltip + tail },
            // One row, one case: a plain click runs it. A row that feeds
            // several suites has no single test, so the click runs all of
            // them — the marker names every case in its menu and tooltip.
            singleRun
        )
    }

    /** Runs every case a data row declares: one process per generator file. */
    private fun dataCasesAction(
        element: PsiElement,
        expected: List<LuaUnitLineCases.ExpectedCase>,
        debug: Boolean
    ): AnAction {
        val text = if (debug) "Debug all ${expected.size} cases" else "Run all ${expected.size} cases"
        return object : AnAction(text, text, icon(debug)) {
            init {
                templatePresentation.setText(text, false)
            }

            override fun actionPerformed(event: AnActionEvent) {
                val project = event.project ?: return
                val factory = LuaUnitConfigurationType.getInstance().configurationFactories.first()
                val template = factory.createTemplateConfiguration(project) as? LuaUnitRunConfiguration
                    ?: return
                template.configureDataCases(expected.map { case -> case.file to case.caseName })
                val settings = LuaUnitRunConfigurations.reuseOrCreate(project, template) ?: return
                ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
            }
        }
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
                LuaUnitRunConfigurations.openSettings(project, template, event)
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
                val settings = LuaUnitRunConfigurations.reuseOrCreate(project, template) ?: return
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
        val settings = LuaUnitRunConfigurations.reuseOrCreate(project, template) ?: return
        ProgramRunnerUtil.executeConfiguration(settings, executor(debug))
    }

    /** The Run entry keeps the green triangle, the Debug one shows the bug. */
    private fun icon(debug: Boolean): javax.swing.Icon =
        if (debug) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute

    private fun executor(debug: Boolean) =
        if (debug) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()

    /**
     * One gutter icon of this provider. The platform's own renderer clicks
     * through to the only entry or opens the menu, and it never knows which of
     * the entries is "the" run; this one does: a plain click runs
     * [Marker.clickAction] when the marker names exactly one test, and a right
     * click always asks with the whole menu.
     */
    private class LuaUnitLineMarkerInfo(
        element: PsiElement,
        private val marker: Marker
    ) : LineMarkerInfo<PsiElement>(
        element,
        element.textRange,
        marker.icon,
        marker.tooltip,
        null,
        GutterIconRenderer.Alignment.CENTER,
        java.util.function.Supplier<String> { "" }
    ) {

        override fun createGutterRenderer(): GutterIconRenderer = Renderer(this, marker)

        private class Renderer(
            info: LineMarkerInfo<PsiElement>,
            private val marker: Marker
        ) : LineMarkerInfo.LineMarkerGutterIconRenderer<PsiElement>(info) {

            override fun getClickAction(): AnAction? = marker.clickAction

            override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(*marker.actions)
        }
    }
}
