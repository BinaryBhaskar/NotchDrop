package com.amurot.notchdrop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.concurrent.thread

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
    /** Closes the file but keeps what was written, for a resumed transfer. Null if this sink can't. */
    fun keep(): KeptFile? = null
    /** Names the unfinished file so it can be found and deleted after a crash; see [KeptFile.ref]. */
    val ref: String? get() = null
}

/** (bytesDone, bytesTotal, currentName) */
typealias Progress = (Long, Long, String) -> Unit

object Transfer {
    const val CONNECTION_LOST = "Connection lost. Check that both devices are still on the same network."

    /**
     * `id` names the batch so an interrupted one can be resumed: sending the same items with the same id
     * lets the receiver answer with how much of each file it already has.
     */
    fun send(items: List<OutgoingItem>, ch: SecureChannel, control: TransferControl? = null, id: String? = null, progress: Progress) {
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
        ch.sendJson(JSONObject().put("t", "offer").put("items", manifest).apply { if (id != null) put("id", id) })
        val have = ch.expect("accept").optJSONArray("resume")

        // The receiver may cancel mid-stream, so its reply is read while the data is still going out.
        val reply = ReplyReader(ch)
        val watchdog = Watchdog(ch)
        try {
            val total = items.sumOf { it.byteSize }
            val offsets = items.mapIndexed { i, item ->
                val o = if (have != null && i < have.length()) have.optLong(i, 0) else 0
                if (o == ResumeStore.COMPLETE) item.byteSize else o.coerceIn(0, item.byteSize)
            }
            var done = offsets.sum()
            progress(done, total, "")
            val buf = ByteArray(Wire.CHUNK)
            for ((i, item) in items.withIndex()) {
                if (item !is OutgoingItem.File) continue
                if (have != null && i < have.length() && have.optLong(i, 0) == ResumeStore.COMPLETE) continue // already there
                val md = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                item.open().use { input ->
                    // The receiver already has the start; hash it locally so the checksum still covers the whole file.
                    while (sent < offsets[i]) {
                        val n = readFully(input, buf, minOf(buf.size.toLong(), offsets[i] - sent).toInt())
                        if (n <= 0) throw NDException("${item.name} changed since the last attempt")
                        md.update(buf, 0, n)
                        sent += n
                    }
                    while (sent < item.size) {
                        if (control?.cancelled == true) throw CancelledException(TransferControl.YOU_CANCELLED)
                        reply.error?.let { throw it }
                        val n = readFully(input, buf, minOf(buf.size.toLong(), item.size - sent).toInt())
                        if (n <= 0) throw NDException("${item.name} ended early")
                        md.update(buf, 0, n)
                        ch.sendChunk(buf, n)
                        watchdog.tick()
                        sent += n
                        done += n
                        progress(done, total, item.name)
                    }
                }
                ch.sendJson(JSONObject().put("t", "file_end").put("sha256", md.digest().toHex()))
            }
            ch.sendJson(JSONObject().put("t", "done"))
            watchdog.stop()
            reply.await(Wire.STALL_MS.toLong())
        } catch (e: Exception) {
            if (e is CancelledException && control?.cancelled == true) {
                runCatching { ch.sendJson(Wire.cancelFrame(control.note)) }
                throw e
            }
            // A failed write usually means the receiver hung up; prefer its reason (e.g. it cancelled).
            val remote = reply.awaitError(1000)
            if (remote is CancelledException) throw remote
            if (watchdog.fired || e is SocketTimeoutException) throw NDException(CONNECTION_LOST)
            throw remote ?: if (e is SocketException) NDException(CONNECTION_LOST) else e
        } finally {
            watchdog.stop()
        }
    }

    /** With a `resume` store, an interrupted batch keeps its partial files so the sender can pick up where it stopped. */
    fun receive(
        offer: JSONObject, ch: SecureChannel, sink: ReceiveSink, control: TransferControl? = null,
        resume: ResumeStore? = null, progress: Progress,
    ): ReceivedBatch {
        val partial = PartialBatch(offer.optString("id").takeIf { it.isNotEmpty() }, ResumeStore.signature(offer))
        return try {
            receiveItems(offer, ch, sink, resume, partial, progress)
        } catch (e: Exception) {
            val stopped = control?.cancelled == true || e is CancelledException
            if (resume != null && !stopped && partial.id != null) resume.keep(partial) else partial.discard()
            // Cancelling closes the socket under the read; report that as the cancel it was.
            if (control?.cancelled == true) throw CancelledException(TransferControl.YOU_CANCELLED)
            if (e is SocketTimeoutException || e is SocketException) throw NDException(CONNECTION_LOST)
            throw e
        }
    }

    private fun receiveItems(
        offer: JSONObject, ch: SecureChannel, sink: ReceiveSink, resume: ResumeStore?, partial: PartialBatch, progress: Progress,
    ): ReceivedBatch {
        val items = offer.getJSONArray("items")
        val prior = resume?.take(partial.id, partial.signature)
        if (prior != null) { partial.parts += prior.parts; partial.done += prior.done } // still ours if this attempt fails too
        val accept = JSONObject().put("t", "accept")
        if (prior != null) accept.put("resume", JSONArray(List(items.length()) { prior.offset(it) }))
        ch.sendJson(accept)

        var total = 0L
        var done = 0L
        for (i in 0 until items.length()) {
            val size = items.getJSONObject(i).optLong("size", 0)
            total += size
            val o = prior?.offset(i) ?: 0
            done += if (o == ResumeStore.COMPLETE) size else o
        }
        val files = mutableListOf<ReceivedFile>()
        val texts = mutableListOf<String>()
        progress(done, total, "")

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            when (item.optString("kind")) {
                "text" -> texts += item.optString("text")
                "file" -> {
                    val name = sanitize(item.optString("name", "file"))
                    val size = item.optLong("size", -1)
                    if (size < 0) throw NDException("bad size")
                    val already = prior?.done?.get(i)
                    if (already != null) { files += already; continue } // arrived before the interruption
                    val md = MessageDigest.getInstance("SHA-256")
                    var got = 0L
                    val kept = partial.parts[i]
                    val incoming = if (kept?.file != null && prior?.offset(i) == kept.length) {
                        // Resuming: re-hash what is already stored, then append.
                        kept.file.read().use { input ->
                            val b = ByteArray(Wire.CHUNK)
                            while (got < kept.length) {
                                val n = input.read(b, 0, minOf(b.size.toLong(), kept.length - got).toInt())
                                if (n < 0) break
                                md.update(b, 0, n)
                                got += n
                            }
                        }
                        if (got != kept.length) throw NDException("partial file for $name is damaged")
                        kept.file.reopen(got)
                    } else {
                        kept?.file?.let { runCatching { it.discard() } }
                        sink.begin(name, item.optString("mime", "application/octet-stream"), size)
                            .also { f -> f.ref?.let { resume?.track(it) } } // so a crash mid-file doesn't leave it behind
                    }
                    try {
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
                        val f = incoming.commit()
                        incoming.ref?.let { resume?.untrack(it) }
                        partial.parts.remove(i)
                        partial.done[i] = f
                        files += f
                    } catch (e: Exception) {
                        // A corrupt or rejected file starts over next time; anything else can be resumed.
                        val keep = e !is NDException || e.message == CONNECTION_LOST
                        val k = if (keep) runCatching { incoming.keep() }.getOrNull() else null
                        if (k != null) partial.parts[i] = PartialBatch.Part(k, got) else {
                            partial.parts.remove(i)
                            incoming.abort()
                            incoming.ref?.let { resume?.untrack(it) }
                        }
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

/**
 * Lets the UI stop a running transfer. Either side may cancel; the other side is told with a
 * cancel frame so it can say who stopped it instead of reporting a broken connection.
 */
class TransferControl(val note: String) {
    @Volatile var cancelled = false
        private set
    private var channel: SecureChannel? = null
    private var receiving = false

    fun attach(ch: SecureChannel, receiving: Boolean) {
        val late = synchronized(this) {
            channel = ch
            this.receiving = receiving
            cancelled
        }
        if (late) hangUp(ch, receiving)
    }

    fun cancel() {
        val (ch, receiving) = synchronized(this) {
            cancelled = true
            channel to receiving
        }
        ch?.let { hangUp(it, receiving) } // still connecting: the sender checks the flag once connected
    }

    private fun hangUp(ch: SecureChannel, receiving: Boolean) = thread(isDaemon = true, name = "notchdrop-cancel") {
        if (receiving) {
            // The receive loop is blocked on a read, so tell the sender from here.
            val t = thread(isDaemon = true) { runCatching { ch.sendJson(Wire.cancelFrame(note)) } }
            t.join(1000)
            // Closing now, with the sender's data still unread, would reset the connection and could
            // drop the cancel frame before the sender reads it. Half-close instead: the sender reads
            // the frame and hangs up, which ends the receive loop. Force it only if that doesn't happen.
            runCatching { ch.conn.socket.shutdownOutput() }
            val deadline = System.currentTimeMillis() + 2500
            while (!ch.conn.socket.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        } else {
            // The send loop notices between chunks and tells the receiver; hang up anyway if it is stuck.
            Thread.sleep(2000)
        }
        ch.close()
    }

    companion object {
        const val YOU_CANCELLED = "You stopped the transfer."
    }
}

/** Reads the receiver's final answer on its own thread while the sender is still writing. */
private class ReplyReader(private val ch: SecureChannel) {
    @Volatile var error: Exception? = null
        private set
    private val reader: Thread

    init {
        ch.conn.socket.soTimeout = 0 // waits for the whole transfer; stalls are caught by the watchdog
        reader = thread(isDaemon = true, name = "notchdrop-reply") {
            try {
                ch.expect("received")
            } catch (e: Exception) {
                error = e
            }
        }
    }

    fun await(timeoutMs: Long) {
        reader.join(timeoutMs)
        if (reader.isAlive) {
            ch.close()
            throw NDException(Transfer.CONNECTION_LOST)
        }
        error?.let { throw it }
    }

    fun awaitError(timeoutMs: Long): Exception? {
        reader.join(timeoutMs)
        return error
    }
}

/** Closes the connection when sending stops making progress; socket writes have no timeout of their own. */
private class Watchdog(private val ch: SecureChannel) {
    @Volatile private var last = System.currentTimeMillis()
    @Volatile private var running = true
    @Volatile var fired = false
        private set

    init {
        thread(isDaemon = true, name = "notchdrop-watchdog") {
            while (running) {
                Thread.sleep(1000)
                if (running && System.currentTimeMillis() - last > Wire.STALL_MS) {
                    fired = true
                    ch.close()
                    break
                }
            }
        }
    }

    fun tick() { last = System.currentTimeMillis() }
    fun stop() { running = false }
}
