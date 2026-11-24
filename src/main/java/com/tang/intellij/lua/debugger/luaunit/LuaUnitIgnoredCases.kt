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

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The cases a suite keeps commented out, and the lines they are declared on.
 *
 * <p>An ignored case never runs, so the run index knows nothing about it: no
 * result, no declaration, nothing the gutter could point at. The lines come
 * from the text discovery ([LuaUnitTextDiscovery]) instead: a commented-out
 * `function` (also inside a `--[[ ... ]]` block) and a commented-out data row
 * (`-- id = '...'`) are cases that never run — exactly the nodes the test tree
 * shows as ignored.
 *
 * <p>A snapshot of the whole suite is built once per change: the gutter asks
 * for an answer on every leaf of the file, and reading every test file there
 * would be the wrong side of the cost.
 */
object LuaUnitIgnoredCases {

    /** One ignored case: its LuaUnit name and the line it is declared on. */
    data class Ignored(
        val caseName: String,
        val declarationLine: Int
    )

    /** One pass over the suite, kept while its files do not change. */
    private class Snapshot(
        val stamp: Long,
        val byFile: Map<String, List<Ignored>>
    ) {
        /** When the stamps were checked last: a burst of calls reuses them. */
        var checkedAt: Long = System.currentTimeMillis()
    }

    /** The stamps are trusted for this long without being re-read. */
    private const val REFRESH_MS = 1000L

    private val snapshots = ConcurrentHashMap<Path, Snapshot>()

    /** The ignored cases declared in [file]: a suite or a data module. */
    fun ignoredIn(file: Path): List<Ignored> {
        val manifest = LuaTestManifest.findManifest(file) ?: return emptyList()
        val root = LuaUnitProjectRoot.of(manifest) ?: manifest.parent ?: return emptyList()
        val snapshot = snapshot(manifest, root)
        return snapshot.byFile[LuaUnitRunIndex.canonical(file.toString())] ?: emptyList()
    }

    /** The ignored case at [line] of [file], or null. */
    fun ignoredAt(file: Path, line: Int): Ignored? =
        ignoredIn(file).firstOrNull { it.declarationLine == line }

    private fun snapshot(manifest: Path, root: Path): Snapshot {
        val key = manifest.toAbsolutePath().normalize()
        val now = System.currentTimeMillis()
        val cached = snapshots[key]
        if (cached != null && now - cached.checkedAt < REFRESH_MS) {
            return cached
        }
        val stamp = stampOf(manifest)
        if (cached != null && cached.stamp == stamp) {
            cached.checkedAt = now
            return cached
        }
        return build(manifest, root, stamp).also { snapshots[key] = it }
    }

    /** The stamps of the manifest and every file it lists, as one number. */
    private fun stampOf(manifest: Path): Long {
        var stamp = LuaUnitTexts.stamp(manifest)
        for (file in manifestFiles(manifest)) {
            stamp = stamp * 31L + LuaUnitTexts.stamp(file)
        }
        return stamp
    }

    private fun build(manifest: Path, root: Path, stamp: Long): Snapshot {
        val byFile = HashMap<String, MutableList<Ignored>>()
        val seen = HashSet<String>()
        for (suite in manifestFiles(manifest)) {
            for (case in LuaUnitTextDiscovery.casesCached(manifest, suite, root)) {
                if (!case.ignored) {
                    continue
                }
                val (file, line) = declarationOf(case) ?: continue
                val fileKey = LuaUnitRunIndex.canonical(file.toString())
                // One data row feeds every generator of the module: the row is
                // one ignored case in the editor, not one per generator.
                if (!seen.add(fileKey + '\u0000' + line)) {
                    continue
                }
                byFile.getOrPut(fileKey) { ArrayList() }.add(Ignored(case.name, line))
            }
        }
        return Snapshot(stamp, byFile)
    }

    /** The files of [manifest], or none when it cannot be read. */
    private fun manifestFiles(manifest: Path): List<Path> = try {
        LuaTestManifest.files(manifest)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Where a case is declared: the `id = ...` line of a data row (the row's
     * own line is the `{`, which would put the mark on the wrong line), the
     * declaration line of a function.
     */
    private fun declarationOf(case: LuaUnitTextDiscovery.Case): Pair<Path, Int>? {
        val source = case.source
        val separator = source.lastIndexOf(':')
        if (separator <= 0) {
            return null
        }
        val file = try {
            Path.of(source.substring(0, separator))
        } catch (e: Exception) {
            return null
        }
        val line = if (case.idLine > 0) case.idLine
        else source.substring(separator + 1).toIntOrNull() ?: return null
        return if (line < 1) null else file to line
    }
}
