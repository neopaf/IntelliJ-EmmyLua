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

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications

/**
 * Everything a run paints in the editor — the code vision of a case, the inlay
 * of a failure, the panel of a fixture — lives on the index, not on the PSI.
 * The index changes while nothing else does, so neither the daemon nor the code
 * vision host ever learns that its answer is stale: without the explicit
 * invalidation below the marks kept the previous run's state until the IDE was
 * restarted (the only thing that rebuilds both caches from scratch).
 *
 * <p>A run is painted in two steps. At its start the old marks are dropped (the
 * user is looking at the run that just began, not at the previous one), and the
 * run keeps them empty while it goes; at its end the new results are published
 * and every open editor is invalidated at once.
 */
object LuaUnitHighlights {

    @Volatile
    private var running = false

    /** True between the start of a run and its published results. */
    fun isRunning(): Boolean = running

    /** The run begins: hide what the previous one left and drop the caches. */
    fun start(project: Project) {
        running = true
        refresh(project, "run started")
    }

    /** The run produced its results (or was stopped): show them at once. */
    fun finish(project: Project) {
        running = false
        refresh(project, "run finished")
    }

    /**
     * Drops every cache the marks live in: the code vision of every open
     * editor, the inlay hints of the whole project and the daemon passes of the
     * open files. Runs on the EDT; the computes behind the marks are cheap.
     */
    fun refresh(project: Project, reason: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }
            val log = com.intellij.openapi.diagnostic.Logger.getInstance("LuaUnit")
            val started = System.currentTimeMillis()
            val manager = FileEditorManager.getInstance(project)
            val host = project.getService(CodeVisionHost::class.java)
            if (host != null) {
                for (file in manager.openFiles) {
                    for (editor in manager.getAllEditors(file)) {
                        val textEditor = editor as? TextEditor ?: continue
                        // The host keeps one lens list per editor/provider and
                        // never asks the provider again on its own.
                        host.invalidateProvider(
                            CodeVisionHost.LensInvalidateSignal(
                                textEditor.editor,
                                listOf(LuaUnitFixtureCodeVision.ID)
                            )
                        )
                    }
                }
            }
            // Inlay hints remember the modification stamp they were built for:
            // a plain daemon restart keeps the old hints, and only this call
            // drops the stamps and runs the passes again.
            InlayHintsPassFactoryInternal.restartDaemonUpdatingHints(project, "LuaUnit: $reason")
            EditorNotifications.getInstance(project).updateAllNotifications()
            log.info("highlights refreshed ($reason) in ${System.currentTimeMillis() - started} ms")
        }
    }
}
