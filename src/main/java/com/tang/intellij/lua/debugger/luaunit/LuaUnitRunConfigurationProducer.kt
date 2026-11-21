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

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Run suite" / "Run case" entries for LuaUnit.
 *
 * <p>Inside a `run_tests.lua` the context is the whole suite: a "Run all tests"
 * entry appears on the first line and at any position of the file. In a test
 * file listed by such a manifest (or in a file of a directory that has one),
 * the context narrows to the test function or the `id = "..."` data entry the
 * caret is on — the case; elsewhere in the file, the whole file.
 *
 * <p>The run configuration is reused when one for the same scope already
 * exists (by [isConfigurationFromContext]), so the entry reuses the tab and
 * the settings of the previous run instead of piling up configurations.
 */
class LuaUnitRunConfigurationProducer : LazyRunConfigurationProducer<LuaUnitRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        LuaUnitConfigurationType.getInstance().configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: LuaUnitRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean = configureFor(sourceElement.get(), configuration)

    /**
     * Fills [configuration] for the element under the caret. The gutter marker
     * uses it too, so a click on the icon and the Alt+Enter entry build the
     * same configuration.
     */
    fun configureFor(element: PsiElement, configuration: LuaUnitRunConfiguration): Boolean {
        val file = element.containingFile ?: return false
        if (!file.virtualFile?.extension.equals("lua", ignoreCase = true)) {
            return false
        }
        val path = file.virtualFile ?: return false
        val diskPath = Path.of(path.path)
        val manifest = LuaTestManifest.findManifest(diskPath) ?: return false

        if (manifest == diskPath || isManifestName(path.name)) {
            configuration.configureScope(manifest, LuaUnitRunConfiguration.Kind.ALL, null, null)
            configuration.name = "All tests"
            return true
        }

        val fileInManifest = try {
            LuaTestManifest.files(manifest).any { it.toAbsolutePath().normalize() == diskPath.toAbsolutePath().normalize() }
        } catch (e: Exception) {
            false
        }
        if (!fileInManifest) {
            return false
        }

        val caseName = LuaUnitCaseAtCaret.find(file, element)
        com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            .info("configureFor caseAtCaret=${caseName?.name} pattern=${caseName?.pattern} element=${element.text.take(40)}")
        if (caseName != null) {
            // scopeName must be the FULL case name ("TestSuite.test_x"): the
            // tree of a one-case run matches its node by it.
            configuration.configureScope(diskPath, LuaUnitRunConfiguration.Kind.CASE, caseName.pattern, caseName.pattern)
            configuration.name = caseName.name
        } else {
            configuration.configureScope(diskPath, LuaUnitRunConfiguration.Kind.FILE, null, null)
            configuration.name = path.name
        }
        return true
    }

    override fun isConfigurationFromContext(configuration: LuaUnitRunConfiguration, context: ConfigurationContext): Boolean {
        val element = context.psiLocation ?: return false
        val file = element.containingFile ?: return false
        val path = Path.of(file.virtualFile?.path ?: return false)
        if (configuration.scopeFile?.toAbsolutePath()?.normalize() != path.toAbsolutePath().normalize()) {
            // Only the manifest itself counts as the context of an All-tests
            // configuration: matching every test file of the manifest would
            // put a "Run <it>" entry on every marker of the project.
            return false
        }
        if (isManifestName(path.fileName.toString())) {
            return configuration.scopeKind == LuaUnitRunConfiguration.Kind.ALL
        }
        if (configuration.scopeKind == LuaUnitRunConfiguration.Kind.FILE) {
            return true
        }
        if (configuration.scopeKind == LuaUnitRunConfiguration.Kind.ALL) {
            return false
        }
        val caseName = LuaUnitCaseAtCaret.find(file, element)
        return caseName != null && (
                caseName.pattern == configuration.scopeName ||
                        caseName.name == configuration.scopeName ||
                        caseName.pattern == configuration.casePattern)
    }

    private fun isManifestName(name: String): Boolean =
        LuaTestManifest.MANIFEST_NAMES.any { it == name }
}

private fun LuaUnitRunConfiguration.configureScope(
    path: Path,
    kind: LuaUnitRunConfiguration.Kind,
    name: String?,
    pattern: String?
) {
    scopePath = path.toString()
    scopeKind = kind
    scopeName = name
    casePattern = pattern
    workingDir = LuaUnitProjectRoot.of(path)?.toString() ?: workingDir
}
