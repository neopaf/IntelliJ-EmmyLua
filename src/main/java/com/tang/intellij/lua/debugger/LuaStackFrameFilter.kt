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

import com.intellij.xdebugger.frame.XStackFrame
import com.tang.intellij.lua.debugger.emmy.EmmyDebugStackFrame
import com.tang.intellij.lua.debugger.remote.LuaMobStackFrame

/**
 * Drops the frames of the plugin's own machinery from the Frames panel.
 *
 * <p>The LuaUnit runner loads the test file while wrapping it into `xpcall` and
 * a `require` hook, so a test run shows the runner's own frames around every
 * user frame. The frames are hidden, not removed from the session: the frame
 * objects are kept as they are, so the stack level a frame carries (and with
 * it Variables and Evaluate) still points at the right Lua level.
 *
 * <p>A frame is service when it belongs to a script the plugin ships, or when
 * it is a call of a wrapping function that Lua reports without a position
 * (`realRequire (unknown)`, `xpcall (unknown)`). A user's own `require(...)` or
 * `pcall(...)` has a position and is therefore kept.
 */
object LuaStackFrameFilter {

    /** Scripts the plugin ships: the LuaUnit runner and the debugger backend. */
    private val SERVICE_FILES = setOf(
        "emmylua_luaunit.lua",
        "emmylua_diff.lua",
        "mobdebug.lua"
    )

    /** Wrappers the runner and the debugger call on behalf of the user's code. */
    private val WRAPPER_NAMES = setOf(
        "require", "realRequire", "requireHook",
        "pcall", "xpcall", "dofile", "loadfile", "load", "loadstring"
    )

    /**
     * The frames to show. An empty result would leave the panel blank, so the
     * input is returned unchanged when everything is filtered out.
     */
    fun filter(frames: List<XStackFrame>): List<XStackFrame> {
        if (frames.size <= 1) {
            return frames
        }
        val kept = frames.filterNot { isServiceFrame(it) }
        return kept.ifEmpty { frames }
    }

    private fun isServiceFrame(frame: XStackFrame): Boolean {
        val position = frame.sourcePosition
        if (position != null && position.file.name in SERVICE_FILES) {
            return true
        }
        val functionName = functionNameOf(frame) ?: return false
        return position == null && functionName in WRAPPER_NAMES
    }

    private fun functionNameOf(frame: XStackFrame): String? = when (frame) {
        is LuaMobStackFrame -> frame.functionName
        is EmmyDebugStackFrame -> frame.data.functionName
        else -> null
    }
}
