package com.amurot.notchdrop.service

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

/** Saves into Download/From <Mac> via MediaStore; no storage permission needed. */
class MediaStoreSink(private val ctx: Context, macName: String) : ReceiveSink {
    private val folder = "${Environment.DIRECTORY_DOWNLOADS}/From ${macName.replace('/', '-')}"

    override fun begin(name: String, mime: String, size: Long): IncomingFile {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw NDException("Couldn't create $name")
        val out = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw NDException("Couldn't open $name")
        }
        return PendingEntry(resolver, uri, name, mime, out)
    }
}

/**
 * A MediaStore download still marked pending (hidden from other apps) while it is written. Kept after an
 * interruption by leaving it pending; Android deletes pending entries nobody finishes after about a week.
 */
private class PendingEntry(
    private val resolver: ContentResolver, private val uri: Uri, private val name: String, private val mime: String,
    out: OutputStream,
) : IncomingFile {
    override val output: OutputStream = out.buffered(256 * 1024)
    override val ref: String get() = refOf(uri)

    override fun commit(): ReceivedFile {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        // MediaStore may have de-duplicated the name ("photo (1).jpg").
        val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return ReceivedFile(finalName, uri.toString(), mime)
    }

    override fun abort() {
        runCatching { output.close() }
        runCatching { resolver.delete(uri, null, null) }
    }

    override fun keep(): KeptFile {
        runCatching { output.close() }
        return Kept(resolver, uri, name, mime)
    }

    class Kept(private val resolver: ContentResolver, private val uri: Uri, private val name: String, private val mime: String) : KeptFile {
        override val ref: String get() = refOf(uri)
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
            return PendingEntry(resolver, uri, name, mime, out)
        }

        override fun discard() { resolver.delete(uri, null, null) }
    }
}

/** Finds this app's pending downloads again after a restart, and the ones nothing will finish. */
class MediaStoreParts(ctx: Context) : KeptFiles {
    private val resolver = ctx.contentResolver
    private val pkg = ctx.packageName

    override fun restore(ref: String): KeptFile? {
        val uri = Uri.parse(ref)
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE), null, null, null)?.use {
            if (it.moveToFirst()) PendingEntry.Kept(resolver, uri, it.getString(0) ?: "file", it.getString(1) ?: "application/octet-stream")
            else null
        }
    }

    override fun exists(uri: String): Boolean =
        runCatching { resolver.query(Uri.parse(uri), arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { it.count > 0 } }
            .getOrNull() == true

    override fun strays(): List<String> {
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.Downloads._ID)
        val where = "${MediaStore.Downloads.IS_PENDING} = 1 AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?"
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(pkg))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
            resolver.query(base, cols, args, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(base), cols, where, arrayOf(pkg), null)
        }
        return cursor?.use { c -> List(c.count) { c.moveToPosition(it); ContentUris.withAppendedId(base, c.getLong(0)).toString() } }.orEmpty()
    }

    override fun delete(ref: String) { resolver.delete(Uri.parse(ref), null, null) }
}

/** The same entry can come back under different volume names; refs always use the plain downloads URI and the id. */
private fun refOf(uri: Uri): String =
    runCatching { ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentUris.parseId(uri)).toString() }
        .getOrDefault(uri.toString())
