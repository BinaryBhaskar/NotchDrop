package app.notchdrop.service

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.notchdrop.core.OutgoingItem
import java.io.File

object Outgoing {
    /** Turns shared content URIs into protocol items. Streams of unknown length are copied to cache first. */
    fun fromUris(ctx: Context, uris: List<Uri>): List<OutgoingItem> {
        val resolver = ctx.contentResolver
        return uris.mapIndexed { i, uri ->
            var name: String? = null
            var size: Long? = null
            if (uri.scheme == "content") {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.let { name = it.name; size = it.length() }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared-${i + 1}"
            val known = size
            if (known != null && known >= 0) {
                OutgoingItem.File(fileName, known, mime) { resolver.openInputStream(uri) ?: error("Can't read $fileName") }
            } else {
                val dir = File(ctx.cacheDir, "outgoing").apply { mkdirs() }
                val tmp = File(dir, "${System.nanoTime()}-$i")
                resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("Can't read $fileName")
                tmp.deleteOnExit()
                OutgoingItem.File(fileName, tmp.length(), mime) { tmp.inputStream() }
            }
        }
    }

    fun summary(items: List<OutgoingItem>): String {
        val files = items.filterIsInstance<OutgoingItem.File>()
        val texts = items.filterIsInstance<OutgoingItem.Text>()
        return when {
            files.size == 1 && texts.isEmpty() -> files[0].name
            files.isEmpty() && texts.size == 1 -> texts[0].text.trim().take(80)
            files.isNotEmpty() && texts.isEmpty() -> "${files.size} files"
            else -> "${items.size} items"
        }
    }
}
