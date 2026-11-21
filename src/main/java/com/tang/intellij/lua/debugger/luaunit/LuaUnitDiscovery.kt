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

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfoRt
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Discovery of the suite tree: which files run, and which cases each of them
 * declares.
 *
 * <p>The manifest decides the files and their sections; a case's LuaUnit name
 * and its declaration line are read from the file itself by a Lua process that
 * loads the file but runs nothing (`--discover`). The IDE could not parse Lua
 * test files on its own: the case set is created by the file's own code (a
 * loop over a data table is the normal way to build cases in this project), so
 * only running the file's top level gives the real list.
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
                item.ignored, item.description
            )
        }
    }

    // ---------------------------------------------------------------- lua

    class Result(
        val exitCode: Int,
        val discovered: List<LuaUnitProtocol.Discovered>,
        val done: LuaUnitProtocol.Done?,
        val problems: List<String>
    )

    internal fun run(file: Path, discover: Boolean): Result {
        val relative = relativeName(file)
        val commandLine = LuaUnitRunnerScript.commandLine(
            configuration,
            relative,
            null,
            discover,
            debugPort = null
        )
        val process = commandLine.createProcess()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { pump(process.inputStream, stdout) }.apply { isDaemon = true }
        val errThread = Thread { pump(process.errorStream, stderr) }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = process.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        outThread.join(2000)
        errThread.join(2000)

        var discovered = listOf<LuaUnitProtocol.Discovered>()
        var done: LuaUnitProtocol.Done? = null
        val problems = ArrayList<String>()
        for (line in stdout.toString().split('\n')) {
            val event = LuaUnitProtocol.parseLine(line)
            when (event) {
                is LuaUnitProtocol.Event.Finished -> {
                    done = event.done
                    discovered = event.discovered
                }
                is LuaUnitProtocol.Event.CaseFailed -> problems.add(event.failure.message)
                else -> Unit
            }
        }
        if (stderr.isNotEmpty()) {
            problems.add(stderr.toString().trim())
        }
        return Result(if (finished) process.exitValue() else -1, discovered, done, problems.filter { it.isNotEmpty() })
    }

    /** The file name the runner is started with: relative to the working directory. */
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
        private const val DISCOVERY_TIMEOUT_SECONDS = 120L
    }
}

/** Reads [stream] into [target] byte by byte: the protocol is line based and
 *  a marker must never be split by a charset decoder boundary. */
private fun pump(stream: java.io.InputStream, target: StringBuilder) {
    try {
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) {
                break
            }
            target.append(String(buffer, 0, read, Charsets.UTF_8))
        }
    } catch (ignored: java.io.IOException) {
    }
}
