package com.amurot.notchdrop.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Plain-file sink that can keep partial files, like MediaStoreSink does with pending entries. */
class FileSink(private val dir: File) : ReceiveSink, KeptFiles {
    init { dir.mkdirs() }

    override fun begin(name: String, mime: String, size: Long): IncomingFile {
        val f = File(dir, ".${UUID.randomUUID()}-$name.part")
        return Entry(f, name, mime, FileOutputStream(f))
    }

    inner class Entry(private val f: File, private val name: String, private val mime: String, out: OutputStream) : IncomingFile {
        override val output: OutputStream = out
        override val ref: String get() = f.path
        override fun commit(): ReceivedFile {
            val dest = File(dir, name)
            check(f.renameTo(dest))
            return ReceivedFile(name, dest.path, mime)
        }
        override fun abort() { runCatching { output.close() }; f.delete() }
        override fun keep(): KeptFile { runCatching { output.close() }; return Kept(f) }
    }

    inner class Kept(private val f: File) : KeptFile {
        override val ref: String get() = f.path
        override fun read(): InputStream = f.inputStream()
        override fun reopen(length: Long): IncomingFile {
            RandomAccessFile(f, "rw").use { it.setLength(length) }
            val name = f.name.removePrefix(".").drop(37).removeSuffix(".part") // ".<uuid>-<name>.part"
            return Entry(f, name, "application/octet-stream", FileOutputStream(f, true))
        }
        override fun discard() { f.delete() }
    }

    override fun restore(ref: String): KeptFile? = File(ref).takeIf { it.exists() }?.let { Kept(it) }
    override fun exists(uri: String) = File(uri).exists()
    override fun strays() = dir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().map { it.path }
    override fun delete(ref: String) { File(ref).delete() }
}

/** Deterministic, non-repeating bytes, so a resume that appends at the wrong offset changes the checksum. */
fun pattern(size: Long): InputStream = object : InputStream() {
    var pos = 0L
    private fun at(p: Long) = ((p * 2654435761L) ushr 13).toInt() and 0xFF
    override fun read(): Int = if (pos < size) at(pos++) else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val n = minOf(len.toLong(), size - pos).toInt()
        for (i in 0 until n) b[off + i] = at(pos + i).toByte()
        pos += n
        return n
    }
}
