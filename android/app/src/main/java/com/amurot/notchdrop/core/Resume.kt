package com.amurot.notchdrop.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/** A partly received file kept after an interruption, so a resumed transfer can append to it. */
interface KeptFile {
    /** Survives a restart: [KeptFiles.restore] turns it back into this file. */
    val ref: String
    /** The bytes already written, for re-hashing. */
    fun read(): InputStream
    /** Opens the file again, cut back to `length` bytes, for appending. */
    fun reopen(length: Long): IncomingFile
    fun discard()
}

/** Where kept files live, so the record of an interrupted batch can be reloaded and leftovers cleaned up. */
interface KeptFiles {
    fun restore(ref: String): KeptFile?
    /** Whether a finished file is still there. */
    fun exists(uri: String): Boolean
    /** Every unfinished file this app has left lying around, as refs. */
    fun strays(): List<String>
    fun delete(ref: String)
}

/** What an interrupted incoming batch left behind: finished files, and partial files with how much they hold. */
class PartialBatch(val id: String?, val signature: List<String>) {
    class Part(val file: KeptFile?, val length: Long)

    val parts = mutableMapOf<Int, Part>()
    val done = mutableMapOf<Int, ReceivedFile>()

    /** Bytes of item `i` already here, or [ResumeStore.COMPLETE]. */
    fun offset(i: Int): Long = when {
        done.containsKey(i) -> ResumeStore.COMPLETE
        else -> parts[i]?.takeIf { it.file != null }?.length ?: 0
    }

    fun discard() {
        parts.values.forEach { p -> runCatching { p.file?.discard() } }
        parts.clear()
    }
}

/**
 * Holds the last interrupted incoming batch until the sender resumes it or sends something else.
 * With a `file`, the record survives restarts, and [cleanUp] deletes partial files nothing refers to any more.
 */
class ResumeStore(private val file: File? = null, private val files: KeptFiles? = null) {
    private var pending: PartialBatch? = null
    /** Refs of partial files that may exist, including ones being written if the process dies. */
    private val tracked = mutableSetOf<String>()

    init {
        runCatching { if (file != null && file.exists()) load(JSONObject(file.readText())) }
    }

    @Synchronized
    fun keep(batch: PartialBatch) {
        val old = pending?.takeIf { it !== batch }
        pending = batch
        old?.let(::drop)
        save()
    }

    /** The stored batch if this offer resumes it. Any other offer drops it, deleting its partial files. */
    fun take(id: String?, signature: List<String>): PartialBatch? {
        val p = synchronized(this) { pending.also { pending = null; save() } } ?: return null
        if (id != null && p.id == id && p.signature == signature) {
            // Forget anything deleted by hand since.
            files?.let { f -> p.done.entries.removeAll { !f.exists(it.value.uri) } }
            return p
        }
        synchronized(this) { drop(p); save() }
        return null
    }

    /** Deletes a batch's partial files and stops tracking them. */
    private fun drop(p: PartialBatch) {
        p.parts.values.forEach { it.file?.ref?.let(tracked::remove) }
        p.discard()
    }

    @Synchronized fun track(ref: String) { tracked += ref; save() }
    @Synchronized fun untrack(ref: String) { tracked -= ref; save() }

    /** Deletes partial files left by earlier runs that the kept batch doesn't need. Call once before receiving. */
    @Synchronized
    fun cleanUp() {
        val f = files ?: return
        val keep = pending?.parts?.values?.mapNotNull { it.file?.ref }.orEmpty().toSet()
        val stray = tracked + runCatching { f.strays() }.getOrDefault(emptyList())
        for (ref in stray - keep) runCatching { f.delete(ref) }
        tracked.clear()
        tracked += keep
        if (pending?.let { it.parts.isEmpty() && it.done.isEmpty() } == true) pending = null
        save()
    }

    private fun load(o: JSONObject) {
        o.optJSONArray("tracked")?.let { a -> for (i in 0 until a.length()) tracked += a.getString(i) }
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return
        val sig = o.optJSONArray("signature")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val b = PartialBatch(id, sig)
        o.optJSONArray("parts")?.let { a ->
            for (k in 0 until a.length()) {
                val p = a.getJSONObject(k)
                val kept = files?.restore(p.getString("ref")) ?: continue
                b.parts[p.getInt("i")] = PartialBatch.Part(kept, p.getLong("length"))
            }
        }
        o.optJSONArray("done")?.let { a ->
            for (k in 0 until a.length()) {
                val d = a.getJSONObject(k)
                b.done[d.getInt("i")] = ReceivedFile(d.getString("name"), d.getString("uri"), d.getString("mime"))
            }
        }
        pending = b
    }

    /** Call while synchronized. */
    private fun save() {
        val file = file ?: return
        val p = pending
        val refs = tracked + p?.parts?.values?.mapNotNull { it.file?.ref }.orEmpty()
        if (p?.id == null && refs.isEmpty()) { file.delete(); return }
        val o = JSONObject().put("tracked", JSONArray(refs.sorted()))
        if (p?.id != null) {
            o.put("id", p.id).put("signature", JSONArray(p.signature))
            o.put("parts", JSONArray(p.parts.mapNotNull { (i, part) ->
                part.file?.let { JSONObject().put("i", i).put("ref", it.ref).put("length", part.length) }
            }))
            o.put("done", JSONArray(p.done.map { (i, f) ->
                JSONObject().put("i", i).put("name", f.name).put("uri", f.uri).put("mime", f.mime)
            }))
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        /** In an `accept`'s `resume` list: this item arrived in full before the interruption; don't send it. */
        const val COMPLETE = -1L

        /** One entry per offered item; a resume only matches the exact same list. */
        fun signature(offer: JSONObject): List<String> {
            val items = offer.optJSONArray("items") ?: return emptyList()
            return List(items.length()) { i ->
                val it = items.getJSONObject(i)
                if (it.optString("kind") == "file") "file:${it.optString("name")}:${it.optLong("size", -1)}" else "text"
            }
        }
    }
}
