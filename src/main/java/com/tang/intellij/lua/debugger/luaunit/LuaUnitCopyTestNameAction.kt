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

import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerTestTreeView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Copies the selected test tree node's name, from the tree's context menu.
 *
 * <p>Cmd+C works while the tree has the focus (the console installs a
 * TransferHandler for it), but a double-click on a case opens its declaration
 * and moves the focus to the editor — the shortcut then copies the editor,
 * not the tree. The context menu is always about the tree.
 */
class LuaUnitCopyTestNameAction : AnAction("Copy Test Name", null, AllIcons.Actions.Copy) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val model = event.getData(TestTreeView.MODEL_DATA_KEY)
        val tree = SMTRunnerTestTreeView.SM_TEST_RUNNER_VIEW.getData(event.dataContext)
        val selected = tree?.selectedTest
        event.presentation.isEnabled =
            model?.properties?.configuration is LuaUnitRunConfiguration
        // The menu says what it copies: a case keeps its own name, a group
        // (a file, a section) is a group.
        event.presentation.text = when {
            selected == null -> "Copy Test Name"
            selected.isLeaf -> "Copy Test Name"
            else -> "Copy Test Group Name"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val tree = SMTRunnerTestTreeView.SM_TEST_RUNNER_VIEW.getData(event.dataContext) ?: return
        val proxy = tree.selectedTest ?: return
        val text = copyableText(proxy as SMTestProxy)
        if (text.isEmpty()) {
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /** A case copies everything it shows (its description included); a group
      *  drops the live "(N passed, ...)" counter of its name. */
    private fun copyableText(proxy: SMTestProxy): String {
        val shown = proxy.presentableName ?: proxy.name ?: return ""
        return if (proxy.isLeaf) shown else shown.replace(Regex("""\s+\([^)]*\)$"""), "")
    }
}
