package com.amurot.notchdrop.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

/** Stream quality as the Mac picks it: "720p", "1080p", "4K" (camera) or "Native" (screen), at 30 or 60 fps. */
data class Quality(val name: String, val fps: Int) {
    /** Length of the short side; 0 means the source's own size. */
    val shortSide: Int get() = when (name) {
        "720p" -> 720
        "4K" -> 2160
        "Native" -> 0
        else -> 1080
    }

    fun bitrate(w: Int, h: Int): Int {
        // About 0.1 bits per pixel per frame at 30 fps: 1080p30 ≈ 10 Mb/s, 4K30 ≈ 35 Mb/s.
        val bpp = if (fps > 30) 0.075 else 0.1
        return (w.toLong() * h * fps * bpp).toInt().coerceIn(2_000_000, 60_000_000)
    }

    companion object {
        fun of(name: String?, fps: Int?, default: String) = Quality(name ?: default, if (fps == 60) 60 else 30)
    }
}

/** H.264 from a Surface. Each access unit goes to `out` with flags (1 = keyframe, 2 = SPS/PPS) and its time in µs. */
class H264Encoder(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    /** Re-send the last frame when the picture doesn't change (a still screen), so the Mac knows we're alive. */
    repeatStill: Boolean,
    private val out: (flags: Int, ptsUs: Long, data: ByteArray) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val surface: Surface
    @Volatile private var running = true
    private val drainer: Thread

    init {
        val f = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
            if (repeatStill) setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000)
            highProfile(width, height, fps)?.let { (profile, level) ->
                setInteger(MediaFormat.KEY_PROFILE, profile)
                setInteger(MediaFormat.KEY_LEVEL, level)
            }
        }
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders refuse an explicit profile; the default (usually Baseline or Main) still works.
            f.removeKey(MediaFormat.KEY_PROFILE)
            f.removeKey(MediaFormat.KEY_LEVEL)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = codec.createInputSurface()
        codec.start()
        drainer = thread(name = "notchdrop-encoder", isDaemon = true) { drain() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = codec.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val buf = codec.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    var flags = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or 1
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or 2
                    try {
                        out(flags, info.presentationTimeUs, bytes)
                    } catch (e: Exception) {
                        running = false
                    }
                }
                codec.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: IllegalStateException) {
            // Released while waiting.
        }
    }

    fun requestKeyframe() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        running = false
        runCatching { drainer.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TAG = "NotchDrop"

        private val info: MediaCodecInfo? by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && MIME in it.supportedTypes }
                .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }.firstOrNull()
        }

        /** Whether the hardware encoder takes this size at this rate. */
        fun supports(w: Int, h: Int, fps: Int): Boolean {
            val caps = info?.getCapabilitiesForType(MIME)?.videoCapabilities ?: return true
            return runCatching { caps.areSizeAndRateSupported(w, h, fps.toDouble()) }.getOrDefault(caps.isSizeSupported(w, h))
        }

        private fun highProfile(w: Int, h: Int, fps: Int): Pair<Int, Int>? {
            val levels = info?.getCapabilitiesForType(MIME)?.profileLevels ?: return null
            val need = when {
                w.toLong() * h > 2_100_000 -> if (fps > 30) CodecProfileLevel.AVCLevel52 else CodecProfileLevel.AVCLevel51
                fps > 30 -> CodecProfileLevel.AVCLevel42
                else -> CodecProfileLevel.AVCLevel41
            }
            val high = levels.filter { it.profile == CodecProfileLevel.AVCProfileHigh }.maxOfOrNull { it.level } ?: return null
            if (high < need) Log.i(TAG, "encoder High level $high below $need")
            return CodecProfileLevel.AVCProfileHigh to maxOf(need, minOf(high, need))
        }
    }
}
