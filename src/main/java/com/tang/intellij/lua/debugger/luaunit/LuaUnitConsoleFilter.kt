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

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project

/**
 * The `file:line` references of the tests console, as clickable links.
 *
 * <p>A case's own output — its stack trace, the `at fixture(...)` frames — is
 * plain text, so the console turns the references into hyperlinks on their
 * way through this filter.
 *
 * <p>Only a path that ends in a known file extension counts: a generic
 * "anything:digit" pattern also matched timestamps (`15:20:52.286`) and AVP
 * dumps of the test data, which lit half of the output for no reason. The
 * file must exist in the project, otherwise the link would go nowhere.
 */
class LuaUnitConsoleFilter(
    private val project: Project
) : Filter {

    private val reference = Regex("""([\w./ -]+\.(?:lua|json|txt|xml|yaml|yml)(?:\.txt)?)(?::(\d+))?""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = reference.find(line) ?: return null
        val file = LuaUnitTestLocator().findFile(project, match.groupValues[1]) ?: return null
        // A reference without a line opens the file itself (a fixture of the
        // run points at the whole file, and the runner prints no ":1").
        val lineNumber = match.groupValues[2].takeIf { it.isNotEmpty() }
            ?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
        val start = entireLength - line.length + match.range.first
        return Filter.Result(
            start,
            start + match.groupValues[1].length + (if (match.groupValues[2].isEmpty()) 0 else match.groupValues[2].length + 1),
            OpenFileHyperlinkInfo(project, file, lineNumber)
        )
    }
}
