package com.tang.intellij.lua.debugger.luaunit

import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers the "LuaUnit" run configuration when a project with a LuaUnit
 * manifest is opened and the project has no such configuration yet, and makes
 * it the selected one: a user only has to press Run.
 *
 * <p>The manifest is the same one the runner uses (`run_tests.lua` in the
 * project root or in `tests/`), so a project that is not a LuaUnit suite is
 * left untouched.
 */
class LuaUnitProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val basePath = project.basePath ?: return
        val manifest = listOf(
            Path.of(basePath, "run_tests.lua"),
            Path.of(basePath, "tests", "run_tests.lua")
        ).firstOrNull { Files.isRegularFile(it) } ?: return

        // The results of the previous session are restored from the workspace
        // file, but the editors restored with the project were created before
        // the index was loaded: their fixture blocks are built here, once the
        // project is up.
        LuaUnitHighlights.refresh(project, "project opened")

        val runManager = RunManager.getInstance(project)
        val type = LuaUnitConfigurationType.getInstance()
        if (runManager.allConfigurationsList.any { it.type === type }) {
            return
        }
        val factory = type.configurationFactories.first()
        val settings = runManager.createConfiguration("All tests", factory)
        (settings.configuration as? LuaUnitRunConfiguration)?.let { configuration ->
            configuration.scopePath = manifest.toString()
            configuration.scopeKind = LuaUnitRunConfiguration.Kind.ALL
            configuration.workingDir = basePath
        }
        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings
    }
}
