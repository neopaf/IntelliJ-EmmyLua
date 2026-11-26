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

/**
 * The LuaUnit cases a file declares, found the way the gutter icons find them:
 * from the manifest and the texts of the files, with nothing run.
 *
 * <p>Two files declare cases:
 * <ul>
 *   <li>a suite file listed in the manifest — its `function Suite:test_x`
 *       declarations and the cases its generator loop builds;</li>
 *   <li>a data module the suites `require` — every row is the declaration of a
 *       case of each file that generates cases from the module.</li>
 * </ul>
 *
 * <p>The names are exactly the ones the run configuration tree uses: the same
 * [LuaUnitTextDiscovery] and [LuaUnitDataSources] objects answer the gutter, the
 * tree and the structure view.
 */
object LuaUnitTestCaseLookup {

    /**
     * One case of the file: the name the test tree shows, the suite it belongs
     * to, its description and the line it is declared on.
     */
    data class Entry(
        /** The full LuaUnit name (`Suite.test_x`). */
        val name: String,
        /** The case's own name (`test_x`): what the structure view shows. */
        val shortName: String,
        /** The table the case is declared on, empty for a plain global test. */
        val owner: String,
        /** The `-- ...` explanation of the declaration, empty when there is none. */
        val description: String,
        /** The file the declaration line lives in (a data row may sit in another file). */
        val file: Path,
        /** The 1-based declaration line. */
        val line: Int,
        /** A case that is commented out and never runs. */
        val ignored: Boolean,
        /** Built by a generator loop from a data row. */
        val generated: Boolean
    )

    /**
     * Every case [file] declares, in the order of their lines.
     *
     * <p>An empty list for a file the manifest does not govern and that no
     * suite generates cases from: a plain script keeps a plain structure.
     */
    fun entriesOf(file: Path): List<Entry> {
        val manifest = LuaTestManifest.findManifest(file) ?: return emptyList()
        val root = LuaUnitProjectRoot.of(manifest) ?: return emptyList()
        val normalized = file.toAbsolutePath().normalize()

        val cases = try {
            if (inManifest(manifest, normalized)) {
                LuaUnitTextDiscovery.casesCached(manifest, file, root)
            } else {
                casesGeneratedFrom(manifest, file, normalized, root)
            }
        } catch (e: Exception) {
            // The structure view must not fail on a file the discovery cannot
            // read: the plain PSI structure is still the answer.
            return emptyList()
        }
        return cases
            .mapNotNull { case -> toEntry(case) }
            .sortedWith(compareBy({ it.line }, { it.name }))
    }

    /** True when [file] is listed by the manifest. */
    private fun inManifest(manifest: Path, file: Path): Boolean = try {
        LuaTestManifest.files(manifest).any { it.toAbsolutePath().normalize() == file }
    } catch (e: Exception) {
        false
    }

    /**
     * The cases every generator of the [dataFile] builds: its rows are the
     * declaration lines of the cases of each suite file that requires it.
     */
    private fun casesGeneratedFrom(
        manifest: Path,
        dataFile: Path,
        normalized: Path,
        root: Path
    ): List<LuaUnitTextDiscovery.Case> {
        val generators = LuaUnitDataSources.find(manifest, dataFile, root)
        if (generators.isEmpty()) {
            return emptyList()
        }
        val result = LinkedHashSet<LuaUnitTextDiscovery.Case>()
        for (generator in generators) {
            for (case in LuaUnitTextDiscovery.casesCached(manifest, generator.file, root)) {
                val (file, _) = sourceOf(case) ?: continue
                if (file != normalized) {
                    continue
                }
                result.add(case)
            }
        }
        return result.toList()
    }

    private fun toEntry(case: LuaUnitTextDiscovery.Case): Entry? {
        val (file, line) = sourceOf(case) ?: return null
        return Entry(
            name = case.name,
            shortName = case.shortName,
            owner = case.owner,
            description = case.description,
            file = file,
            line = line,
            ignored = case.ignored,
            generated = case.idLine > 0
        )
    }

    /** The `<path>:<line>` of a case, as the discovery stores it. */
    private fun sourceOf(case: LuaUnitTextDiscovery.Case): Pair<Path, Int>? {
        val source = case.source
        val colon = source.lastIndexOf(':')
        if (colon <= 0) {
            return null
        }
        val line = source.substring(colon + 1).toIntOrNull() ?: return null
        return try {
            Path.of(source.substring(0, colon)).toAbsolutePath().normalize() to line
        } catch (e: Exception) {
            null
        }
    }
}
