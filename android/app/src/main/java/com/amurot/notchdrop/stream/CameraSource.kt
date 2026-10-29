package com.amurot.notchdrop.stream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import com.amurot.notchdrop.core.OutgoingItem
import com.amurot.notchdrop.service.Node
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Something that streams video to the Mac: the camera or the screen. */
interface StreamSource {
    val kind: String
    /** The first message: size, rotation and controls. */
    fun header(): JSONObject
    fun start(link: StreamLink)
    fun handle(msg: JSONObject)
    fun requestKeyframe()
    fun stop()
}

/**
 * Camera2 rather than CameraX: CameraX caps a video surface at 1080p, and 4K needs the
 * encoder's surface straight on the camera. The picture is sent as the sensor sees it;
 * the Mac turns it upright with `rotation`.
 */
class CameraSource(private val ctx: Context, private var quality: Quality, private val onEnd: (String?) -> Unit) : StreamSource {
    override val kind = "camera"

    private class Lens(val name: String, val id: String, val chars: CameraCharacteristics) {
        val front get() = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sensor get() = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val hasTorch get() = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val zoomRange: Range<Float>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
            } else {
                Range(1f, chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            }
    }

    private val cm = ctx.getSystemService(CameraManager::class.java)
    private val lenses: List<Lens> = findLenses()
    private var lens: Lens = lenses.firstOrNull() ?: throw IllegalStateException("No camera")
    private var zoom = 1f
    private var torch = false
    private var deviceDegrees = 0

    private val thread = HandlerThread("notchdrop-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var link: StreamLink? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: H264Encoder? = null
    private var jpeg: ImageReader? = null
    private var size = Size(1920, 1080)
    private var fps = 30
    @Volatile private var stopped = false
    /** Bumped on every reopen so callbacks from an old camera are ignored. */
    private var generation = 0

    private val orientation = object : OrientationEventListener(ctx) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            val snapped = ((deg + 45) / 90 * 90) % 360
            if (snapped == deviceDegrees) return
            deviceDegrees = snapped
            handler.post { link?.send(JSONObject().put("t", "format").put("rotation", rotation())) }
        }
    }

    private fun rotation() = (lens.sensor + (if (lens.front) -deviceDegrees else deviceDegrees) + 360) % 360

    override fun header(): JSONObject {
        pickSize()
        return state().put("kind", kind)
    }

    private fun state(): JSONObject {
        val zr = lens.zoomRange
        return JSONObject()
            .put("w", size.width).put("h", size.height).put("rotation", rotation())
            .put("lens", lens.name).put("lenses", JSONArray(lenses.map { it.name }))
            .put("zoom", zoom.toDouble()).put("zoomMin", zr.lower.toDouble()).put("zoomMax", zr.upper.toDouble())
            .put("torch", torch).put("hasTorch", lens.hasTorch)
            .put("quality", quality.name).put("fps", fps)
    }

    override fun start(link: StreamLink) {
        this.link = link
        if (orientation.canDetectOrientation()) orientation.enable()
        handler.post { open() }
    }

    override fun requestKeyframe() {
        encoder?.requestKeyframe()
    }

    override fun handle(msg: JSONObject) {
        handler.post {
            when (msg.optString("t")) {
                "keyframe" -> encoder?.requestKeyframe()
                "cam" -> {
                    if (msg.has("lens")) {
                        val l = lenses.firstOrNull { it.name == msg.optString("lens") }
                        if (l != null && l !== lens) {
                            lens = l
                            zoom = 1f.coerceIn(l.zoomRange.lower, l.zoomRange.upper)
                            torch = false
                            reopen()
                            return@post
                        }
                    }
                    if (msg.has("zoom")) zoom = msg.optDouble("zoom", 1.0).toFloat().coerceIn(lens.zoomRange.lower, lens.zoomRange.upper)
                    if (msg.has("torch")) torch = msg.optBoolean("torch") && lens.hasTorch
                    repeat()
                    link?.send(state().put("t", "cam"))
                }
                "quality" -> {
                    quality = Quality.of(msg.optString("quality"), msg.optInt("fps"), quality.name)
                    reopen()
                }
                "shutter" -> shutter()
            }
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        orientation.disable()
        handler.post {
            closeCamera()
            thread.quitSafely()
        }
    }

    // MARK: Camera

    private fun findLenses(): List<Lens> {
        val all = cm.cameraIdList.mapNotNull { id ->
            runCatching { cm.getCameraCharacteristics(id) }.getOrNull()?.takeIf {
                it.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
            }?.let { id to it }
        }
        val out = mutableListOf<Lens>()
        val back = all.filter { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        val front = all.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        back.firstOrNull()?.let { (id, c) ->
            out += Lens("back", id, c)
            // Extra back cameras some phones expose directly; most reach them through zoom instead.
            val main = focal(c)
            back.drop(1).forEach { (oid, oc) ->
                val name = if (focal(oc) < main) "wide" else "tele"
                if (out.none { it.name == name }) out += Lens(name, oid, oc)
            }
        }
        front?.let { (id, c) -> out += Lens("front", id, c) }
        return out
    }

    private fun focal(c: CameraCharacteristics) = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f

    /** The largest 16:9 (or failing that, any) size at or under the asked height that the encoder takes. */
    private fun pickSize() {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty().filter { it.width >= it.height }
        val target = quality.shortSide.takeIf { it > 0 } ?: 1080
        fun ok(s: Size, f: Int): Boolean {
            if (!H264Encoder.supports(s.width, s.height, f)) return false
            val minNs = runCatching { map.getOutputMinFrameDuration(MediaCodec::class.java, s) }.getOrDefault(0L)
            return minNs == 0L || minNs <= 1_000_000_000L / f + 1_000_000
        }
        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height }
        val wide = sorted.filter { it.width * 9 == it.height * 16 }
        fun choose(f: Int) = (wide.filter { it.height <= target } + sorted.filter { it.height <= target }).firstOrNull { ok(it, f) }
        val wantFps = quality.fps
        val picked = choose(wantFps) ?: choose(30)
        fps = if (picked != null && ok(picked, wantFps)) wantFps else 30
        size = picked ?: sorted.lastOrNull() ?: Size(1280, 720)
        if (picked != null && picked.height < target) {
            link?.send(JSONObject().put("t", "format").put("message", "This camera tops out at ${picked.height}p"))
        } else if (fps < wantFps) {
            link?.send(JSONObject().put("t", "format").put("message", "${quality.name} at 60 fps isn't supported; using 30"))
        }
    }

    private fun fpsRange(): Range<Int>? {
        val ranges = lens.chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= fps }.maxByOrNull { it.upper }
    }

    private fun reopen() {
        closeCamera()
        pickSize()
        open()
    }

    @SuppressLint("MissingPermission") // StreamActivity asks before starting.
    private fun open() {
        if (stopped) return
        val gen = ++generation
        try {
            encoder = H264Encoder(size.width, size.height, fps, quality.bitrate(size.width, size.height), repeatStill = false) { f, pts, data ->
                link?.frame(f, pts, data)
            }
        } catch (e: Exception) {
            Log.w(TAG, "encoder", e)
            fail("Couldn't start the video encoder")
            return
        }
        link?.send(state().put("t", "format"))
        cm.openCamera(lens.id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (gen != generation || stopped) { d.close(); return }
                device = d
                createSession(d, gen, jpegSizes())
            }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (gen == generation) fail("The camera was taken by another app")
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (gen == generation) fail("Camera error $error")
            }
        }, handler)
    }

    /** Photo sizes to try with the video stream: the largest first, then smaller ones some phones need at 4K. */
    private fun jpegSizes(): List<Size?> {
        val map = lens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val all = map?.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width.toLong() * it.height }.orEmpty()
        val same = all.filter { it.width * size.height == it.height * size.width }
        return (listOfNotNull(all.firstOrNull(), same.firstOrNull { it.width <= 4000 }, same.firstOrNull { it.width <= 1920 }) + listOf(null)).distinct()
    }

    @Suppress("DEPRECATION") // The list-of-surfaces form works back to API 29 and is all we need.
    private fun createSession(d: CameraDevice, gen: Int, jpegs: List<Size?>) {
        val jpegSize = jpegs.first()
        jpeg?.close()
        jpeg = jpegSize?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2).apply { setOnImageAvailableListener(::savePhoto, handler) } }
        val enc = encoder ?: return
        val outputs = listOfNotNull(enc.surface, jpeg?.surface)
        try {
            d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || stopped) { s.close(); return }
                    session = s
                    repeat()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    if (jpegs.size > 1) createSession(d, gen, jpegs.drop(1)) else fail("This phone can't stream at ${size.height}p")
                }
            }, handler)
        } catch (e: Exception) {
            if (jpegs.size > 1) createSession(d, gen, jpegs.drop(1)) else fail("Camera couldn't start")
        }
    }

    private fun CaptureRequest.Builder.common() {
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            lens.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { a ->
                val w = (a.width() / zoom).toInt()
                val h = (a.height() / zoom).toInt()
                set(CaptureRequest.SCALER_CROP_REGION, Rect((a.width() - w) / 2, (a.height() - h) / 2, (a.width() + w) / 2, (a.height() + h) / 2))
            }
        }
        set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun repeat() {
        val d = device ?: return
        val s = session ?: return
        val enc = encoder ?: return
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(enc.surface)
                common()
                fpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                val af = lens.chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (af?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }
            s.setRepeatingRequest(req.build(), null, handler)
        }.onFailure { Log.w(TAG, "repeat", it) }
    }

    private fun shutter() {
        val d = device ?: return
        val s = session ?: return
        val r = jpeg
        if (r == null) {
            link?.send(JSONObject().put("t", "cam").put("message", "Photos aren't available at this quality"))
            return
        }
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                encoder?.let { addTarget(it.surface) } // keep the video running
                common()
                set(CaptureRequest.JPEG_ORIENTATION, rotation())
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            s.capture(req.build(), null, handler)
        }.onFailure { link?.send(JSONObject().put("t", "cam").put("message", "Couldn't take the photo")) }
    }

    private fun savePhoto(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val bytes = image.use { img ->
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also(buf::get)
        }
        val name = "Photo " + SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.US).format(Date()) + ".jpg"
        val file = File(ctx.cacheDir, "shutter").apply { mkdirs() }.resolve(name)
        file.writeBytes(bytes)
        Node.send(listOf(OutgoingItem.File(name, file.length(), "image/jpeg") { file.inputStream() }), name)
        link?.send(JSONObject().put("t", "cam").put("message", "Photo sent"))
    }

    private fun closeCamera() {
        generation++
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        encoder?.release()
        encoder = null
        runCatching { jpeg?.close() }
        jpeg = null
    }

    private fun fail(message: String) {
        if (stopped) return
        onEnd(message)
    }

    private companion object {
        const val TAG = "NotchDrop"
    }
}
