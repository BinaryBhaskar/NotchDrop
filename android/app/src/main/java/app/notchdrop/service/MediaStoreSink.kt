package app.notchdrop.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import app.notchdrop.core.IncomingFile
import app.notchdrop.core.NDException
import app.notchdrop.core.ReceiveSink
import app.notchdrop.core.ReceivedFile
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
        return object : IncomingFile {
            override val output: OutputStream = out.buffered(256 * 1024)

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
        }
    }
}
