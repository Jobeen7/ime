package com.ninthsoft.ime.ui

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Build
import android.provider.DocumentsContract
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class AppFilesDocumentsProvider : DocumentsProvider() {
    private lateinit var filesRoot: File
    private lateinit var docIdPrefix: String

    private val File.documentId: String
        get() = absolutePath.removePrefix(docIdPrefix)

    override fun onCreate(): Boolean {
        filesRoot = context?.getExternalFilesDir(null) ?: return false
        docIdPrefix = "${filesRoot.parent}${File.separator}"
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, filesRoot.documentId)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, com.ninthsoft.ime.R.mipmap.ic_launcher)
                add(Root.COLUMN_TITLE, context!!.getString(com.ninthsoft.ime.R.string.app_name))
                add(Root.COLUMN_MIME_TYPES, MIME_TYPE_WILDCARD)
            }
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
            newRowFromFile(fileFromDocumentId(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        val parent = fileFromDocumentId(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        parent.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?.forEach { newRowFromFile(it) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        fileFromDocumentId(documentId), ParcelFileDescriptor.parseMode(mode)
    )

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val file = fileFromDocumentId(documentId)
        val pfd = ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.MODE_READ_ONLY
        )
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        validateDisplayName(displayName)
        val parent = fileFromDocumentId(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException(parentDocumentId)
        }

        val file = createAbstractFile(parent, displayName)

        try {
            val success = if (mimeType == Document.MIME_TYPE_DIR) {
                file.mkdir()
            } else {
                file.createNewFile()
            }
            if (!success) {
                throw FileNotFoundException(file.path)
            }
        } catch (e: IOException) {
            throw FileNotFoundException("create failed: ${e.message}")
        }

        notifyChanged(parentDocumentId)
        return file.documentId
    }

    override fun deleteDocument(documentId: String) {
        val file = fileFromDocumentId(documentId)
        if (file == filesRoot) {
            throw FileNotFoundException("cannot delete root")
        }

        val success = if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }

        if (!success) {
            throw FileNotFoundException(documentId)
        }

        notifyChanged(parentDocumentId(documentId))
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String {
        validateDisplayName(displayName)

        val source = fileFromDocumentId(documentId)
        val target = source.resolveSibling(displayName)

        if (target.exists() || !source.renameTo(target)) {
            throw FileNotFoundException(
                "rename failed: $documentId -> $displayName"
            )
        }

        notifyChanged(parentDocumentId(documentId))
        return target.documentId
    }

    override fun copyDocument(
        sourceDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = fileFromDocumentId(sourceDocumentId)
        val targetParent = fileFromDocumentId(targetParentDocumentId)

        if (!targetParent.isDirectory) {
            throw FileNotFoundException(targetParentDocumentId)
        }

        val target = createAbstractFile(targetParent, source.name)

        try {
            if (source.isDirectory) {
                source.copyRecursively(target)
            } else {
                source.copyTo(target)
            }
        } catch (e: Exception) {
            throw FileNotFoundException("copy failed: ${e.message}")
        }

        notifyChanged(targetParentDocumentId)
        return target.documentId
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val source = fileFromDocumentId(sourceDocumentId)
        val targetParent = fileFromDocumentId(targetParentDocumentId)

        if (!targetParent.isDirectory) {
            throw FileNotFoundException(targetParentDocumentId)
        }

        val target = createAbstractFile(targetParent, source.name)

        if (!source.renameTo(target)) {
            throw FileNotFoundException(
                "move failed: $sourceDocumentId"
            )
        }

        notifyChanged(sourceParentDocumentId)
        notifyChanged(targetParentDocumentId)
        return target.documentId
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?,
    ): Cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION).apply {
        val keyword = query.lowercase()
        fileFromDocumentId(
            filesRoot.documentId
        ).walk().filter {
            it.name.lowercase().contains(keyword)
        }.take(50).forEach {
            newRowFromFile(it)
        }
    }

    override fun getDocumentType(documentId: String): String =
        fileFromDocumentId(documentId).mimeType

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        val parent = fileFromDocumentId(parentDocumentId).canonicalFile
        val child = fileFromDocumentId(documentId).canonicalFile

        return child.path != parent.path && child.path.startsWith(parent.path + File.separator)
    }

    private fun fileFromDocumentId(documentId: String): File {
        val file = File(docIdPrefix, documentId).canonicalFile
        val root = filesRoot.canonicalFile

        if (file != root && !file.path.startsWith(root.path + File.separator)) {
            throw FileNotFoundException(documentId)
        }

        return file
    }

    private fun createAbstractFile(
        parent: File,
        displayName: String,
    ): File {
        var file = parent.resolve(displayName)
        var index = 2

        while (file.exists()) {
            file = parent.resolve("$displayName ($index)")
            index++
        }

        return file
    }

    private fun MatrixCursor.newRowFromFile(file: File) {
        if (!file.exists()) {
            throw FileNotFoundException(file.path)
        }

        val mimeType = file.mimeType

        var flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Document.FLAG_SUPPORTS_COPY
        } else {
            0
        }

        if (file.canWrite()) {
            flags = flags or if (file.isDirectory) {
                Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                Document.FLAG_SUPPORTS_WRITE
            }
        }

        if (file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or Document.FLAG_SUPPORTS_MOVE
            }
        }

        if (mimeType.startsWith("image/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }

        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.documentId)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(
                Document.COLUMN_SIZE, if (file.isFile) file.length() else null
            )
            add(Document.COLUMN_ICON, com.ninthsoft.ime.R.mipmap.ic_launcher)
        }
    }

    private fun validateDisplayName(name: String) {
        require(
            name.isNotBlank() && name != "." && name != ".."
        )
        require(
            !name.contains('/') && !name.contains(File.separatorChar)
        )
    }

    private fun parentDocumentId(documentId: String): String = documentId.substringBeforeLast(
        '/', filesRoot.documentId
    )

    private fun notifyChanged(documentId: String) {
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(
                AUTHORITY, documentId
            ), null
        )
    }

    private val File.mimeType: String
        get() = when {
            isDirectory -> Document.MIME_TYPE_DIR
            extension.lowercase() in TEXT_EXTENSIONS -> MIME_TYPE_TEXT
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
                ?: MIME_TYPE_BIN
        }

    companion object {
        const val AUTHORITY = "com.ninthsoft.ime.files.documents"
        const val ROOT_ID = "files"

        private const val MIME_TYPE_WILDCARD = "*/*"
        private const val MIME_TYPE_TEXT = "text/plain"
        private const val MIME_TYPE_BIN = "application/octet-stream"

        private val TEXT_EXTENSIONS = setOf(
            "txt",
            "dict",
            "yaml",
            "yml",
            "json",
            "conf",
            "ini",
            "lua",
            "mb",
        )

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
