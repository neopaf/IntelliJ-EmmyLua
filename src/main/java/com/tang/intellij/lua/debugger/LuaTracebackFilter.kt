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

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.HyperlinkInfoBase
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.testframework.actions.TestDiffRequestProcessor
import com.intellij.execution.testframework.stacktrace.DiffHyperlink
import com.intellij.openapi.ListSelection
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.tang.intellij.lua.psi.LuaFileUtil
import java.util.regex.Pattern

/**
 *
 * Created by tangzx on 2017/6/10.
 */
class LuaTracebackFilter(private val project: Project) : Filter {

    private val filePattern: Pattern = Pattern.compile("\\s*((/+)?[^<>\\\\|:\"*? ]+):(\\d+):")

    private var diffHyperlink: DiffHyperlinkInfo? = null

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        //lua.exe: Test.lua:3: attempt to call global 'print1' (a nil value)
        //stack traceback:
        //Test.lua:3: in function 'a'
        //Test.lua:7: in function 'b'
        //Test.lua:11: in main chunk

        val matcher = filePattern.matcher(line)
        if (matcher.find()) {
            val fileName = matcher.group(1).replace(Regex("^\\.\\.\\."), "") // remove leading '...', it confuses finders
            val lineNumber = Integer.parseInt(matcher.group(3))
            val file = LuaFileUtil.findFile(project, fileName)
            if (file != null) {
                val hyperlink = OpenFileHyperlinkInfo(project, file, lineNumber - 1)

                val textStartOffset = entireLength - line.length
                val startPos = matcher.start(1)
                val endPos = matcher.end(3) + 1
                return Filter.Result(startPos + textStartOffset, endPos + textStartOffset, hyperlink)
            }
        }

        /*
            ...some_test.lua:116: expected:
            {
                ...
            }
            actual:
            {
                ...
            }
        */
        if(line.endsWith("expected:"))
            diffHyperlink = DiffHyperlinkInfo() // pen down
        else if(diffHyperlink != null) {
            if(line.endsWith("actual:")) {
                diffHyperlink!!.actual = ""
                return null
            }
            if(diffHyperlink!!.actual == null) {
                diffHyperlink!!.expected = diffHyperlink!!.expected + line + "\n"
            } else {
                diffHyperlink!!.actual = diffHyperlink!!.actual + line + "\n"
                if(line == "}") // last line of table output
                    diffHyperlink = null // pen up
            }
            return Filter.Result(0, line.length, diffHyperlink)
        }
        return null
    }

    class DiffHyperlinkInfo() : HyperlinkInfoBase() {
        var expected: String = ""
        var actual: String? = null
        override fun navigate(project: Project, hyperlinkLocationPoint: RelativePoint?) {
            val hyperlink = DiffHyperlink(expected, actual!!, null, null, true)
            val listSelection = ListSelection.createSingleton(hyperlink)
            val chain = TestDiffRequestProcessor.createRequestChain(project, listSelection)
            DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        }
    }
}
