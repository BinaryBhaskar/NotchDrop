package com.amurot.notchdrop.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import com.amurot.notchdrop.control.PhoneControlService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Mirrors the phone's screen with MediaProjection. Input from the Mac goes to PhoneControlService, if it's on. */
class ScreenSource(
    private val ctx: Context,
    private val projection: MediaProjection,
    private var quality: Quality,
    private val onEnd: (String?) -> Unit,
) : StreamSource {
    override val kind = "screen"

    private val thread = HandlerThread("notchdrop-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val dm = ctx.getSystemService(DisplayManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var link: StreamLink? = null
    private var display: VirtualDisplay? = null
    private var encoder: H264Encoder? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    @Volatile private var stopped = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopped) onEnd("Screen sharing stopped on the phone")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            // Rotation: same pixels, swapped sides.
            if (id != android.view.Display.DEFAULT_DISPLAY) return
            val (w, h) = measure()
            if (w != width || h != height) rebuild()
        }
    }

    /** The real screen size scaled to the asked quality, in multiples of 16 for the encoder. */
    private fun measure(): Pair<Int, Int> {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        var w = b.width()
        var h = b.height()
        val short = minOf(w, h)
        val target = quality.shortSide
        var scale = if (target in 1 until short) target.toDouble() / short else 1.0
        // Some encoders top out below the native size of tall screens.
        while (scale > 0.3 && !H264Encoder.supports(even(w * scale), even(h * scale), quality.fps)) scale -= 0.1
        w = even(w * scale)
        h = even(h * scale)
        return w to h
    }

    private fun even(v: Double) = (v.toInt() / 16 * 16).coerceAtLeast(16)

    override fun header(): JSONObject {
        val (w, h) = measure()
        width = w
        height = h
        return state().put("kind", kind)
    }

    private fun state() = JSONObject()
        .put("w", width).put("h", height).put("rotation", 0)
        .put("quality", quality.name).put("fps", quality.fps)
        .put("control", PhoneControlService.running.value)

    override fun start(link: StreamLink) {
        this.link = link
        handler.post {
            // Android 14 wants the callback registered before the virtual display exists.
            projection.registerCallback(projectionCallback, handler)
            dpi = ctx.resources.displayMetrics.densityDpi
            val enc = newEncoder() ?: return@post
            try {
                display = projection.createVirtualDisplay(
                    "NotchDrop", width, height, scaledDpi(), DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, handler,
                )
            } catch (e: Exception) {
                Log.w(TAG, "virtual display", e)
                onEnd("Couldn't capture the screen")
                return@post
            }
            dm.registerDisplayListener(displayListener, handler)
        }
        scope.launch {
            PhoneControlService.running.drop(1).collect { link.send(JSONObject().put("t", "format").put("control", it)) }
        }
    }

    private fun scaledDpi(): Int {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return (dpi * width.toDouble() / maxOf(1, b.width())).toInt().coerceAtLeast(120)
    }

    private fun newEncoder(): H264Encoder? = try {
        H264Encoder(width, height, quality.fps, quality.bitrate(width, height), repeatStill = true) { f, pts, data ->
            link?.frame(f, pts, data)
        }.also { encoder = it }
    } catch (e: Exception) {
        Log.w(TAG, "encoder", e)
        onEnd("Couldn't start the video encoder")
        null
    }

    /** New size (rotation or quality): new encoder, same virtual display. */
    private fun rebuild() {
        if (stopped) return
        val (w, h) = measure()
        width = w
        height = h
        val old = encoder
        val enc = newEncoder() ?: return
        display?.resize(w, h, scaledDpi())
        display?.surface = enc.surface
        old?.release()
        link?.send(state().put("t", "format"))
    }

    override fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    override fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "keyframe" -> encoder?.requestKeyframe()
            "quality" -> handler.post {
                quality = Quality.of(msg.optString("quality"), msg.optInt("fps"), quality.name)
                rebuild()
            }
            "input" -> {
                val svc = PhoneControlService.instance
                if (svc == null) {
                    link?.send(JSONObject().put("t", "format").put("control", false))
                } else {
                    svc.remote(msg)
                }
            }
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        scope.cancel()
        handler.post {
            runCatching { dm.unregisterDisplayListener(displayListener) }
            runCatching { display?.release() }
            display = null
            encoder?.release()
            encoder = null
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "NotchDrop"
    }
}
