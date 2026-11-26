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

package com.tang.intellij.lua.editor.structure

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import javax.swing.Icon

/**
 * A LuaUnit case of the structure view that has no PSI element of its own in
 * the file: a case generated from a data row, or a case hidden in a block
 * comment. A click opens the line the case is declared on — the row's `desc`
 * (or `id`) in a data module, the commented-out function in a suite file.
 */
class LuaTestCaseElement(
    private val project: Project,
    private val file: Path,
    private val line: Int,
    name: String,
    description: String? = null,
    icon: Icon = AllIcons.Nodes.Test
) : LuaTreeElement(CaseTarget(project, file, line), name, icon) {

    init {
        location = description
    }

    /** The navigation target of a case: opens [file] at [line]. */
    private class CaseTarget(
        private val project: Project,
        private val file: Path,
        private val line: Int
    ) : NavigationItem {

        override fun getName(): String? = null

        override fun getPresentation(): ItemPresentation? = null

        override fun navigate(requestFocus: Boolean) {
            val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return
            OpenFileDescriptor(project, virtualFile, (line - 1).coerceAtLeast(0), 0).navigate(requestFocus)
        }

        override fun canNavigate(): Boolean = true

        override fun canNavigateToSource(): Boolean = true
    }
}
