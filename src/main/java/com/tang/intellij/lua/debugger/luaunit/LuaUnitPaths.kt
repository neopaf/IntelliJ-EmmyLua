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

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * The file references of the tests console, in the form the run output shows.
 *
 * <p>A run loads a suite by its relative path, so a traceback names the file
 * the way the project does ("tests/psi/x.lua"). The debugger loads it by its
 * absolute path — a breakpoint is sent as an absolute path and a relative
 * chunk name would never match it — and Lua trims every chunk name longer than
 * `LUA_IDSIZE` in `debug.traceback` to "..." plus its last characters. The
 * same file then reads ".../paf/.../tests/psi/x.lua": truncated, and no
 * hyperlink can resolve it.
 *
 * <p>This object brings such a reference back to the project-relative form.
 * A truncated reference kept the tail of the path, and the tail of a long
 * project path also ends with the project-relative part ("/tests/psi/x.lua"),
 * so trying the tail against the project base recovers the file.
 */
object LuaUnitPaths {

    /**
     * A file reference: an absolute path, a `file://` URL or a
     * `...`-truncated one. A path may contain dots, slashes and spaces.
     */
    private val REFERENCE =
        Regex("""(?:\.\.\.)?[^\s"'()<>,;:]+\.(?:lua|json|txt|xml|yaml|yml)(?:\.txt)?""")

    /** The project-relative form of [file], or null when it is outside the project. */
    fun relative(project: Project, file: VirtualFile): String? {
        val base = project.basePath ?: return null
        val path = file.path
        if (!path.startsWith(base)) {
            return null
        }
        val rest = path.substring(base.length)
        // The base itself is never a file; a file next to it starts with '/'.
        if (!rest.startsWith("/")) {
            return null
        }
        return rest.substring(1)
    }

    /**
     * The file a reference points at, or null. An absolute path is taken as it
     * is; a `...`-truncated one is looked up by its tail against the project
     * base, which is the part the truncation cut away.
     */
    fun findFile(project: Project, reference: String): VirtualFile? {
        val path = reference.trim()
            .removePrefix("file://")
            .removePrefix("file:")
            .substringBefore('#')
        if (path.isEmpty()) {
            return null
        }
        val fileSystem = LocalFileSystem.getInstance()
        fileSystem.findFileByPath(path)?.let { return it }
        if (!path.startsWith("...")) {
            return null
        }
        val tail = path.removePrefix("...")
        val base = project.basePath ?: return null
        // The tail may carry the truncated head of the base itself
        // ("...paf/Documents/..."): every slash in it is a possible start of
        // the part that begins the project-relative path.
        var at = tail.indexOf('/')
        if (at < 0) {
            return null
        }
        while (at >= 0) {
            fileSystem.findFileByPath(base + tail.substring(at))?.let { return it }
            at = tail.indexOf('/', at + 1)
        }
        return null
    }

    /**
     * [text] with its file references in the project-relative form. Every
     * reference that does not resolve to a file of the project — a LuaUnit or
     * an interpreter path outside it — stays as it was.
     */
    fun normalize(project: Project, text: String): String {
        if (text.isEmpty() || !hasReference(text)) {
            return text
        }
        val out = StringBuilder(text.length)
        var last = 0
        for (match in REFERENCE.findAll(text)) {
            val file = findFile(project, match.value) ?: continue
            val relative = relative(project, file) ?: continue
            if (relative == match.value) {
                continue
            }
            out.append(text, last, match.range.first).append(relative)
            last = match.range.last + 1
        }
        if (last == 0) {
            return text
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** A cheap pre-check: most output lines name no file at all. */
    private fun hasReference(text: String): Boolean =
        text.contains(".lua") || text.contains(".json") || text.contains(".txt") ||
            text.contains(".xml") || text.contains(".yaml") || text.contains(".yml")
}
