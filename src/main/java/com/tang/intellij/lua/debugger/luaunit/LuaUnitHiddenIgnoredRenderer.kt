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

import com.intellij.execution.testframework.PoolOfTestIcons
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SmRunnerBundle
import com.intellij.execution.testframework.sm.runner.SMTRunnerNodeDescriptor
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.ui.ColoredTreeCellRenderer
import java.awt.Component
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * The platform's tests-tree renderer with one correction at paint time: a
 * group that holds ignored cases keeps the "passed with ignored" icon after
 * the user hides ignored tests.
 *
 * <p>The "show ignored" toggle only filters the tree; the proxies stay, and a
 * group's state — the icon comes from it — is fixed when its suite finishes,
 * so the mark would never go away. With ignored tests hidden there is nothing
 * to signal: the group is painted as a plain passed one.
 */
class LuaUnitHiddenIgnoredRenderer(
    private val delegate: TreeCellRenderer,
    private val properties: TestConsoleProperties
) : TreeCellRenderer {

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ): Component {
        val component = delegate.getTreeCellRendererComponent(
            tree, value, selected, expanded, leaf, row, hasFocus
        )
        if (component !is ColoredTreeCellRenderer || !hidesIgnored()) {
            return component
        }
        val proxy = proxyOf(value) ?: return component
        if (!proxy.isLeaf && proxy.isIgnored) {
            component.icon = PoolOfTestIcons.PASSED_ICON
            component.setAccessibleStatusText(
                SmRunnerBundle.message("sm.test.runner.ui.tests.tree.presentation.accessible.status.passed")
            )
        }
        return component
    }

    private fun hidesIgnored(): Boolean = TestConsoleProperties.HIDE_IGNORED_TEST.value(properties)

    private fun proxyOf(value: Any?): SMTestProxy? {
        val node = value as? DefaultMutableTreeNode ?: return null
        val descriptor = node.userObject as? SMTRunnerNodeDescriptor ?: return null
        return descriptor.element
    }
}
