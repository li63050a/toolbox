package com.toolbox.app.fs

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.toolbox.app.R
import java.io.File
import java.io.FileNotFoundException

/**
 * 虚拟磁盘：在系统文件管理器中挂载一块指向 /data/data/com.toolbox.app 的卷。
 * files 目录可读写，databases / shared_prefs / cache 仅可读。
 */
class ToolboxDocumentsProvider : DocumentsProvider() {

    private val rootDocId: String
        get() = context?.dataDir?.absolutePath ?: "/"

    private fun fileFor(docId: String): File = File(docId)

    private fun isRoot(docId: String): Boolean = docId == rootDocId

    private fun isDataSubdir(file: File): Boolean =
        file.absolutePath.startsWith(rootDocId + File.separator)

    /** 仅 files 子目录开放写 */
    private fun writable(file: File): Boolean {
        val root = File(rootDocId)
        val filesDir = File(root, "files")
        return file.absolutePath.startsWith(filesDir.absolutePath + File.separator) ||
            file.absolutePath == filesDir.absolutePath
    }

    override fun onCreate(): Boolean = true

    private fun resolveProjection(projection: Array<out String>?): Array<out String> =
        projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )

    private val defaultRootProjection = arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_ICON
    )

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: defaultRootProjection
        val c = MatrixCursor(cols)
        val row = c.newRow()
        row.add(Root.COLUMN_ROOT_ID, "toolbox_root")
        row.add(Root.COLUMN_DOCUMENT_ID, rootDocId)
        row.add(Root.COLUMN_TITLE, context?.getString(R.string.app_name) ?: "Toolbox")
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("文件不存在: $documentId")
        return buildCursor(projection, file)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = fileFor(parentDocumentId)
        val c = MatrixCursor(resolveProjection(projection))
        parent.listFiles()
            ?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
            ?.forEach { c.addRow(documentRow(it)) }
        return c
    }

    private fun buildCursor(projection: Array<out String>?, file: File): Cursor {
        val c = MatrixCursor(resolveProjection(projection))
        c.addRow(documentRow(file))
        return c
    }

    private fun documentRow(file: File): Array<Any?> = arrayOf(
        file.absolutePath,
        file.name,
        if (file.isDirectory) Document.MIME_TYPE_DIR else mimeFor(file),
        file.length(),
        file.lastModified(),
        flagsFor(file)
    )

    private fun mimeFor(file: File): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private fun flagsFor(file: File): Int {
        if (file.isDirectory) {
            return Document.FLAG_DIR_PREFERS_LAST_MODIFIED or Document.FLAG_DIR_SUPPORTS_CREATE
        }
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (writable(file)) flags = flags or Document.FLAG_SUPPORTS_WRITE
        return flags
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (!file.exists() || file.isDirectory) throw FileNotFoundException("无法打开: $documentId")
        val wantsWrite = mode.contains("w") || mode.contains("rw")
        if (wantsWrite && !writable(file)) throw SecurityException("只读区域")
        val fdMode = if (wantsWrite) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, fdMode)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("文件不存在: $documentId")
        return rememberThumbnail(file)?.let { AssetFileDescriptor(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY), 0, -1) }
            ?: throw FileNotFoundException("无缩略图")
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("父目录不存在")
        val newFile = if (Document.MIME_TYPE_DIR == mimeType) {
            uniqueFile(File(parent, displayName))
        } else {
            uniqueFile(File(parent, fixName(displayName)))
        }
        val ok = if (newFile.isDirectory) newFile.mkdirs() else newFile.createNewFile()
        if (!ok) throw RuntimeException("创建失败")
        return newFile.absolutePath
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileFor(documentId)
        if (!isDataSubdir(file)) throw SecurityException("只读区域")
        val target = File(file.parentFile, fixName(displayName))
        if (!file.renameTo(target)) throw RuntimeException("重命名失败")
        return target.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFor(documentId)
        if (!isDataSubdir(file)) throw SecurityException("只读区域")
        fun remove(f: File) {
            if (f.isDirectory) f.listFiles()?.forEach { remove(it) }
            if (!f.delete()) throw RuntimeException("删除失败: ${f.name}")
        }
        remove(file)
    }

    private fun uniqueFile(candidate: File): File {
        if (!candidate.exists()) return candidate
        val name = candidate.name
        var i = 1
        while (true) {
            val renamed = File(candidate.parentFile, "$name ($i)")
            if (!renamed.exists()) return renamed
            i++
        }
    }

    private fun fixName(name: String): String = name.trim().ifBlank { "未命名" }

    private fun rememberThumbnail(file: File): File? = null // 大文件缩略图交给系统，返回 null 时系统按图标显示
}