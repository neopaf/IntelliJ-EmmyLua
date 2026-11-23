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

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * The colour of a failure hint: the red of the editor's colour scheme, bright
 * enough to be read on the scheme's own background.
 *
 * <p>The scheme keeps the red of an error as the foreground of its error
 * attributes; a dim value is brightened until it reads, and a palette without
 * the attributes falls back to the platform's red, which is still a
 * theme-aware colour rather than a hard-coded one.
 */
object LuaUnitErrorColors {

    /** The letters of a failure hint: bright red, from the palette. */
    val text: Color
        get() {
            val scheme = EditorColorsManager.getInstance().globalScheme
            val base = scheme.defaultBackground
            var colour = scheme.getAttributes(CodeInsightColors.ERRORS_ATTRIBUTES)?.foregroundColor
                ?: JBColor.RED
            repeat(6) {
                if (contrast(colour, base) >= MIN_CONTRAST) {
                    return colour
                }
                colour = colour.brighter()
            }
            return JBColor.RED
        }

    /** The letters of a diff hint: the palette's hyperlink colour. */
    val link: Color
        get() {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return scheme.getAttributes(CodeInsightColors.HYPERLINK_ATTRIBUTES)?.foregroundColor
                ?: scheme.defaultForeground
        }

    /** The least contrast the letters need against the editor background. */
    private const val MIN_CONTRAST = 3.5

    /** Relative luminance difference (WCAG-ish), only to brighten a dim red. */
    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val la = luminance(a)
        val lb = luminance(b)
        val hi = Math.max(la, lb)
        val lo = Math.min(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }
}
