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

import com.tang.intellij.lua.debugger.LuaPathLiterals

import com.intellij.execution.configurations.GeneralCommandLine
import com.tang.intellij.lua.psi.LuaFileUtil

/**
 * The Lua runner script the plugin ships and the Lua process it starts.
 *
 * <p>The runner lives inside the plugin (`luaunit/emmylua_luaunit.lua`), not in
 * the user's project: it is the same file for every project and it must not be
 * shadowed by one. `emmylua_diff.lua` is looked up next to it, so the runner
 * adds its own directory to `package.path`.
 */
object LuaUnitRunnerScript {

    const val RUNNER = "luaunit/emmylua_luaunit.lua"

    /** Separates the exact case names of a rerun inside one `--cases` value. */
    const val CASE_SEPARATOR: String = "\u0001"

    /**
     * Absolute path of the runner script.
     *
     * <p>The Lua scripts ship as a jar of their own (`lib/luaunit.jar`), so the
     * platform leaves a real file under `lib/` next to the plugin's main jar.
     * The class loader path (`plugin/classes/...`) only exists in a development
     * sandbox, where the scripts are not packed anywhere; both are tried.
     */
    fun path(): String {
        val resolved = resolve(RUNNER) ?: RUNNER
        com.intellij.openapi.diagnostic.Logger.getInstance(LuaUnitRunnerScript::class.java)
            .info("LuaUnit runner: " + resolved)
        return resolved
    }

    /** Directory of the runner; it is also the emmy diff module's directory. */
    fun directory(): String? = extractDirectory("luaunit")?.absolutePath

    /** Path of the mobdebug module the plugin bundles. */
    fun mobdebugDirectory(): String? = extractDirectory("debugger/mobdebug")?.absolutePath

    /** The plugin directory as the platform sees it, from the loaded class. */
    private fun pluginDirectory(): java.io.File? {
        val pluginXml = LuaUnitRunnerScript::class.java.classLoader
            .getResource("META-INF/plugin.xml")
            ?: return null
        return when (pluginXml.protocol) {
            "file" -> java.io.File(pluginXml.toURI()).parentFile?.parentFile?.parentFile
            "jar" -> java.io.File(
                (pluginXml.openConnection() as java.net.JarURLConnection).jarFileURL.toURI()
            ).parentFile?.parentFile
            else -> null
        }
    }

    /**
     * A file of this plugin: the classes directory of a development sandbox
     * first (a plain file, readable from Lua), the unpacked copy of the
     * plugin's jars second.
     */
    private fun resolve(relativePath: String): String? {
        val directory = pluginDirectory() ?: return null
        // Development sandbox: an unpacked classes directory.
        java.io.File(directory, "classes/$relativePath").takeIf { it.isFile }
            ?.let { return it.absolutePath }
        java.io.File(directory, relativePath).takeIf { it.isFile }
            ?.let { return it.absolutePath }
        // The runner requires `emmylua_diff.lua` next to itself, so the whole
        // scripts directory is unpacked — never the runner file alone: the
        // directory stamp would then look complete without the diff module,
        // and its require would silently fall back to a stub.
        val name = relativePath.substringAfter('/')
        return extractDirectory(relativePath.substringBefore('/'))
            ?.resolve(name)?.takeIf { it.isFile }?.absolutePath
    }

    /**
     * A directory of this plugin, unpacked out of its jars.
     *
     * <p>`luaunit/` and `debugger/mobdebug/` are put on `package.path`, and a
     * Lua package path cannot point inside a jar, so the directory that
     * actually holds the `.lua` files is returned — a real one in a development
     * sandbox, an unpacked copy otherwise.
     *
     * <p>The unpack lives in a folder stamped by the jars' modification time:
     * a new build of the plugin never lands in the folder of an old one, and
     * a partial or stale copy cannot pass for a complete one.
     */
    private fun extractDirectory(relativePath: String): java.io.File? {
        val directory = pluginDirectory() ?: return null
        // A development sandbox and an installed plugin keep their resources
        // as real folders — check them before looking inside the jars.
        java.io.File(directory, "classes/$relativePath")
            .takeIf { it.isDirectory }?.let { return it }
        java.io.File(directory, relativePath)
            .takeIf { it.isDirectory }?.let { return it }
        val stamp = pluginJars().maxOfOrNull { it.lastModified() } ?: 0L
        val base = java.io.File(System.getProperty("java.io.tmpdir"), "emmylua-luaunit-$stamp")
        val target = java.io.File(base, relativePath)
        if (target.isDirectory && target.listFiles()?.isNotEmpty() == true) {
            return target
        }
        val prefix = "$relativePath/"
        for (jar in pluginJars()) {
            java.util.zip.ZipFile(jar).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { it.name.startsWith(prefix) && !it.isDirectory }
                    .toList()
                if (entries.isEmpty()) {
                    continue
                }
                for (entry in entries) {
                    val out = java.io.File(target, entry.name.removePrefix(prefix))
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return target
            }
        }
        return null
    }

    /** The plugin's jars, our own scripts jar first. */
    private fun pluginJars(): List<java.io.File> {
        val directory = pluginDirectory() ?: return emptyList()
        val lib = java.io.File(directory, "lib")
        val jars = lib.listFiles { file -> file.name.endsWith(".jar") }?.toList() ?: return emptyList()
        return jars.sortedByDescending { it.name.startsWith("luaunit") }
    }

    /**
     * The `-e` prelude that makes breakpoints work under Debug.
     *
     * <p>Every suite is a separate Lua process, so the debugger never sees Lua
     * unless the process opts in. The prelude loads the bundled mobdebug and
     * connects it to the IDE's debugger before the runner (and therefore any
     * test file) runs; the port is the one the debug session listens on.
     */
    fun debugPrelude(port: Int, workingDir: String?): String {
        // The path goes into a Lua string: a raw Windows separator would be an
        // escape sequence there and the directory would vanish from
        // `package.path` (see [LuaPathLiterals]).
        val path = LuaPathLiterals.packagePath(listOfNotNull(mobdebugDirectory(), workingDir))
        return "package.path = '$path' .. package.path " +
                "local ok, mob = pcall(require, 'mobdebug') " +
                "if ok then mob.start('127.0.0.1', $port) else io.stderr:write('[LuaUnit] mobdebug: ' .. tostring(mob) .. '\\n') end"
    }

    /**
     * The command line of one suite: the interpreter, the runner and the
     * options of the suite (all prefixed `--`, which LuaUnit itself parses from
     * the same command line — the runner strips them in its own layer).
     */
    fun commandLine(
        configuration: LuaUnitRunConfiguration,
        file: String,
        pattern: String?,
        discover: Boolean,
        debugPort: Int?,
        caseNames: List<String> = emptyList()
    ): GeneralCommandLine {
        val workingDir = configuration.workingDirOrDefault()
        val commandLine = GeneralCommandLine(configuration.expandMacros(configuration.interpreter.trim()))
        commandLine.withWorkDirectory(workingDir)
        commandLine.withCharset(Charsets.UTF_8)

        configuration.extraArgs?.let {
            if (it.isNotBlank()) {
                commandLine.addParameters(it.trim().split(Regex("\\s+")))
            }
        }
        commandLine.withEnvironment(configuration.envs.envs)

        val runner = path()
        if (debugPort != null) {
            commandLine.addParameters("-e", debugPrelude(debugPort, workingDir))
            // Under the debugger the file is loaded by its absolute path: a
            // breakpoint is sent as an absolute path too, and a relative chunk
            // name ("@tests/x.lua") would never match it.
            val absolute = java.io.File(workingDir, file).canonicalFile
            commandLine.addParameters(runner)
            commandLine.addParameter(if (discover) "--discover=$absolute" else "--file=$absolute")
            // A case run keeps its pattern under Debug as well: without it
            // the process would run every case of the file and stop in
            // whichever one hits a breakpoint first.
            if (!discover && !pattern.isNullOrBlank()) {
                commandLine.addParameter("--pattern=$pattern")
            }
            if (!discover && caseNames.isNotEmpty()) {
                commandLine.addParameter("--cases=" + caseNames.joinToString(CASE_SEPARATOR))
            }
            return commandLine
        }
        commandLine.addParameters(runner)
        commandLine.addParameter(if (discover) "--discover=$file" else "--file=$file")
        if (!discover && !pattern.isNullOrBlank()) {
            commandLine.addParameter("--pattern=$pattern")
        }
        if (!discover && caseNames.isNotEmpty()) {
            commandLine.addParameter("--cases=" + caseNames.joinToString(CASE_SEPARATOR))
        }
        return commandLine
    }
}
