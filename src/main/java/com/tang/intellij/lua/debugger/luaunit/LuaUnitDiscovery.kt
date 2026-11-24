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

import java.io.File
import java.nio.file.Path

/**
 * Discovery of the suite tree: which files run, and which cases each of them
 * declares.
 *
 * <p>The manifest decides the files and their sections; a case's LuaUnit name
 * and its declaration line are read from the file's text by
 * [LuaUnitTextDiscovery]. The IDE could not parse arbitrary Lua, but the shapes
 * this project uses are plain to read, and a text scan is what the tree needs:
 * the case set may be created by the file's own code (a loop over a data table
 * is the normal way to build cases), and [LuaUnitTextDiscovery] reads those
 * generator loops.
 *
 * <p>Discovery stops after [MAX_DISCOVERY_FAILURES] files that fail to load: at
 * that point the tree would be mostly red error nodes and a run is pointless.
 */
class LuaUnitDiscovery(private val configuration: LuaUnitRunConfiguration) {

    fun discover(): LuaUnitNode.Root {
        val scope = configuration.scopeFile
            ?: throw IllegalStateException("The configuration has no test file")
        val manifest = LuaTestManifest.findManifest(scope) ?: scope

        val files: List<Pair<String, Path>> = when (configuration.scopeKind) {
            LuaUnitRunConfiguration.Kind.FILE, LuaUnitRunConfiguration.Kind.CASE ->
                listOf("" to scope)
            else -> {
                val groups = LuaTestManifest.groups(manifest)
                val filtered = when (configuration.scopeKind) {
                    LuaUnitRunConfiguration.Kind.SECTION ->
                        groups.filter { it.name.equals(configuration.scopeName, ignoreCase = true) }
                    else -> groups
                }
                val list = ArrayList<Pair<String, Path>>()
                for (group in filtered) {
                    for (file in group.files) {
                        list.add(group.name to file)
                    }
                }
                list
            }
        }

        val sections = ArrayList<LuaUnitNode.Section>()
        val failures = ArrayList<String>()
        for ((title, file) in files) {
            val display = displayName(file)
            val cases = discoverCases(file, display, failures)
            sections.add(LuaUnitNode.Section(title, listOf(LuaUnitNode.Suite(file, display, cases))))
            if (failures.size >= MAX_DISCOVERY_FAILURES) {
                break
            }
        }

        // Files of one manifest section are grouped back together: the loop
        // above keeps them in place, this merges the adjacent ones.
        val merged = ArrayList<LuaUnitNode.Section>()
        for (section in sections) {
            val last = merged.lastOrNull()
            if (last != null && last.title == section.title) {
                merged[merged.size - 1] = LuaUnitNode.Section(section.title, last.suites + section.suites)
            } else {
                merged.add(section)
            }
        }
        if (merged.isEmpty()) {
            throw IllegalStateException("No test files found in $manifest")
        }
        com.intellij.openapi.diagnostic.Logger.getInstance(LuaUnitDiscovery::class.java).info(
            "LuaUnit discovery: ${merged.size} sections, ${merged.sumOf { it.suites.size }} suites, " +
                "${merged.sumOf { s -> s.suites.sumOf { it.cases.size } }} cases, failures=$failures"
        )
        return LuaUnitNode.Root(manifest, merged)
    }

    private fun discoverCases(file: Path, display: String, failures: MutableList<String>): List<LuaUnitNode.Case> {
        // The cases are read from the files' text on the IDE side: starting a
        // Lua process per file made the discovery of the whole manifest take
        // seconds for what is a plain text scan.
        val root = LuaUnitProjectRoot.of(file) ?: file.parent
        val cases = try {
            LuaUnitTextDiscovery.cases(LuaTestManifest.findManifest(file) ?: file, file, root)
        } catch (e: Exception) {
            failures.add(display)
            consoleError(display, e.message ?: "The suite could not be discovered")
            return listOf(LuaUnitNode.Case(file.toString(), "Discovery failed", null))
        }
        return cases.map { item ->
            val dot = item.name.lastIndexOf('.')
            var displayName = if (dot >= 0) item.name.substring(dot + 1) else item.name
            // The "test_" prefix carries no information in the tree: drop it.
            if (displayName.startsWith("test_")) {
                displayName = displayName.removePrefix("test_")
            }
            LuaUnitNode.Case(
                item.name, displayName, LuaUnitProtocol.parseSource(item.source),
                item.ignored, item.description, item.idLine > 0
            )
        }
    }

    /** The file name the suite node is named with: relative to the working directory. */
    private fun relativeName(file: Path): String {
        val workingDir = configuration.workingDirOrDefault()
        val absolute = file.toAbsolutePath().normalize()
        if (workingDir == null) {
            return absolute.toString()
        }
        return try {
            File(workingDir).toPath().toAbsolutePath().normalize().relativize(absolute).toString().replace('\\', '/')
        } catch (e: IllegalArgumentException) {
            absolute.toString()
        }
    }

    /** "tests/ss7/test_ss7_common.lua": the suite node's name. */
    private fun displayName(file: Path): String = relativeName(file)

    private fun consoleError(display: String, message: String) {
        // Discovery reports go to the IDE log; a file that cannot be discovered
        // becomes a single red case in its suite (see discoverCases).
        com.intellij.openapi.diagnostic.Logger.getInstance(LuaUnitDiscovery::class.java)
            .warn("LuaUnit discovery failed for $display: $message")
    }

    companion object {
        private const val MAX_DISCOVERY_FAILURES = 5
    }
}
