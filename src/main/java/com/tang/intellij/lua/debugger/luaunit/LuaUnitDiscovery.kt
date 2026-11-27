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
                // A data-row run names the exact cases: only the files that
                // host their generators have to be discovered, not the whole
                // manifest (which may hold dozens of suites).
                val selected = selectedFiles()
                val narrowed = if (selected.isEmpty()) groups else groups
                    .map { group -> LuaTestManifest.Group(group.name, group.files.filter { it.normalized() in selected }) }
                    .filter { group -> group.files.isNotEmpty() }
                    // A stored configuration may name files the manifest no
                    // longer lists: the walk then falls back to the manifest,
                    // and the cases of the run are still filtered by name.
                    .ifEmpty { groups }
                val filtered = when (configuration.scopeKind) {
                    LuaUnitRunConfiguration.Kind.SECTION ->
                        narrowed.filter { it.name.equals(configuration.scopeName, ignoreCase = true) }
                    else -> narrowed
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

    /** The files of the exact cases a data-row run names. */
    private fun selectedFiles(): Set<Path> {
        if (configuration.selectedCases.isEmpty()) {
            return emptySet()
        }
        return configuration.selectedCases.mapNotNullTo(HashSet()) { entry ->
            try {
                Path.of(entry.substringBefore('\u0000')).toAbsolutePath().normalize()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun Path.normalized(): Path = try {
        toAbsolutePath().normalize()
    } catch (e: Exception) {
        this
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
            // The case's own name comes from the parser, not from cutting the
            // full name at a dot: a generated case's row may itself contain
            // dots (`1.1.1 ... bypass.mode=full`), and the last one would have
            // cut away the beginning of the name.
            LuaUnitNode.Case(
                item.name, displayName(item.shortName), LuaUnitProtocol.parseSource(item.source),
                item.ignored, item.description, item.idLine > 0, item.owner
            )
        }
    }

    /**
     * The name shown in the tree: the `test_` prefix carries no information
     * there, and a CamelCase test name ("testUnpackRadius") drops the bare
     * "test" as well — the tree reads better and the case is still the same.
     * Anything else ("testing_foo", or the name "test" itself) is left alone:
     * there the prefix is part of the name. Only the shown name changes, a
     * copy of the case still carries the declared name.
     */
    private fun displayName(name: String): String = when {
        name.startsWith("test_") -> name.removePrefix("test_")
        name.length > 4 && name.startsWith("test") && name[4].isUpperCase() -> name.substring(4)
        else -> name
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
