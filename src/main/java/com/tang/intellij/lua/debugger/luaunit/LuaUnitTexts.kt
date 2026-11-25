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
