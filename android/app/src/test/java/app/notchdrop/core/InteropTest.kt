package app.notchdrop.core

import app.notchdrop.core.Transfer.toHex
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
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
}
