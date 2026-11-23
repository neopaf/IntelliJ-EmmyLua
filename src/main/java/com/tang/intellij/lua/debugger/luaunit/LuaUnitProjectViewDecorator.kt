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

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Colours a test file or a fixture in the project view by what the last run
 * said about it: green when everything that touched the file passed, red when
 * something failed.
 *
 * <p>This is the standard "problem file" decoration of the tree: the name is
 * tinted, the icon and the rest of the row stay as they are. A file no run
 * knows keeps the regular colour.
 */
class LuaUnitProjectViewDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (LuaUnitHighlights.isRunning()) {
            return
        }
        val index = LuaUnitRunIndex.getInstance(node.project)
        val status = (if (file.isDirectory) {
            index.aggregateUnder(file.path)
        } else {
            index.aggregateOf(file.path)
        }) ?: return
        data.forcedTextForeground =
            if (status == LuaUnitRunIndex.Status.FAILED) FAILED_COLOR else PASSED_COLOR
    }

    companion object {
        /** The tree's own test colours, tuned for both themes. */
        private val PASSED_COLOR: Color = JBColor(0x59A869, 0x499C54)
        private val FAILED_COLOR: Color = JBColor(0xDB5860, 0xC7222D)
    }
}
