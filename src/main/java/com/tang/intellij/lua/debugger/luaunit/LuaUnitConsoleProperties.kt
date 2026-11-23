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

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.SMStacktraceParser
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.pom.Navigatable
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.psi.LuaFileUtil
import javax.swing.Icon

/**
 * Console properties of a LuaUnit run.
 *
 * <p>The locator is what makes a tree node navigable: a double-click on a case
 * or a suite opens the `.lua` file at the declaration line (a case generated in
 * a loop points at the data entry it was built from). The stack trace parser
 * links `file.lua:line` references inside failure traces the same way.
 */
class LuaUnitConsoleProperties(
    private val configuration: LuaUnitRunConfiguration,
    executor: Executor
) : SMTRunnerConsoleProperties(configuration, FRAMEWORK_NAME, executor), SMStacktraceParser {

    init {
        // The predefined filters make `path/file.lua:line` references in the
        // output clickable (the console turns them into IDE hyperlinks) and
        // hide the framework's own noise from the console.
        setUsePredefinedMessageFilter(true)
        isIdBasedTestTree = false
        isPrintTestingStartedTime = false
        // The tests statistics panel (the "Tests passed: N" block), the way a
        // JUnit run shows it above the tree.
        setIfUndefined(com.intellij.execution.testframework.TestConsoleProperties.SHOW_STATISTICS, true)
        setIfUndefined(com.intellij.execution.testframework.TestConsoleProperties.SHOW_INLINE_STATISTICS, true)
        // The platform hides the passed tests by default: a LuaUnit run would
        // open to bare groups with "(0)" counters and look broken. The filter
        // stays in the toolbar — a user who wants the failures only can flip it.
        setIfUndefined(com.intellij.execution.testframework.TestConsoleProperties.HIDE_PASSED_TESTS, false)
    }

    override fun getTestLocator(): SMTestLocator = LuaUnitTestLocator()

    /**
     * The "Rerun Failed Tests" action is not created through this platform
     * hook: nothing calls it for a custom console. The run state puts the
     * action (with the console it belongs to) into the tab's toolbar itself —
     * see [LuaUnitTestConsole.rerunFailedAction].
     */

    /**
     * A failed node opens the assertion that broke — the topmost frame of the
     * trace — for a plain test. A generated case keeps its data row: the
     * assertion there is a line every row shares.
     */
    override fun getErrorNavigatable(location: Location<*>, stackTrace: String): Navigatable? {
        val project = location.project
        val virtualFile = location.virtualFile ?: return null
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(virtualFile) ?: return null
        val element = location.psiElement ?: return null
        val line = document.getLineNumber(element.textOffset) + 1
        val index = LuaUnitRunIndex.getInstance(project)
        val path = LuaUnitRunIndex.canonical(virtualFile.path)
        val case = index.casesDeclaredIn(path)
            .firstOrNull { it.failed && it.declarationLine == line } ?: return null
        if (case.generated) {
            // A generated case failed in the body of its generator, but the
            // user double-clicked the data row: the line that belongs to this
            // case is the field the assertion named (`SY_SLA_2001`). A row
            // without such a field keeps its own `id` line.
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
                ?: return null
            val attribute = LuaUnitDataRows.namedFieldLine(psiFile, document, line, case.message)
            if (attribute <= 0 || attribute == line) {
                return null
            }
            return com.intellij.openapi.fileEditor.OpenFileDescriptor(
                project, virtualFile, attribute - 1, 0
            )
        }
        if (case.errorLine <= 0) {
            return null
        }
        if (case.errorFile == case.declarationFile && case.errorLine == case.declarationLine) {
            return null
        }
        val errorFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(case.errorFile) ?: return null
        return com.intellij.openapi.fileEditor.OpenFileDescriptor(
            project, errorFile, (case.errorLine - 1).coerceAtLeast(0), 0
        )
    }

    companion object {
        const val FRAMEWORK_NAME = "LuaUnit"
    }
}

/**
 * Turns "file:line" references into [Location]s of the project.
 *
 * <p>A LuaUnit case has no PSI element of its own: its location is the line of
 * the file it was declared on, which is exactly what a double-click should open.
 */
class LuaUnitTestLocator : SMTestLocator {

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope
    ): List<Location<*>> {
        val location = resolve(path, project)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("getLocation protocol=$protocol path=$path resolved=${location != null}")
        return listOfNotNull(location)
    }

    /** The location of a `file:line` (or a bare file) reference, or null. */
    fun resolve(reference: String, project: Project): Location<*>? {
        if (reference.isBlank()) {
            return null
        }
        val file: String
        val line: Int
        val colon = reference.lastIndexOf(':')
        val parsedLine = if (colon > 0) reference.substring(colon + 1).trim().toIntOrNull() else null
        if (parsedLine != null) {
            // "file:///abs/path.lua:5" and a plain "/abs/path.lua:5" both
            // resolve to the same file. Tolerate a missing line as well.
            file = reference.substring(0, colon).removePrefix("file://").removePrefix("file:")
            line = parsedLine
        } else {
            file = reference.removePrefix("file://").removePrefix("file:")
            line = 1
        }
        val virtualFile = findFile(project, file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        // The PSI element at the case's own line, not the file: the platform
        // builds the "Run 'X'" / "Debug 'X'" entry of the tree from the
        // location's element, and a whole-file element would start the entire
        // suite instead of the case the node stands for.
        val element = elementAtLine(psiFile, line) ?: psiFile
        return LuaUnitSourceLocation(element, project, virtualFile, (line - 1).coerceAtLeast(0))
    }

    /** The first element of [line] (1-based), or null when the file has none such. */
    private fun elementAtLine(psiFile: com.intellij.psi.PsiFile, line: Int): com.intellij.psi.PsiElement? {
        val text = try {
            psiFile.text
        } catch (e: Exception) {
            return null
        }
        var offset = 0
        var current = 1
        while (current < line && offset < text.length) {
            if (text[offset] == '\n') {
                current++
            }
            offset++
        }
        if (current != line) {
            return null
        }
        while (offset < text.length && (text[offset] == ' ' || text[offset] == '\t')) {
            offset++
        }
        return psiFile.findElementAt(offset)
    }

    /** A file reference from a trace or a declaration, also for .lua.txt files. */
    fun findFile(project: Project, path: String): VirtualFile? {
        val cleaned = path.trim().removePrefix("at ").trim()
            .removePrefix("fixture(").removeSuffix(")")
            .substringBefore('(')
        // An absolute path (what the tree node carries) is resolved directly;
        // the project-relative lookup is kept for trace frames.
        val absolute = java.io.File(cleaned)
        if (absolute.isAbsolute) {
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(cleaned)
            if (file != null) {
                return file
            }
            val withoutTxt = absolute.path.removeSuffix(".lua.txt")
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(withoutTxt)?.let { return it }
        }
        return LuaFileUtil.findFile(project, cleaned)
            ?: LuaFileUtil.findFile(project, cleaned.removeSuffix(".lua.txt"))
            // The runner reports paths relative to the project root ("tests/ss7/x.lua").
            ?: project.basePath?.let { base ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath("$base/$cleaned")
            }
            ?: project.basePath?.let { base ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath("$base/${cleaned.removeSuffix(".lua.txt")}")
            }
    }

    /** The first `path.lua:line` reference of a trace, as a location. */
    fun parseFrame(trace: String, project: Project): Location<*>? {
        // Any file, not only .lua: a fixture frame points at a .json/.txt data
        // file that a click must open too.
        val pattern = Regex("([^\\s(]+\\.(?:lua|json|txt|xml|yaml|yml)(?:\\.txt)?):(\\d+)")
        for (match in pattern.findAll(trace)) {
            val location = resolve(match.groupValues[1] + ":" + match.groupValues[2], project)
            if (location != null) {
                return location
            }
        }
        return null
    }
}

/**
 * A location on a line of a Lua file. [PsiLocation] does not carry a line, so
 * the navigatable is overridden to open the file at the line the case lives on.
 */
private class LuaUnitSourceLocation(
    element: PsiElement,
    private val projectRef: Project,
    private val file: VirtualFile,
    private val line: Int
) : PsiLocation<PsiElement>(element) {

    override fun getNavigatable(): Navigatable {
        return com.intellij.openapi.fileEditor.OpenFileDescriptor(projectRef, file, line, 0)
    }

    override fun toString(): String = "${file.path}:${line + 1}"
}
