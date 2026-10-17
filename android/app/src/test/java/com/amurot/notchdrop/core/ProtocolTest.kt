package com.amurot.notchdrop.core

import com.amurot.notchdrop.core.Transfer.toHex
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.InputStream
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ProtocolTest {
    /** Must match `NotchDrop --vectors` on the Mac. */
    @Test
    fun matchesMacVectors() {
        val master = ByteArray(32) { it.toByte() }
        val nc = ByteArray(32) { (0x40 + it).toByte() }
        val ns = ByteArray(32) { (0x80 + it).toByte() }
        val (c2s, s2c) = FrameCipher.derive(master, nc, ns)
        assertEquals("c645532299f95bc5d35c6d255e6a604ef4507ce126146dac1b347ee2e304e624", c2s.toHex())
        assertEquals("340c2f3a6a331ccf23c2c67358fc49e54a48164e9dfd73ed4c14fc45f9af41f8", s2c.toHex())
        val tx = FrameCipher(c2s)
        tx.seal("first".toByteArray())
        assertEquals("ad06e536b5c1d57758855e8e8f1312723a9612077f6bd1ed481b675c982913", tx.seal("hello notchdrop".toByteArray()).toHex())
    }

    private class MemSink : ReceiveSink {
        val files = mutableMapOf<String, ByteArray>()
        override fun begin(name: String, mime: String, size: Long) = object : IncomingFile {
            val buf = ByteArrayOutputStream()
            override val output: OutputStream = buf
            override fun commit(): ReceivedFile { files[name] = buf.toByteArray(); return ReceivedFile(name, "mem:$name", mime) }
            override fun abort() {}
        }
    }

    private inner class Node(val id: String, val key: ByteArray?) : SessionDelegate {
        val sink = MemSink()
        val finished = CountDownLatch(1)
        var batch: ReceivedBatch? = null
        var error: Exception? = null
        var control: TransferControl? = null
        /** Called with bytes received so far. */
        var onProgress: (Long) -> Unit = {}
        override val cancelNote = "Stopped on Test."
        override fun incomingStarted(control: TransferControl) { this.control = control }
        override val myId = id
        override fun keyFor(mode: String, from: String) = key
        override fun myHello(): JSONObject = JSONObject().put("t", "hello").put("id", id).put("name", "Test").put("platform", "test")
        override fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?) {}
        override fun sink() = sink
        override fun incomingProgress(done: Long, total: Long, name: String) = onProgress(done)
        override fun incomingFinished(batch: ReceivedBatch) { this.batch = batch; finished.countDown() }
        override fun incomingFailed(error: Exception) { this.error = error; finished.countDown() }
    }

    @Test
    fun loopbackTransfer() = runBlocking {
        val key = Wire.randomBytes(32)
        val node = Node("server", key)
        val server = ProtocolServer(0, node)
        val port = server.start()
        try {
            val hello = JSONObject().put("t", "hello").put("id", "client").put("name", "C").put("platform", "test")
            val wrong = runCatching {
                PeerLink.connect(listOf(InetSocketAddress("127.0.0.1", port)), "session", Wire.randomBytes(32), "client", hello)
            }
            assertTrue("wrong key must fail", wrong.isFailure)

            val dead = InetSocketAddress("127.0.0.1", 1)
            val c = PeerLink.connect(listOf(dead, InetSocketAddress("127.0.0.1", port)), "session", key, "client", hello)
            assertEquals("server", c.hello.getString("id"))

            val data = Random(7).nextBytes(3 * 1024 * 1024 + 123)
            val items = listOf(
                OutgoingItem.File("a.bin", data.size.toLong(), "application/octet-stream") { ByteArrayInputStream(data) },
                OutgoingItem.File("../evil:name.txt", 0, "text/plain") { ByteArrayInputStream(ByteArray(0)) },
                OutgoingItem.Text("https://example.com"),
            )
            Transfer.send(items, c.channel) { _, _, _ -> }
            c.channel.sendJson(JSONObject().put("t", "bye"))
            assertTrue(node.finished.await(10, TimeUnit.SECONDS))
            val b = node.batch!!
            assertArrayEquals(data, node.sink.files["a.bin"])
            assertTrue(node.sink.files.containsKey("evil-name.txt"))
            assertEquals(listOf("https://example.com"), b.texts)
        } finally {
            server.stop()
        }
    }

    /** `size` zero bytes without holding them in memory. */
    private fun zeros(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            b.fill(0, off, off + n)
            left -= n
            return n
        }
    }

    private fun bigFile() = listOf(OutgoingItem.File("big.bin", 512L * 1024 * 1024, "application/octet-stream") { zeros(512L * 1024 * 1024) })

    private suspend fun connect(port: Int, key: ByteArray) = PeerLink.connect(
        listOf(InetSocketAddress("127.0.0.1", port)), "session", key, "client",
        JSONObject().put("t", "hello").put("id", "client").put("name", "C").put("platform", "test"),
    )

    @Test
    fun receiverCancelStopsSender() = runBlocking {
        val key = Wire.randomBytes(32)
        val node = Node("server", key)
        node.onProgress = { done -> if (done > 4 * 1024 * 1024) node.control?.cancel() }
        val server = ProtocolServer(0, node)
        val port = server.start()
        try {
            val c = connect(port, key)
            try {
                Transfer.send(bigFile(), c.channel) { _, _, _ -> }
                fail("send should have been cancelled")
            } catch (e: CancelledException) {
                assertEquals("Stopped on Test.", e.message)
            }
            assertTrue(node.finished.await(10, TimeUnit.SECONDS))
            assertTrue(node.error is CancelledException)
            assertEquals(TransferControl.YOU_CANCELLED, node.error?.message)
        } finally {
            server.stop()
        }
    }

    @Test
    fun senderCancelStopsReceiver() = runBlocking {
        val key = Wire.randomBytes(32)
        val node = Node("server", key)
        val server = ProtocolServer(0, node)
        val port = server.start()
        try {
            val c = connect(port, key)
            val control = TransferControl("Stopped on Sender.")
            control.attach(c.channel, receiving = false)
            try {
                Transfer.send(bigFile(), c.channel, control) { done, _, _ -> if (done > 4 * 1024 * 1024) control.cancel() }
                fail("send should have been cancelled")
            } catch (e: CancelledException) {
                assertEquals(TransferControl.YOU_CANCELLED, e.message)
            }
            assertTrue(node.finished.await(10, TimeUnit.SECONDS))
            assertTrue("got ${node.error}", node.error is CancelledException)
            assertEquals("Stopped on Sender.", node.error?.message)
        } finally {
            server.stop()
        }
    }
}
