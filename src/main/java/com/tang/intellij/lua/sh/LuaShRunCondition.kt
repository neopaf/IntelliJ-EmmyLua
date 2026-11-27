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

package com.tang.intellij.lua.sh

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.sh.run.ShRunnerAdditionalCondition
import com.tang.intellij.lua.psi.LuaPsiFile

/**
 * Keeps the Shell Script plugin (bundled in IntelliJ IDEA Ultimate) from
 * putting its run marker on a Lua file whose first line is a shebang.
 *
 * <p>That plugin registers its run marker contributor without a language and
 * answers for any file whose first element starts with "#!"
 * (`com.intellij.sh.backend.run.ShRunLineMarkerContributor`). A Lua module with
 * a shebang would then get a second gutter icon next to the LuaUnit one, and
 * the run behind it executes the file itself with no interpreter — the file is
 * rarely executable, so the terminal only reports "Permission denied".
 *
 * <p>This is the condition the Shell Script plugin asks before it shows the
 * marker. It is registered from `emmylua-sh.xml`, which the platform loads only
 * when `com.jetbrains.sh` is installed: a Community Edition install (the plugin
 * is not there) never loads it, and the rest of this plugin works as before.
 */
class LuaShRunCondition : ShRunnerAdditionalCondition {

    override fun isRunningProhibitedForElement(element: PsiElement): Boolean =
        element.containingFile is LuaPsiFile

    override fun isRunningProhibitedForFile(file: PsiFile): Boolean = file is LuaPsiFile
}
