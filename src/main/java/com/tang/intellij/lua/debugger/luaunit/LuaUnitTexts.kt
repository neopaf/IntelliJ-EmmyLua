package com.tang.intellij.lua.debugger.luaunit

import java.nio.file.Files
import java.nio.file.Path

/**
 * Texts of the suite files the way the editor sees them.
 *
 * <p>The gutter icons must not read the disk: an edited file is stale there
 * until it is saved, and its line numbers no longer match the editor's. The
 * text is taken from the open document first (the live copy, unsaved edits
 * included), then from the VFS cache, and only when the file is unknown to
 * the VFS — from the disk itself.
 */
object LuaUnitTexts {

    /**
     * The text of [file]: the open document's copy, the VFS cache, or the disk.
     *
     * <p>The lookups touch the platform model (the VFS and the open documents),
     * and the callers are not always on the EDT: the tests console reads the
     * failing line of a case on the process reader thread. The read action
     * makes that legal.
     */
    // ReadAction.computeBlocking exists only in IDEA 2026.2.
    @Suppress("DEPRECATION")
    fun read(file: Path): String? =
        com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> { readNow(file) }

    private fun readNow(file: Path): String? {
        val virtual = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(file)
        if (virtual != null && virtual.isValid) {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance().getDocument(virtual)
            if (document != null) {
                return document.text
            }
            return try {
                com.intellij.openapi.vfs.VfsUtilCore.loadText(virtual)
            } catch (e: Exception) {
                null
            }
        }
        return try {
            Files.readString(file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The change stamp of [file]: the document's modification counter while
     * it is open (every keystroke moves it), the VFS one after a save, the
     * disk's mtime for a file the VFS does not know.
     */
    // ReadAction.computeBlocking exists only in IDEA 2026.2.
    @Suppress("DEPRECATION")
    fun stamp(file: Path): Long =
        com.intellij.openapi.application.ReadAction.compute<Long, RuntimeException> { stampNow(file) }

    private fun stampNow(file: Path): Long {
        val virtual = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(file)
        if (virtual != null && virtual.isValid) {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager
                .getInstance().getDocument(virtual)
            if (document != null) {
                return document.modificationStamp
            }
            return virtual.modificationStamp
        }
        return try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: Exception) {
            -1L
        }
    }
}

/**
 * A counter of the edits made in the IDE.
 *
 * <p>The discovery and the snapshot of the ignored cases cache their answer by
 * the stamps of the files they read: the stamp of a suite moves on its own
 * edit, but a data module the suite requires has no stamp among them — the
 * row a user just commented out would never show up in the gutter. The counter
 * moves on every document change, and the caches that read files it cannot
 * name drop their answers when it moves.
 */
object LuaUnitTextEdits {

    @Volatile
    var revision: Long = 0
        private set

    fun touch() {
        revision++
    }
}
