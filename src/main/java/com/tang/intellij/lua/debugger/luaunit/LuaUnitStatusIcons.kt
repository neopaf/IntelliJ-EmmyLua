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

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * The one gutter icon of a LuaUnit line: the result of the last run with a
 * small run arrow beside it.
 *
 * <p>A line used to carry two markers — the arrow of the run marker and the
 * separate mark of the result — and the gutter paints every icon of a line as
 * its own column, so the whole file paid an extra column (an icon plus the gap)
 * even though only the test lines needed one.
 *
 * <p>These are the platform's own composed icons: `TestState.Green2`, `Red2`
 * and `Yellow2` are exactly a status glyph plus the small run arrow, the very
 * icons IDEA shows on the gutter of a JUnit method. Nothing is drawn here, so
 * they stay crisp at any display scale and change with the theme.
 *
 * <p>The instances are constants: the platform compares the icons of mergeable
 * markers by identity, and a fresh object per request would break that.
 */
internal object LuaUnitStatusIcons {

    /** The line did not run yet (or a run is going on): the bare run arrow. */
    val NOT_RUN: Icon = AllIcons.RunConfigurations.TestState.Run

    /** A green tick: every case of the marker passed. */
    val PASSED: Icon = AllIcons.RunConfigurations.TestState.Green2

    /** A yellow cross: a failed assertion, an expected/actual pair. */
    val FAILED: Icon = AllIcons.RunConfigurations.TestState.Yellow2

    /** A red exclamation mark: any other failure. */
    val ERROR: Icon = AllIcons.RunConfigurations.TestState.Red2

    /** The icon of [state], the bare arrow when nothing is known yet. */
    fun of(state: LuaUnitTestState?): Icon = when (state) {
        LuaUnitTestState.PASSED -> PASSED
        LuaUnitTestState.FAILED -> FAILED
        LuaUnitTestState.ERROR -> ERROR
        null -> NOT_RUN
    }
}
