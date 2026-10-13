package com.amurot.notchdrop.core

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Wire protocol shared with the Mac app. See PROTOCOL.md at the repo root. */
object Wire {
    const val VERSION = 1
    const val MAC_PORT = 47820
    const val PHONE_PORT = 47821
    const val SERVICE_TYPE = "_notchdrop._tcp"
    const val MAX_FRAME = 4 * 1024 * 1024
    const val CHUNK = 256 * 1024

    private val rng = SecureRandom()
    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
}

class NDException(message: String) : IOException(message)

/** Length-prefixed frames over a blocking TCP socket. */
class FrameConn(val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

    val remoteHost: String?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress

    fun writeFrame(payload: ByteArray) {
        val n = payload.size
        output.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        output.write(payload)
        output.flush()
    }

    fun readFrame(): ByteArray {
        val n = input.readInt()
        if (n <= 0 || n > Wire.MAX_FRAME) throw NDException("frame size $n")
        return ByteArray(n).also { input.readFully(it) }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** AES-256-GCM with a 12-byte nonce of 4 zero bytes + 8-byte big-endian counter per direction. */
class FrameCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    private fun nonce() = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    fun seal(plain: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce()))
        counter++
        return cipher.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 16) throw NDException("short frame")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce()))
        val plain = try {
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            throw NDException("Authentication failed — re-pair devices")
        }
        counter++
        return plain
    }

    companion object {
        fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArray(length)
            var t = ByteArray(0)
            var pos = 0
            var i = 1
            while (pos < length) {
                mac.update(t)
                mac.update(info)
                mac.update(i.toByte())
                t = mac.doFinal()
                val n = minOf(t.size, length - pos)
                System.arraycopy(t, 0, out, pos, n)
                pos += n
                i++
            }
            return out
        }

        /** Returns (client->server, server->client) keys. */
        fun derive(master: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): Pair<ByteArray, ByteArray> {
            val salt = clientNonce + serverNonce
            return hkdf(master, salt, "notchdrop c2s".toByteArray()) to hkdf(master, salt, "notchdrop s2c".toByteArray())
        }
    }
}

sealed interface WireMessage {
    class Json(val obj: JSONObject) : WireMessage
    class Chunk(val data: ByteArray) : WireMessage
}

class SecureChannel(val conn: FrameConn, txKey: ByteArray, rxKey: ByteArray) : Closeable {
    private val tx = FrameCipher(txKey)
    private val rx = FrameCipher(rxKey)

    @Synchronized
    fun sendJson(obj: JSONObject) {
        val body = obj.toString().toByteArray(Charsets.UTF_8)
        conn.writeFrame(tx.seal(byteArrayOf(1) + body))
    }

    @Synchronized
    fun sendChunk(data: ByteArray, len: Int = data.size) {
        val p = ByteArray(len + 1)
        p[0] = 2
        System.arraycopy(data, 0, p, 1, len)
        conn.writeFrame(tx.seal(p))
    }

    fun receive(): WireMessage {
        val p = rx.open(conn.readFrame())
        if (p.isEmpty()) throw NDException("empty message")
        return when (p[0].toInt()) {
            1 -> {
                val obj = JSONObject(String(p, 1, p.size - 1, Charsets.UTF_8))
                if (obj.optString("t") == "error") throw NDException(obj.optString("message", "Remote error"))
                WireMessage.Json(obj)
            }
            2 -> WireMessage.Chunk(p.copyOfRange(1, p.size))
            else -> throw NDException("type ${p[0]}")
        }
    }

    fun expect(t: String): JSONObject {
        val m = receive()
        if (m !is WireMessage.Json || m.obj.optString("t") != t) throw NDException("expected $t")
        return m.obj
    }

    override fun close() = conn.close()
}

object Handshake {
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun client(conn: FrameConn, mode: String, myId: String, key: ByteArray): SecureChannel {
        val nc = Wire.randomBytes(32)
        val req = JSONObject().put("v", Wire.VERSION).put("mode", mode).put("from", myId).put("nonce", b64.encodeToString(nc))
        conn.writeFrame(req.toString().toByteArray())
        val resp = JSONObject(String(conn.readFrame()))
        if (resp.has("error")) throw NDException(resp.getString("error"))
        val ns = b64d.decode(resp.getString("nonce"))
        if (ns.size != 32) throw NDException("bad nonce")
        val (c2s, s2c) = FrameCipher.derive(key, nc, ns)
        return SecureChannel(conn, c2s, s2c)
    }

    class ServerResult(val channel: SecureChannel, val mode: String, val from: String)

    fun server(conn: FrameConn, myId: String, keyFor: (String, String) -> ByteArray?): ServerResult {
        val req = JSONObject(String(conn.readFrame()))
        val mode = req.getString("mode")
        val from = req.getString("from")
        val nc = b64d.decode(req.getString("nonce"))
        if (nc.size != 32) throw NDException("bad nonce")
        val key = keyFor(mode, from)
        if (key == null) {
            val msg = if (mode == "pair") "Phone is not in pairing mode" else "Not paired with this phone"
            runCatching { conn.writeFrame(JSONObject().put("error", msg).toString().toByteArray()) }
            throw NDException(msg)
        }
        val ns = Wire.randomBytes(32)
        val resp = JSONObject().put("v", Wire.VERSION).put("from", myId).put("nonce", b64.encodeToString(ns))
        conn.writeFrame(resp.toString().toByteArray())
        val (c2s, s2c) = FrameCipher.derive(key, nc, ns)
        return ServerResult(SecureChannel(conn, s2c, c2s), mode, from)
    }
}
