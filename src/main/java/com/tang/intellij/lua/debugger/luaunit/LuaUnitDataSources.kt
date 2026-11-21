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
        val generators = ArrayList<Generator>()
        for (file in files) {
            if (file.toAbsolutePath().normalize() == dataFile.toAbsolutePath().normalize()) {
                continue
            }
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                continue
            }
            if (!text.contains("require('$dataName')") && !text.contains("require(\"$dataName\")")) {
                continue
            }
            val loop = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*\[\s*['"]([^'"]*)['"]\s*\.\.\s*[A-Za-z_][A-Za-z0-9_]*\.id\s*]""")
                .find(text) ?: continue
            generators.add(Generator(file, loop.groupValues[1], loop.groupValues[2]))
        }
        return generators
    }

    /**
     * The case name of the row a literal `id = "..."` belongs to: the row's
     * `desc` when it has one (that is what the generator loop reads first),
     * otherwise the [id] itself. The literal's [offsetInFile] locates the row.
     */
    fun rowName(dataFile: java.nio.file.Path, id: String, offsetInFile: Int): String? {
        val text = try {
            Files.readString(dataFile)
        } catch (e: Exception) {
            return id
        }
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
