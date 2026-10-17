package com.amurot.notchdrop.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ResumeTest {
    private val offer = JSONObject().put("items", JSONArray()
        .put(JSONObject().put("kind", "file").put("name", "a.bin").put("size", 1000))
        .put(JSONObject().put("kind", "text").put("text", "hi")))
    private val sig = ResumeStore.signature(offer)

    private fun setUp(): Triple<File, FileSink, File> {
        val dir = Files.createTempDirectory("nd-resume").toFile().apply { deleteOnExit() }
        return Triple(File(dir, "resume.json"), FileSink(File(dir, "in")), dir)
    }

    @Test
    fun recordSurvivesRestartAndStraysAreCleanedUp() {
        val (record, sink, _) = setUp()
        val first = ResumeStore(record, sink)

        // A file kept after an interruption, and one that was mid-write when the process died.
        val kept = sink.begin("a.bin", "application/octet-stream", 1000)
        first.track(kept.ref!!)
        kept.output.write(ByteArray(100) { it.toByte() })
        val batch = PartialBatch("batch-1", sig).apply { parts[0] = PartialBatch.Part(kept.keep(), 100) }
        first.keep(batch)
        val crashed = sink.begin("b.bin", "application/octet-stream", 10)
        first.track(crashed.ref!!)
        crashed.output.close()
        assertTrue(record.exists())

        val second = ResumeStore(record, sink)
        second.cleanUp()
        assertFalse("stray deleted", File(crashed.ref!!).exists())
        assertTrue("kept file stays", File(kept.ref!!).exists())

        val back = second.take("batch-1", sig)
        assertNotNull(back)
        assertEquals(100L, back!!.offset(0))
        assertEquals(0L, back.offset(1))
        assertEquals(100, back.parts[0]!!.file!!.read().use { it.readBytes() }.size)
    }

    @Test
    fun anotherOfferDropsTheKeptBatch() {
        val (record, sink, _) = setUp()
        val store = ResumeStore(record, sink)
        val f = sink.begin("a.bin", "application/octet-stream", 1000)
        f.output.write(ByteArray(10))
        val batch = PartialBatch("batch-1", sig).apply { parts[0] = PartialBatch.Part(f.keep(), 10) }
        store.keep(batch)

        assertNull(ResumeStore(record, sink).take("batch-2", sig))
        assertFalse(File(f.ref!!).exists())
        assertFalse(record.exists())
        assertNull(ResumeStore(record, sink).take("batch-1", sig))
    }

    @Test
    fun noIdMeansNothingIsKept() {
        val (record, sink, _) = setUp()
        val store = ResumeStore(record, sink)
        assertNull(store.take(null, sig))
        assertFalse(record.exists())
    }
}
