package com.amurot.notchdrop.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amurot.notchdrop.core.IncomingFile
import com.amurot.notchdrop.core.KeptFile
import com.amurot.notchdrop.core.KeptFiles
import com.amurot.notchdrop.core.NDException
import com.amurot.notchdrop.core.ReceiveSink
import com.amurot.notchdrop.core.ReceivedFile
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Saves into a folder the user picked with the system folder picker (any storage, SD cards included).
 * Files are written as "<name>.ndpart" and renamed when complete, so half-written files are easy to spot and clean up.
 */
class FolderSink(private val ctx: Context, private val tree: Uri) : ReceiveSink {
    override fun begin(name: String, mime: String, size: Long): IncomingFile {
        val resolver = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = runCatching { DocumentsContract.createDocument(resolver, parent, PART_MIME, name + PART) }.getOrNull()
            ?: throw NDException("Couldn't create $name in the save folder. Choose it again in Settings.")
        val out = resolver.openOutputStream(uri) ?: run {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw NDException("Couldn't open $name")
        }
        return PartFile(resolver, uri, name, mime, out)
    }

    companion object {
        const val PART = ".ndpart"
        /** A neutral type, so providers don't append an extension to the part name. */
        const val PART_MIME = "application/octet-stream"

        /** A readable label for a picked folder, like "Download/Mac" or "SD card/Photos". */
        fun label(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "Chosen folder"
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':', "")
            val root = if (volume.equals("primary", true)) "Internal storage" else if (':' in id) "SD card" else return id
            return if (path.isEmpty()) root else "$root/$path"
        }
    }
}

private class PartFile(
    private val resolver: ContentResolver, private val uri: Uri, private val name: String, private val mime: String,
    out: OutputStream,
) : IncomingFile {
    override val output: OutputStream = out.buffered(256 * 1024)
    override val ref: String get() = uri.toString()

    override fun commit(): ReceivedFile {
        runCatching { output.close() }
        // The provider may pick another name if this one is taken, and may hand back a new URI.
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, uri, name) }.getOrNull() ?: uri
        val finalName = resolver.query(renamed, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return ReceivedFile(finalName, renamed.toString(), mime)
    }

    override fun abort() {
        runCatching { output.close() }
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }

    override fun keep(): KeptFile {
        runCatching { output.close() }
        return Kept(resolver, uri, name, mime)
    }

    class Kept(private val resolver: ContentResolver, private val uri: Uri, private val name: String, private val mime: String) : KeptFile {
        override val ref: String get() = uri.toString()
        override fun read(): InputStream = resolver.openInputStream(uri) ?: throw NDException("Couldn't open $name")

        override fun reopen(length: Long): IncomingFile {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw NDException("Couldn't open $name")
            val fos = FileOutputStream(pfd.fileDescriptor)
            fos.channel.truncate(length)
            fos.channel.position(length)
            val out = object : FilterOutputStream(fos) {
                override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
                override fun close() { runCatching { fos.close() }; pfd.close() }
            }
            return PartFile(resolver, uri, name, mime, out)
        }

        override fun discard() { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
    }
}

/**
 * Kept files in either place: MediaStore downloads, or documents in the picked folder.
 * Refs say which: document URIs come from a documents provider, everything else is MediaStore.
 */
class SavedParts(private val ctx: Context, private val tree: () -> Uri?) : KeptFiles {
    private val media = MediaStoreParts(ctx)
    private val resolver = ctx.contentResolver

    private fun isDocument(ref: String) = DocumentsContract.isDocumentUri(ctx, Uri.parse(ref))

    override fun restore(ref: String): KeptFile? {
        if (!isDocument(ref)) return media.restore(ref)
        val uri = Uri.parse(ref)
        val name = runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: return null
        return PartFile.Kept(resolver, uri, name.removeSuffix(FolderSink.PART), FolderSink.PART_MIME)
    }

    override fun exists(uri: String): Boolean = if (isDocument(uri)) {
        runCatching { resolver.query(Uri.parse(uri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } }
            .getOrNull() == true
    } else media.exists(uri)

    /** Pending MediaStore entries, plus ".ndpart" files in the current save folder. */
    override fun strays(): List<String> {
        val t = tree() ?: return media.strays()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val parts = runCatching {
            resolver.query(children, cols, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getString(1)?.endsWith(FolderSink.PART) == true) {
                            add(DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)).toString())
                        }
                    }
                }
            }
        }.getOrNull().orEmpty()
        return media.strays() + parts
    }

    override fun delete(ref: String) {
        if (isDocument(ref)) runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(ref)) } else media.delete(ref)
    }
}
