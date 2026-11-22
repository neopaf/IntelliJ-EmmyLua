package com.tang.intellij.lua.debugger.luaunit

import java.nio.file.Files
import java.nio.file.Path

/**
 * The test files generated from a plain data module.
 *
 * <p>A data file (`tests/ss7/test_ss7_data.lua`) is a table of `id = "..."` rows
 * with no test in it. The cases live in the files that `require` it and build a
 * loop such as
 *
 * ```lua
 * local tests = require('tests/ss7/test_ss7_data')
 * for _, test in pairs(tests) do
 *     TestSuite['test_' .. test.id] = function() ... end
 * end
 * ```
 *
 * so one data row is the declaration line of a case of every such generator.
 * This object finds those generators (there may be several) and the case prefix
 * each of them adds, so the marker in the data file can run the generated case.
 */
object LuaUnitDataSources {

    /** One generator: the file name and the class and prefix it builds. */
    data class Generator(val file: Path, val suite: String, val prefix: String)

    /**
     * The generators of every data module of the manifest, cached by the
     * joined change stamps of the manifest and all its files: one scan serves
     * the whole gutter pass, and any edit (saved or not) drops the cache.
     */
    private val scanCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, List<Generator>>>>()

    /** ".lua" stripped, as `require` names it. */
    private fun requireName(file: Path): String = file.toString().removeSuffix(".lua").replace('\\', '/')

    /** Generators of [dataFile] that are part of [manifest]. */
    fun find(manifest: Path, dataFile: Path, root: Path): List<Generator> {
        val dataName = requireName(root.toAbsolutePath().normalize().relativize(dataFile.toAbsolutePath().normalize()))
        val files = try {
            LuaTestManifest.files(manifest)
        } catch (e: Exception) {
            return emptyList()
        }
        // A data row asks for its generators on every line marker pass, and
        // the answer lives in ALL the files of the manifest: without this
        // cache every `id = "..."` line re-read every one of them.
        var stampKey = LuaUnitTexts.stamp(manifest)
        for (file in files) {
            stampKey = stampKey * 31L + LuaUnitTexts.stamp(file)
        }
        val key = manifest.toAbsolutePath().normalize().toString()
        val hit = scanCache[key]
        val scan: Map<String, List<Generator>>
        if (hit != null && hit.first == stampKey) {
            scan = hit.second
        } else {
            scan = scanGenerators(files)
            scanCache[key] = stampKey to scan
        }
        return scan[dataName] ?: emptyList()
    }

    /** The `require`d data module of every file of the manifest, with its loop. */
    private fun scanGenerators(files: List<Path>): Map<String, List<Generator>> {
        val result = HashMap<String, MutableList<Generator>>()
        val loop = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\[\s*['"]([^'"]*)['"]\s*\.\.\s*[A-Za-z_][A-Za-z0-9_]*\.id\s*]""")
        val require = Regex("""require\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        for (file in files) {
            val text = LuaUnitTexts.read(file) ?: continue
            val found = loop.find(text) ?: continue
            for (match in require.findAll(text)) {
                result.getOrPut(match.groupValues[1]) { ArrayList() }
                    .add(Generator(file, found.groupValues[1], found.groupValues[2]))
            }
        }
        return result
    }

    /**
     * The case name of the row a literal `id = "..."` belongs to: the row's
     * `desc` when it has one (that is what the generator loop reads first),
     * otherwise the [id] itself. The literal's [offsetInFile] locates the row.
     */
    fun rowName(dataFile: java.nio.file.Path, id: String, offsetInFile: Int): String? {
        // The editor's copy: the row a marker sits on may not be saved yet.
        val text = LuaUnitTexts.read(dataFile) ?: return id
        // Walk back to the row's opening brace, then forward over the row's own
        // span (a nested table ends the first field scan) for a `desc = '...'`.
        var index = offsetInFile.coerceIn(0, text.length)
        while (index > 0) {
            val ch = text[index - 1]
            if (ch == '}') {
                break
            }
            if (ch == '{') {
                break
            }
            index--
        }
        val desc = Regex("""\bdesc\s*=\s*['"]([^'"]+)['"]""")
            .find(text, index)
        // The desc must sit inside this row: no `}` between the brace and it.
        if (desc != null) {
            val between = text.substring(index, desc.range.first)
            if (!between.contains('}')) {
                return desc.groupValues[1]
            }
        }
        return id
    }
}
