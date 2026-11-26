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
 * The suite tree of a LuaUnit run configuration, before anything runs.
 *
 * <p>`run_tests.lua` supplies the sections and the files; the cases of a file
 * are read from its text (see [LuaUnitTextDiscovery]), so the tree shows what
 * is going to run (and where each case lives) without executing a case.
 *
 * <p>The tree is exactly the shape the console tree gets:
 *
 * ```
 * run_tests.lua
 *   ├─ SS7                         (section of the manifest)
 *   │    └─ test_ss7_common.lua    (suite: one Lua process)
 *   │         ├─ TestSs7CommonHex.test_roundtrip_frame2
 *   │         └─ ...
 *   └─ DTCAP
 * ```
 */
sealed class LuaUnitNode {
    /** The root: the manifest or the single file the configuration points at. */
    data class Root(
        val manifest: Path,
        val sections: List<Section>
    ) : LuaUnitNode()

    /** A section of the manifest (`--SS7`), its title and its files. */
    data class Section(
        val title: String,
        val suites: List<Suite>
    ) : LuaUnitNode()

    /** One test file: a Lua process of its own. */
    data class Suite(
        val file: Path,
        val displayName: String,
        val cases: List<Case>,
        /** A discovery or load problem for the whole file. */
        val error: String? = null
    ) : LuaUnitNode()

    /**
     * One case. [name] is the LuaUnit name (`Class.method` or `testFn`);
     * [source] is where it is declared — the definition line, or, for a case
     * generated in a loop, the `id = ...` line its data came from.
     */
    data class Case(
        val name: String,
        val displayName: String,
        val source: LuaUnitSource?,
        /** A commented-out data row ("-- id = 'x'"): shown as ignored. */
        val ignored: Boolean = false,
        /** A trailing "-- ..." on the data row: shown after the name. */
        val description: String = "",
        /** Built in a loop from a data row: the declaration is the row, not a
         *  function, so its failures belong to the row of the data table. */
        val generated: Boolean = false,
        /** The table the case is declared on (`Suite`, `M.Suite`), empty for a
         *  plain global test. Kept apart from [name] on purpose: a generated
         *  case's name may contain dots of its own, so the owner cannot be
         *  recovered by cutting the name at a dot. */
        val owner: String = ""
    ) : LuaUnitNode()

    /** A `file:line` pair a case (or a fixture) points at. */
    data class LuaUnitSource(val file: Path, val line: Int)

    fun suites(): List<Suite> = when (this) {
        is Root -> sections.flatMap { it.suites }
        is Section -> suites
        is Suite -> listOf(this)
        is Case -> emptyList()
    }
}
