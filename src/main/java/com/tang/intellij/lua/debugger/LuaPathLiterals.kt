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

package com.tang.intellij.lua.debugger

/**
 * A path as it can be put inside a Lua string literal.
 *
 * <p>A Windows path is full of backslashes, and Lua reads `\` inside a string
 * as the start of an escape sequence: `C:\Users\SKrishan\AppData\...` reached
 * Lua as `C:UsersSKrishanAppData...`, so `package.path` pointed at nothing and
 * the bundled mobdebug was never found. The separators become `/` — Lua opens
 * such paths on Windows as well — and a quote is escaped.
 */
object LuaPathLiterals {

    /** [path] as the body of a Lua string literal. */
    fun of(path: String): String = path.replace('\\', '/').replace("'", "\\'")

    /** [paths] as a `package.path` chunk: every entry ends with a separator. */
    fun packagePath(paths: List<String>): String =
        if (paths.isEmpty()) {
            ""
        } else {
            of(paths.joinToString(";") { "$it/?.lua" } + ";")
        }
}
