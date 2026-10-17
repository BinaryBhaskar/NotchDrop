package com.amurot.notchdrop.core

import com.amurot.notchdrop.core.Transfer.toHex
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Talks to the real Mac binary (`mac/.build/debug/NotchDrop`). Skipped if it hasn't been built. */
class InteropTest {
    private val bin = System.getenv("ND_MAC_BIN")?.let(::File)

    private fun mac(vararg args: String): Process {
        assumeTrue("Mac binary not built", bin?.canExecute() == true)
        return ProcessBuilder(listOf(bin!!.path) + args).redirectErrorStream(false).start()
    }

    @Test
    fun androidSendsToMac() = runBlocking {
        val key = Wire.randomBytes(32)
        val p = mac("--interop-server", key.toHex())
        val out = p.inputStream.bufferedReader()
        val port = out.readLine().removePrefix("port=").toInt()

        val data = Random(1).nextBytes(1_500_000)
        val hello = JSONObject().put("t", "hello").put("id", "client-id").put("name", "Test Phone").put("platform", "android")
        val c = PeerLink.connect(listOf(InetSocketAddress("127.0.0.1", port)), "session", key, "client-id", hello)
        assertEquals("server-id", c.hello.getString("id"))
        Transfer.send(
            listOf(
                OutgoingItem.File("IMG_1.jpg", data.size.toLong(), "image/jpeg") { ByteArrayInputStream(data) },
                OutgoingItem.Text("héllo 👋"),
            ),
            c.channel,
        ) { _, _, _ -> }
        c.channel.sendJson(JSONObject().put("t", "bye"))

        val lines = generateSequence { out.readLine() }.takeWhile { it != "DONE" }.toList()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        val sha = MessageDigest.getInstance("SHA-256").digest(data).toHex()
        assertEquals(listOf("file=IMG_1.jpg sha=$sha", "text=héllo 👋"), lines)
    }

    @Test
    fun macSendsToAndroid() {
        val key = Wire.randomBytes(32)
        val files = mutableMapOf<String, ByteArray>()
        val done = CountDownLatch(1)
        var texts = emptyList<String>()
        val node = object : SessionDelegate {
            override val myId = "server-id"
            override fun keyFor(mode: String, from: String) = if (from == "client-id") key else null
            override fun myHello(): JSONObject = JSONObject().put("t", "hello").put("id", myId).put("name", "Test Phone")
            override fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?) {}
            override fun sink() = object : ReceiveSink {
                override fun begin(name: String, mime: String, size: Long) = object : IncomingFile {
                    val buf = ByteArrayOutputStream()
                    override val output: OutputStream = buf
                    override fun commit(): ReceivedFile { files[name] = buf.toByteArray(); return ReceivedFile(name, name, mime) }
                    override fun abort() {}
                }
            }
            override fun incomingProgress(done: Long, total: Long, name: String) {}
            override fun incomingFinished(batch: ReceivedBatch) { texts = batch.texts; done.countDown() }
            override fun incomingFailed(error: Exception) { error.printStackTrace(); done.countDown() }
        }
        val server = ProtocolServer(0, node)
        val port = server.start()
        try {
            val data = Random(2).nextBytes(700_000)
            val f = File.createTempFile("report", ".pdf").apply { writeBytes(data); deleteOnExit() }
            val p = mac("--interop-send", port.toString(), key.toHex(), f.path, "https://example.com/x")
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertEquals(p.inputStream.bufferedReader().readText(), 0, p.exitValue())
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertArrayEquals(data, files[f.name])
            assertEquals(listOf("https://example.com/x"), texts)
        } finally {
            server.stop()
        }
    }

    // --- Cancel and connection loss ---------------------------------------------------------------

    private val big = 256L * 1024 * 1024
    private val after = 2L * 1024 * 1024

    private fun zeros(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            b.fill(0, off, off + n); left -= n
            return n
        }
    }

    private fun bigItems() = listOf(OutgoingItem.File("big.bin", big, "application/octet-stream") { zeros(big) })

    /** Starts `--interop-server` and connects to it as "client-id". */
    private fun macServer(vararg extra: String): Triple<Process, java.io.BufferedReader, PeerLink.Connected> = runBlocking {
        val key = Wire.randomBytes(32)
        val p = mac("--interop-server", key.toHex(), *extra)
        val out = p.inputStream.bufferedReader()
        val port = out.readLine().removePrefix("port=").toInt()
        val hello = JSONObject().put("t", "hello").put("id", "client-id").put("name", "Test Phone").put("platform", "android")
        Triple(p, out, PeerLink.connect(listOf(InetSocketAddress("127.0.0.1", port)), "session", key, "client-id", hello))
    }

    private fun failLine(out: java.io.BufferedReader) = generateSequence { out.readLine() }.firstOrNull { it.startsWith("FAIL") || it == "DONE" }

    @Test
    fun androidCancelsSendToMac() {
        val (p, out, c) = macServer()
        val control = TransferControl("Stopped on Test Phone.")
        control.attach(c.channel, receiving = false)
        val e = runCatching {
            Transfer.send(bigItems(), c.channel, control) { done, _, _ -> if (done >= after) control.cancel() }
        }.exceptionOrNull()
        assertTrue("got $e", e is CancelledException && e.message == TransferControl.YOU_CANCELLED)
        assertEquals("FAIL: cancelled(\"Stopped on Test Phone.\")", failLine(out))
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
    }

    @Test
    fun macCancelsReceiveFromAndroid() {
        val (p, out, c) = macServer(after.toString())
        val e = runCatching { Transfer.send(bigItems(), c.channel) { _, _, _ -> } }.exceptionOrNull()
        assertTrue("got $e", e is CancelledException && e.message == "Stopped on Server.")
        assertEquals("FAIL: cancelled(\"${TransferControl.YOU_CANCELLED}\")", failLine(out))
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
    }

    /** Wi-Fi dropping mid-transfer looks like a sender that goes silent without closing the socket. */
    @Test
    fun macGivesUpOnSilentSender() {
        val (p, out, c) = macServer()
        val offer = JSONObject().put("kind", "file").put("name", "big.bin").put("size", big).put("mime", "application/octet-stream")
        c.channel.sendJson(JSONObject().put("t", "offer").put("items", org.json.JSONArray().put(offer)))
        c.channel.expect("accept")
        c.channel.sendChunk(ByteArray(Wire.CHUNK))
        val start = System.nanoTime()
        val line = failLine(out)
        val secs = (System.nanoTime() - start) / 1e9
        assertEquals("FAIL: connectionLost", line)
        assertTrue("took $secs s", secs in 15.0..35.0)
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        c.channel.close()
    }

    private class Receiver(val key: ByteArray, val cancelAt: Long?) : SessionDelegate {
        val failed = CountDownLatch(1)
        @Volatile var error: Exception? = null
        @Volatile var control: TransferControl? = null
        override val myId = "server-id"
        override val cancelNote = "Stopped on Test Phone."
        override fun keyFor(mode: String, from: String) = if (from == "client-id") key else null
        override fun myHello(): JSONObject = JSONObject().put("t", "hello").put("id", myId).put("name", "Test Phone")
        override fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?) {}
        override fun sink() = object : ReceiveSink {
            override fun begin(name: String, mime: String, size: Long) = object : IncomingFile {
                override val output: OutputStream = object : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, off: Int, len: Int) {} }
                override fun commit() = ReceivedFile(name, name, mime)
                override fun abort() {}
            }
        }
        override fun incomingStarted(control: TransferControl) { this.control = control }
        override fun incomingProgress(done: Long, total: Long, name: String) { if (cancelAt != null && done >= cancelAt) control?.cancel() }
        override fun incomingFinished(batch: ReceivedBatch) { failed.countDown() }
        override fun incomingFailed(error: Exception) { this.error = error; failed.countDown() }
    }

    private fun macSendsBig(cancelOnPhone: Boolean, cancelOnMac: Boolean): Pair<String, Exception?> {
        val key = Wire.randomBytes(32)
        val node = Receiver(key, if (cancelOnPhone) after else null)
        val server = ProtocolServer(0, node)
        val port = server.start()
        val f = File.createTempFile("big", ".bin").apply { deleteOnExit() }
        try {
            RandomAccessFile(f, "rw").use { it.setLength(big) }
            val args = mutableListOf("--interop-send", port.toString(), key.toHex(), f.path, "x")
            if (cancelOnMac) args += after.toString()
            val p = mac(*args.toTypedArray())
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            val output = p.inputStream.bufferedReader().readText().trim()
            assertTrue(node.failed.await(10, TimeUnit.SECONDS))
            return output to node.error
        } finally {
            server.stop()
            f.delete()
        }
    }

    @Test
    fun macCancelsSendToAndroid() {
        val (output, error) = macSendsBig(cancelOnPhone = false, cancelOnMac = true)
        assertEquals("FAIL: cancelled(\"${TransferControl.YOU_CANCELLED}\")", output)
        assertNotNull(error)
        assertTrue("got $error", error is CancelledException && error.message == "Stopped on Mac.")
    }

    @Test
    fun androidCancelsReceiveFromMac() {
        val (output, error) = macSendsBig(cancelOnPhone = true, cancelOnMac = false)
        assertEquals("FAIL: cancelled(\"Stopped on Test Phone.\")", output)
        assertTrue("got $error", error is CancelledException && error.message == TransferControl.YOU_CANCELLED)
    }

    // --- Resume -----------------------------------------------------------------------------------

    private val resumeSize = 48L * 1024 * 1024
    private val dropAt = 8L * 1024 * 1024

    private fun patternSha(size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(Wire.CHUNK)
        pattern(size).use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().toHex()
    }

    @Test
    fun androidResumesSendToMac() {
        val key = Wire.randomBytes(32)
        val p = mac("--interop-server", key.toHex(), "-", "resume")
        val out = p.inputStream.bufferedReader()
        val port = out.readLine().removePrefix("port=").toInt()
        val hello = JSONObject().put("t", "hello").put("id", "client-id").put("name", "Test Phone").put("platform", "android")
        fun connect() = runBlocking { PeerLink.connect(listOf(InetSocketAddress("127.0.0.1", port)), "session", key, "client-id", hello) }
        val items = listOf(
            OutgoingItem.File("big.bin", resumeSize, "application/octet-stream") { pattern(resumeSize) },
            OutgoingItem.Text("hi"),
        )

        // Wi-Fi drops partway: the connection just dies.
        val c1 = connect()
        val e = runCatching {
            Transfer.send(items, c1.channel, id = "batch-1") { done, _, _ -> if (done >= dropAt) c1.channel.close() }
        }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(failLine(out)!!.startsWith("FAIL"))

        // Resume: the Mac says how much it has, and only the rest is sent.
        val c2 = connect()
        var start = -1L
        Transfer.send(items, c2.channel, id = "batch-1") { done, _, _ -> if (start < 0) start = done }
        c2.channel.sendJson(JSONObject().put("t", "bye"))
        val lines = generateSequence { out.readLine() }.takeWhile { it != "DONE" }.toList()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        assertTrue("resumed at $start", start in 1 until resumeSize)
        assertEquals(listOf("start=$start", "file=big.bin sha=${patternSha(resumeSize)}", "text=hi"), lines)
    }

    private class ResumingReceiver(val key: ByteArray) : SessionDelegate {
        val results = java.util.concurrent.LinkedBlockingQueue<Result<ReceivedBatch>>()
        @Volatile var start = -1L
        val dir: File = java.nio.file.Files.createTempDirectory("nd-in").toFile()
        val files = FileSink(dir)
        override val myId = "server-id"
        override fun keyFor(mode: String, from: String) = if (from == "client-id") key else null
        override fun myHello(): JSONObject = JSONObject().put("t", "hello").put("id", myId).put("name", "Test Phone")
        override fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?) {}
        override fun sink() = files
        override fun incomingStarted(control: TransferControl) { start = -1 }
        override fun incomingProgress(done: Long, total: Long, name: String) { if (start < 0) start = done }
        override fun incomingFinished(batch: ReceivedBatch) { results += Result.success(batch) }
        override fun incomingFailed(error: Exception) { results += Result.failure(error) }
    }

    @Test
    fun macResumesSendToAndroid() {
        val key = Wire.randomBytes(32)
        val node = ResumingReceiver(key)
        val server = ProtocolServer(0, node, ResumeStore(File(node.dir, "resume.json"), node.files))
        val port = server.start()
        val f = File.createTempFile("big", ".bin").apply { deleteOnExit() }
        try {
            f.outputStream().use { o -> pattern(resumeSize).use { it.copyTo(o, Wire.CHUNK) } }
            fun send(vararg extra: String): String {
                val p = mac("--interop-send", port.toString(), key.toHex(), f.path, "x", "-", "batch-1", *extra)
                assertTrue(p.waitFor(30, TimeUnit.SECONDS))
                return p.inputStream.bufferedReader().readText().trim()
            }
            assertTrue(send(dropAt.toString()).startsWith("FAIL"))
            assertTrue(node.results.poll(30, TimeUnit.SECONDS)!!.isFailure)
            assertEquals(1, node.dir.listFiles { x -> x.name.endsWith(".part") }!!.size)

            val output = send()
            val batch = node.results.poll(10, TimeUnit.SECONDS)!!.getOrThrow()
            assertTrue("resumed at ${node.start}", node.start in 1 until resumeSize)
            assertEquals("start=${node.start}\nSENT", output)
            val got = File(batch.files.single().uri)
            assertEquals(patternSha(resumeSize), MessageDigest.getInstance("SHA-256").digest(got.readBytes()).toHex())
            assertEquals(listOf("x"), batch.texts)
            assertEquals(0, node.dir.listFiles { x -> x.name.endsWith(".part") }!!.size)
        } finally {
            server.stop()
            f.delete()
            node.dir.deleteRecursively()
        }
    }
}
