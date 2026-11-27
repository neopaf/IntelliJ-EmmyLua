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
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * The icon of [state] marked as incompletely covered: the status glyph with
     * a small white dot in its top-right corner.
     *
     * <p>The dot says what the status colour cannot: one row of a data module
     * feeds several generated cases, and the run behind the icon may have
     * executed only some of them. The colour stays the result of the cases that
     * did run; the dot adds "and not all of them did".
     */
    fun partialOf(state: LuaUnitTestState?): Icon = withDot(of(state))

    /** [icon] with the white dot of partial coverage, cached by identity. */
    fun withDot(icon: Icon): Icon = DOTTED.computeIfAbsent(icon) { StatusDotIcon(it) }

    /**
     * One icon per base glyph: the platform keeps the presentation of a line
     * marker between repaints and compares merged icons by identity, so the
     * wrapper must be the very same object every time.
     */
    private val DOTTED = ConcurrentHashMap<Icon, Icon>()

    /**
     * A status glyph with a white dot in its top-right corner — the mark of a
     * line whose cases did not all run.
     *
     * <p>The dot is drawn over the platform icon in the icon's own coordinate
     * space, so HiDPI scaling and the theme's icon variants keep working: the
     * size follows the icon's logical width, and the location follows its
     * top-right corner. A thin dark outline keeps it visible on a light
     * glyph, on a dark background and on every display scale.
     */
    private class StatusDotIcon(private val base: Icon) : Icon {

        override fun getIconWidth(): Int = base.iconWidth

        override fun getIconHeight(): Int = base.iconHeight

        override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
            base.paintIcon(component, g, x, y)
            val width = base.iconWidth
            val height = base.iconHeight
            if (width <= 0 || height <= 0) {
                return
            }
            // A 16px glyph gets a 4px dot; a larger one keeps it readable
            // without covering the glyph itself.
            val size = maxOf(3, width / 4)
            val dotX = x + width - size - 1
            val dotY = y + 1
            val graphics = g.create()
            try {
                if (graphics is Graphics2D) {
                    graphics.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                    )
                }
                graphics.color = OUTLINE
                graphics.fillOval(dotX, dotY, size, size)
                graphics.color = DOT
                graphics.fillOval(dotX + 1, dotY + 1, size - 2, size - 2)
            } finally {
                graphics.dispose()
            }
        }

        override fun toString(): String = "LuaUnitStatus($base, partial)"
    }

    /** The dot itself: white on every theme, the colour the request named. */
    private val DOT = Color.WHITE

    /** A faint outline: a white dot alone vanishes on a light glyph. */
    private val OUTLINE = Color(0, 0, 0, 0x66)
}
