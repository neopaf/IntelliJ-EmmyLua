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

package com.tang.intellij.lua.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.SorterUtil
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.tang.intellij.lua.LuaBundle
import com.tang.intellij.lua.editor.structure.LuaClassFieldElement
import com.tang.intellij.lua.editor.structure.LuaFileElement
import com.tang.intellij.lua.editor.structure.LuaFuncElement
import com.tang.intellij.lua.editor.structure.LuaTestStructure
import com.tang.intellij.lua.editor.structure.LuaTreeElement
import com.tang.intellij.lua.psi.LuaPsiFile

/**
 * Structure View
 * Created by TangZX on 2016/12/13.
 */
class LuaStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        return object : TreeBasedStructureViewBuilder() {

            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return LuaStructureViewModel(psiFile)
            }

            override fun createStructureView(fileEditor: com.intellij.openapi.fileEditor.FileEditor?, project: com.intellij.openapi.project.Project): StructureView {
                val view = super.createStructureView(fileEditor, project)
                // The tree of the created view is reached by name: the
                // component lives in the core of IDEA 2025.3 and in the
                // `platform-structureView` plugin of IDEA 2026.2, where a
                // compile-time reference would not resolve.
                val tree = try {
                    view.javaClass.getMethod("getTree").invoke(view) as? javax.swing.JTree
                } catch (e: Exception) {
                    null
                }
                if (tree != null) {
                    // IDEA 2026.2 opens the test suites on its own; 2025.3
                    // leaves them collapsed, with the cases hidden. A file of
                    // LuaUnit cases opens its suites so the cases are visible
                    // at once, without a click.
                    if (psiFile is LuaPsiFile && LuaTestStructure.hasTests(psiFile)) {
                        com.intellij.util.ui.tree.TreeUtil.promiseExpandAll(tree)
                    }
                }
                return view
            }
        }
    }

    inner class LuaStructureViewModel(psiFile: PsiFile) : StructureViewModelBase(psiFile, LuaFileElement(psiFile as LuaPsiFile)), StructureViewModel.ElementInfoProvider {

        init {
            // A file of LuaUnit cases follows the order of its lines — the run
            // tree shows the cases the same way, and the alphabet would mix the
            // data rows up. Every other file keeps the alphabetical order.
            if (LuaTestStructure.hasTests(psiFile as LuaPsiFile)) {
                withSorters(LuaFileOrderSorter(), LuaAlphaSorter())
            } else {
                withSorters(LuaAlphaSorter())
            }
        }

        override fun isAlwaysShowsPlus(structureViewTreeElement: StructureViewTreeElement): Boolean {
            return false
        }

        override fun isAlwaysLeaf(structureViewTreeElement: StructureViewTreeElement): Boolean {
            return false
        }
    }

    /**
     * 字母alpha排序，但field排在method前面
     */
    inner class LuaAlphaSorter : Sorter {

        override fun getComparator() = kotlin.Comparator<Any> { o1, o2 ->
            if (o1 is LuaClassFieldElement && o2 is LuaFuncElement)
                return@Comparator -1
            else if (o1 is LuaFuncElement && o2 is LuaClassFieldElement)
                return@Comparator 1

            val s1 = SorterUtil.getStringPresentation(o1)
            val s2 = SorterUtil.getStringPresentation(o2)
            s1.compareTo(s2, ignoreCase = true)
        }

        override fun isVisible(): Boolean {
            return true
        }

        override fun getPresentation(): ActionPresentation {
            return ActionPresentationData(LuaBundle.message("action.sort.alphabetically"), LuaBundle.message("action.sort.alphabetically_desc"), AllIcons.ObjectBrowser.Sorted)
        }

        override fun getName(): String {
            return "Alpha Sorter"
        }
    }

    /**
     * The order of the file, by the character offset of each node. A node with
     * no PSI of its own (a LuaUnit case built from a data row) carries the
     * offset of its line.
     */
    inner class LuaFileOrderSorter : Sorter {

        override fun getComparator() = kotlin.Comparator<Any> { o1, o2 ->
            val first = (o1 as? LuaTreeElement)?.lineOrder ?: 0
            val second = (o2 as? LuaTreeElement)?.lineOrder ?: 0
            first.compareTo(second)
        }

        override fun isVisible(): Boolean {
            return true
        }

        override fun getPresentation(): ActionPresentation {
            return ActionPresentationData(
                "Sort by Position",
                "Sort by Position in File",
                AllIcons.ObjectBrowser.Sorted
            )
        }

        override fun getName(): String {
            return "File Order Sorter"
        }
    }
}
