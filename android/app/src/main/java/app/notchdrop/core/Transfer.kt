package app.notchdrop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

sealed class OutgoingItem {
    class File(val name: String, val size: Long, val mime: String, val open: () -> InputStream) : OutgoingItem()
    class Text(val text: String) : OutgoingItem()

    val byteSize: Long get() = if (this is File) size else 0
}

data class ReceivedFile(val name: String, val uri: String, val mime: String)
data class ReceivedBatch(val files: List<ReceivedFile>, val texts: List<String>)

/** Where incoming files are written (MediaStore on Android, a temp dir in tests). */
interface ReceiveSink {
    fun begin(name: String, mime: String, size: Long): IncomingFile
}

interface IncomingFile {
    val output: OutputStream
    fun commit(): ReceivedFile
    fun abort()
}

/** (bytesDone, bytesTotal, currentName) */
typealias Progress = (Long, Long, String) -> Unit

object Transfer {
    fun send(items: List<OutgoingItem>, ch: SecureChannel, progress: Progress) {
        val manifest = JSONArray()
        for (item in items) {
            manifest.put(
                when (item) {
                    is OutgoingItem.File -> JSONObject().put("kind", "file").put("name", item.name)
                        .put("size", item.size).put("mime", item.mime)
                    is OutgoingItem.Text -> JSONObject().put("kind", "text").put("text", item.text)
                }
            )
        }
        ch.sendJson(JSONObject().put("t", "offer").put("items", manifest))
        ch.expect("accept")

        val total = items.sumOf { it.byteSize }
        var done = 0L
        progress(0, total, "")
        val buf = ByteArray(Wire.CHUNK)
        for (item in items) {
            if (item !is OutgoingItem.File) continue
            val md = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            item.open().use { input ->
                while (sent < item.size) {
                    val n = readFully(input, buf, minOf(buf.size.toLong(), item.size - sent).toInt())
                    if (n <= 0) throw NDException("${item.name} ended early")
                    md.update(buf, 0, n)
                    ch.sendChunk(buf, n)
                    sent += n
                    done += n
                    progress(done, total, item.name)
                }
            }
            ch.sendJson(JSONObject().put("t", "file_end").put("sha256", md.digest().toHex()))
        }
        ch.sendJson(JSONObject().put("t", "done"))
        ch.expect("received")
    }

    fun receive(offer: JSONObject, ch: SecureChannel, sink: ReceiveSink, progress: Progress): ReceivedBatch {
        val items = offer.getJSONArray("items")
        ch.sendJson(JSONObject().put("t", "accept"))

        var total = 0L
        for (i in 0 until items.length()) total += items.getJSONObject(i).optLong("size", 0)
        var done = 0L
        val files = mutableListOf<ReceivedFile>()
        val texts = mutableListOf<String>()
        progress(0, total, "")

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            when (item.optString("kind")) {
                "text" -> texts += item.optString("text")
                "file" -> {
                    val name = sanitize(item.optString("name", "file"))
                    val size = item.optLong("size", -1)
                    if (size < 0) throw NDException("bad size")
                    val incoming = sink.begin(name, item.optString("mime", "application/octet-stream"), size)
                    try {
                        val md = MessageDigest.getInstance("SHA-256")
                        var got = 0L
                        incoming.output.use { out ->
                            while (got < size) {
                                val m = ch.receive() as? WireMessage.Chunk ?: throw NDException("expected data")
                                if (got + m.data.size > size) throw NDException("too much data")
                                out.write(m.data)
                                md.update(m.data)
                                got += m.data.size
                                done += m.data.size
                                progress(done, total, name)
                            }
                        }
                        val end = ch.expect("file_end")
                        if (!end.optString("sha256").equals(md.digest().toHex(), ignoreCase = true)) {
                            throw NDException("checksum mismatch for $name")
                        }
                        files += incoming.commit()
                    } catch (e: Exception) {
                        incoming.abort()
                        throw e
                    }
                }
            }
        }
        ch.expect("done")
        ch.sendJson(JSONObject().put("t", "received").put("ok", true))
        return ReceivedBatch(files, texts)
    }

    fun sanitize(raw: String): String {
        var s = raw.substringAfterLast('/').replace(':', '-').trim().trimStart('.')
        if (s.isEmpty()) s = "file"
        return s.take(200)
    }

    private fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
        var n = 0
        while (n < want) {
            val r = input.read(buf, n, want - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Rate-limits progress callbacks to ~15/s plus the final one. */
class ProgressThrottle(private val emit: Progress) : Progress {
    private var last = 0L
    override fun invoke(done: Long, total: Long, name: String) {
        val now = System.currentTimeMillis()
        if (done >= total || now - last > 66) {
            last = now
            emit(done, total, name)
        }
    }
}
