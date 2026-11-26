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

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Created by TangZX on 2016/12/28.
 */
open class LuaTreeElement(
    val element: NavigationItem,
    var name: String,
    var icon: Icon
) : StructureViewTreeElement {
    var parent: LuaTreeElement? = null
    private val children = LinkedHashMap<String, LuaTreeElement>()

    /**
     * The note shown to the right of the name: a test case's description, or
     * the "ignored" mark of a commented-out case. Null keeps the line empty.
     */
    var location: String? = null

    /**
     * The position of the node in the file, in characters: the key of the
     * file-order sorter. A node of the PSI takes the offset of its element, a
     * case with no PSI in this file (a generated one) sets its own.
     */
    var lineOrder: Int = (element as? PsiElement)?.textRange?.startOffset ?: 0

    open fun getPresentableText(): String? {
        return name
    }

    override fun getValue(): Any {
        return element
    }

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String? {
                return this@LuaTreeElement.getPresentableText()
            }

            override fun getLocationString(): String? {
                return this@LuaTreeElement.location
            }

            override fun getIcon(b: Boolean): Icon? {
                return this@LuaTreeElement.icon
            }
        }
    }

    override fun getChildren(): Array<TreeElement> {
        return children.values.toTypedArray()
    }

    fun addChild(child: LuaTreeElement, name: String? = null) {
        children[name ?: child.name] = child
        child.parent = this
    }

    fun clearChildren() {
        children.clear()
    }

    fun childNamed(name: String): LuaTreeElement? {
        return children[name]
    }

    override fun navigate(b: Boolean) {
        element.navigate(b)
    }

    override fun canNavigate(): Boolean {
        return element.canNavigate()
    }

    override fun canNavigateToSource(): Boolean {
        return element.canNavigateToSource()
    }
}
