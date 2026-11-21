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

import java.nio.file.Files
import java.nio.file.Path

/**
 * The directory a LuaUnit suite runs from.
 *
 * <p>Test files are started by a project-relative name (`tests/ss7/x.lua`) and
 * their modules live in the project root (`./diam_message.lua`), so the
 * working directory must be that root. It is the first parent of the scope that
 * contains a manifest, a `tests/` directory or a Lua module `require` calls
 * `diam_message`: any of the three identifies the root reliably enough, and a
 * project without all of them falls back to the configured working directory.
 */
object LuaUnitProjectRoot {

    private const val MARKER_MODULE = "diam_message.lua"

    fun of(start: Path): Path? {
        // The start itself first, then its parents, closest first.
        // Start from the containing directory: a scope is a file as often as
        // it is a directory.
        val startDirectory = if (Files.isDirectory(start)) start else start.parent
        var directory: Path? = startDirectory?.toAbsolutePath()?.normalize()
        var manifestRoot: Path? = null
        var depth = 0
        while (depth < 32) {
            val current = directory ?: break
            // A module named by require() without a path resolves against the root.
            if (Files.isRegularFile(current.resolve(MARKER_MODULE))) {
                return current
            }
            if (hasManifest(current)) {
                // A manifest in a subdirectory (e.g. tests/run_tests.lua) lists
                // entries relative to the project root: the root is its parent,
                // but keep it as a fallback in case no marker is found above.
                if (manifestRoot == null) {
                    manifestRoot = current.parent ?: current
                }
            }
            if (Files.isDirectory(current.resolve("tests"))) {
                return current
            }
            directory = current.parent
            depth++
        }
        return manifestRoot
    }

    /** True when [directory] holds a manifest file. */
    private fun hasManifest(directory: Path): Boolean =
        LuaTestManifest.MANIFEST_NAMES.any { Files.isRegularFile(directory.resolve(it)) }
}
