package com.amurot.notchdrop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Callbacks from incoming sessions. Called on worker threads. */
interface SessionDelegate {
    val myId: String
    fun keyFor(mode: String, from: String): ByteArray?
    fun myHello(): JSONObject
    fun didReceiveHello(hello: JSONObject, mode: String, remoteHost: String?)
    fun sink(): ReceiveSink
    fun incomingProgress(done: Long, total: Long, name: String)
    fun incomingFinished(batch: ReceivedBatch)
    fun incomingFailed(error: Exception)
    /** Lets the UI cancel the incoming transfer that is about to start. */
    fun incomingStarted(control: TransferControl) {}
    /** Shown on the other device when this one cancels, e.g. "Stopped on Pixel 8." */
    val cancelNote: String get() = "Stopped on the phone."
}

/** `resume` keeps an interrupted incoming batch so the sender can pick up where it stopped. */
class ProtocolServer(private val port: Int, private val delegate: SessionDelegate, private val resume: ResumeStore = ResumeStore()) {
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    /** Binds on all interfaces (Wi-Fi and hotspot) and returns the bound port. */
    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        socket = ss
        thread(name = "notchdrop-accept", isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                }
            }
        }
        return ss.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(s: Socket) {
        val fc = FrameConn(s)
        var inTransfer = false
        try {
            s.soTimeout = 10_000
            s.tcpNoDelay = true
            val hs = Handshake.server(fc, delegate.myId, delegate::keyFor)
            val ch = hs.channel
            val hello = ch.expect("hello")
            ch.sendJson(delegate.myHello())
            delegate.didReceiveHello(hello, hs.mode, fc.remoteHost)
            s.soTimeout = 60_000

            loop@ while (true) {
                val m = ch.receive() as? WireMessage.Json ?: throw NDException("unexpected data")
                when (m.obj.optString("t")) {
                    "bye" -> break@loop
                    "offer" -> {
                        inTransfer = true
                        val control = TransferControl(delegate.cancelNote)
                        control.attach(ch, receiving = true)
                        delegate.incomingStarted(control)
                        s.soTimeout = Wire.STALL_MS
                        val batch = Transfer.receive(m.obj, ch, delegate.sink(), control, resume, ProgressThrottle(delegate::incomingProgress))
                        s.soTimeout = 60_000
                        inTransfer = false
                        delegate.incomingFinished(batch)
                    }
                    "ping" -> ch.sendJson(JSONObject().put("t", "pong"))
                }
            }
        } catch (e: Exception) {
            if (inTransfer) delegate.incomingFailed(e)
        } finally {
            fc.close()
        }
    }
}

/** Outbound: try every known route to the peer at once, keep the first that authenticates. */
object PeerLink {
    class Connected(val channel: SecureChannel, val hello: JSONObject, val host: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Returns the first connection that authenticates, or throws the most informative failure. */
    suspend fun connect(
        candidates: List<InetSocketAddress>,
        mode: String,
        key: ByteArray,
        myId: String,
        hello: JSONObject,
        timeoutMs: Int = 4000,
        socketFor: (InetSocketAddress) -> Socket = { Socket() },
    ): Connected {
        val distinct = candidates.distinctBy { "${it.hostString}:${it.port}" }
        if (distinct.isEmpty()) throw NDException("No known address for the Mac")
        val result = CompletableDeferred<Connected?>()
        // A protocol-level refusal ("Not paired…", auth failure) is more useful than "connection refused".
        val refusal = AtomicReference<String?>(null)
        // Attempts run in a detached scope: blocking socket IO can't be cancelled, so losers finish on their own.
        val jobs = distinct.map { addr ->
            scope.launch {
                try {
                    val c = attempt(socketFor(addr), addr, mode, key, myId, hello, timeoutMs)
                    if (!result.complete(c)) c.channel.close()
                } catch (e: NDException) {
                    refusal.set(e.message)
                } catch (_: Exception) {
                }
            }
        }
        scope.launch {
            jobs.joinAll()
            result.complete(null)
        }
        return result.await() ?: throw NDException(refusal.get() ?: "Mac not reachable")
    }

    private fun attempt(s: Socket, addr: InetSocketAddress, mode: String, key: ByteArray, myId: String, hello: JSONObject, timeoutMs: Int): Connected {
        try {
            s.tcpNoDelay = true
            s.connect(addr, timeoutMs)
            s.soTimeout = timeoutMs + 4000
            val fc = FrameConn(s)
            val ch = Handshake.client(fc, mode, myId, key)
            ch.sendJson(hello)
            val reply = ch.expect("hello")
            s.soTimeout = 60_000
            return Connected(ch, reply, fc.remoteHost)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }
}
