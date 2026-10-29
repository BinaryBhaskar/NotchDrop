package com.amurot.notchdrop.stream

import com.amurot.notchdrop.core.PeerLink
import com.amurot.notchdrop.core.WireMessage
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * One live stream to the Mac over an authenticated session. Video goes out as chunks
 * (flags, pts µs, Annex-B data); everything else is JSON both ways.
 *
 * Frames are queued and sent from their own thread so a slow network never blocks the encoder.
 * When the queue backs up, it is emptied and frames are dropped until the next keyframe.
 */
class StreamLink(
    private val c: PeerLink.Connected,
    private val onMessage: (JSONObject) -> Unit,
    private val onKeyframeNeeded: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private class Frame(val flags: Int, val pts: Long, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Frame>()
    @Volatile private var open = true
    @Volatile private var skipToKey = false
    private var closedReported = false

    fun start(header: JSONObject) {
        c.channel.sendJson(header.put("t", "stream"))
        // The Mac only talks when you use the controls, so no read timeout.
        c.channel.conn.socket.soTimeout = 0
        thread(name = "notchdrop-stream-tx", isDaemon = true) { writeLoop() }
        thread(name = "notchdrop-stream-rx", isDaemon = true) { readLoop() }
    }

    val isOpen: Boolean get() = open

    /** Called from the encoder thread. */
    fun frame(flags: Int, pts: Long, data: ByteArray) {
        if (!open) return
        val key = flags and 1 != 0
        val config = flags and 2 != 0
        if (config) {
            queue.put(Frame(flags, pts, data))
            return
        }
        if (skipToKey) {
            if (!key) return
            skipToKey = false
        }
        // About a second of video waiting means the network can't keep up: start over at a keyframe.
        if (queue.size > BACKLOG) {
            queue.removeIf { it.flags and 2 == 0 }
            if (!key) {
                skipToKey = true
                onKeyframeNeeded()
                return
            }
        }
        queue.put(Frame(flags, pts, data))
    }

    fun send(msg: JSONObject) {
        if (!open) return
        runCatching { c.channel.sendJson(msg) }.onFailure { close() }
    }

    fun close() {
        if (!open) return
        open = false
        runCatching { c.channel.sendJson(JSONObject().put("t", "bye")) }
        runCatching { c.channel.close() }
        queue.clear()
        reportClosed()
    }

    @Synchronized
    private fun reportClosed() {
        if (closedReported) return
        closedReported = true
        onClosed()
    }

    private fun writeLoop() {
        try {
            while (open) {
                val f = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val buf = ByteBuffer.allocate(9 + f.data.size)
                buf.put(f.flags.toByte()).putLong(f.pts).put(f.data)
                c.channel.sendChunk(buf.array())
            }
        } catch (_: Exception) {
            open = false
            runCatching { c.channel.close() }
            reportClosed()
        }
    }

    private fun readLoop() {
        try {
            while (open) {
                val m = c.channel.receive() as? WireMessage.Json ?: continue
                if (m.obj.optString("t") == "bye") break
                onMessage(m.obj)
            }
        } catch (_: Exception) {
        }
        open = false
        runCatching { c.channel.close() }
        reportClosed()
    }

    private companion object {
        const val BACKLOG = 45
    }
}
