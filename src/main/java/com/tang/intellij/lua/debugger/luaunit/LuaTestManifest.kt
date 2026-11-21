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
 * The test files listed by a `run_tests.lua` manifest, grouped the way the file
 * groups them.
 *
 * <p>That array literal is the single source of truth for the suite: the same
 * list drives a plain `lua tests/run_tests.lua` run, so a new test file is
 * registered once. Both quote styles are recognized (`"tests/a.lua"` and
 * `'tests/b.lua'`); a `--` comment, whole-line or trailing, hides an entry. A
 * whole-line comment above a run of entries is a section title and becomes a
 * node of the tree:
 *
 * ```lua
 * --SS7
 * 'tests/ss7/test_decode_ss7.lua',
 * ```
 *
 * <p>A file listed twice is taken once, at the place of its first occurrence.
 */
object LuaTestManifest {

    /** One section of the manifest: its title (empty when untitled) and files. */
    data class Group(val name: String, val files: List<Path>)

    private val ENTRY = Regex("([\"'])([^\"']+\\.lua)\\1")
    private val SECTION = Regex("--+\\s*(.+?)\\s*$")

    /** Manifest file names looked up in the directory of the selected line. */
    val MANIFEST_NAMES = listOf("run_tests.lua")

    /**
     * The manifest file that governs [file]: the file itself when it is a
     * manifest, otherwise a manifest in one of its parent directories. The
     * closest one wins — a directory may have its own subset manifest.
     */
    fun findManifest(file: Path): Path? {
        var directory: Path? = if (Files.isDirectory(file)) file else file.parent
        var depth = 0
        while (directory != null && depth < 32) {
            if (Files.isRegularFile(file) && MANIFEST_NAMES.any { directory.resolve(it) == file }) {
                return file
            }
            for (name in MANIFEST_NAMES) {
                val candidate = directory.resolve(name)
                if (Files.isRegularFile(candidate)) {
                    return candidate
                }
            }
            directory = directory.parent
            depth++
        }
        return null
    }

    /**
     * Entries of the manifest, grouped by section.
     *
     * @throws IllegalArgumentException when the manifest cannot be read
     */
    fun groups(manifest: Path): List<Group> {
        val lines = try {
            Files.readAllLines(manifest)
        } catch (e: Exception) {
            throw IllegalArgumentException("Cannot read $manifest: ${e.message}", e)
        }
        // Entries are project-relative ("tests/ss7/x.lua"), even when the
        // manifest sits in a subdirectory such as tests/. They resolve against
        // the project root, not against the manifest's own directory.
        val directory = LuaUnitProjectRoot.of(manifest) ?: manifest.parent ?: manifest.toAbsolutePath().parent

        val sections = LinkedHashMap<String, MutableList<Path>>()
        val seen = HashSet<Path>()
        var current = ""

        for (raw in lines) {
            var line = raw
            val comment = line.indexOf("--")
            if (comment >= 0 && line.substring(0, comment).isBlank()) {
                val title = SECTION.find(line.substring(comment))
                val text = title?.groupValues?.get(1)?.trim()
                // A bare "--" is a separator, not a title: it must not rename
                // the section the entries around it belong to.
                if (!text.isNullOrEmpty()) {
                    current = text
                }
                line = line.substring(0, comment)
            }

            for (entry in ENTRY.findAll(line)) {
                val path = directory.resolve(entry.groupValues[2]).normalize()
                if (seen.add(path)) {
                    sections.getOrPut(current) { ArrayList() }.add(path)
                }
            }
        }

        return sections.map { (name, files) -> Group(name, files) }
    }

    /** Entries of the manifest in declaration order, sections flattened. */
    fun files(manifest: Path): List<Path> = groups(manifest).flatMap { it.files }
}
