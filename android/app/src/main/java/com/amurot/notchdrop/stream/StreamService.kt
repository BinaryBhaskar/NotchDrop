package com.amurot.notchdrop.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.amurot.notchdrop.R
import com.amurot.notchdrop.service.Node
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Runs the camera and/or screen stream to the Mac. Foreground with the matching types, so
 * Android lets the camera and screen capture keep going with the screen off or another app open.
 */
class StreamService : Service() {
    private val live = mutableMapOf<String, Pair<StreamSource, StreamLink?>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Node.init(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND)
        when (intent?.action) {
            ACTION_STOP -> {
                if (kind == null) live.keys.toList().forEach { end(it, null) } else end(kind, null)
                return START_NOT_STICKY
            }
            ACTION_START -> if (kind != null) begin(kind, intent) else stopIfIdle()
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun begin(kind: String, intent: Intent) {
        if (live.containsKey(kind)) return
        // Foreground first, with the type the new source needs; Android checks it when the camera or projection opens.
        live[kind] = Pair(Placeholder, null)
        if (!goForeground()) {
            live.remove(kind)
            stopIfIdle()
            return
        }
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val q = Quality.of(prefs.getString("$kind.quality", null), prefs.getInt("$kind.fps", 30), "1080p")
        val source: StreamSource = try {
            if (kind == KIND_CAMERA) {
                CameraSource(this, q) { msg -> end(kind, msg) }
            } else {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return end(kind, "Screen sharing wasn't allowed")
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                    ?: return end(kind, "Screen sharing wasn't allowed")
                ScreenSource(this, projection, q) { msg -> end(kind, msg) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "source", e)
            return end(kind, if (kind == KIND_CAMERA) "Camera unavailable" else "Couldn't capture the screen")
        }
        live[kind] = Pair(source, null)
        _active.update { it + kind }
        Node.scope.launch {
            try {
                val c = Node.dial()
                lateinit var link: StreamLink
                link = StreamLink(
                    c,
                    onMessage = { m ->
                        if (m.optString("t") == "quality") {
                            prefs.edit().putString("$kind.quality", m.optString("quality")).putInt("$kind.fps", m.optInt("fps", 30)).apply()
                        }
                        source.handle(m)
                    },
                    onKeyframeNeeded = source::requestKeyframe,
                    onClosed = { android.os.Handler(mainLooper).post { end(kind, null) } },
                )
                val header = source.header().put("control", com.amurot.notchdrop.control.PhoneControlService.running.value)
                android.os.Handler(mainLooper).post {
                    if (live[kind]?.first !== source) {
                        link.close()
                        return@post
                    }
                    live[kind] = Pair(source, link)
                    try {
                        link.start(header)
                        source.start(link)
                    } catch (e: Exception) {
                        end(kind, "Lost the Mac")
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(mainLooper).post { end(kind, e.message ?: "Mac not reachable") }
            }
        }
        notifyState()
    }

    private fun end(kind: String, message: String?) {
        val (source, link) = live.remove(kind) ?: return
        if (link != null && message != null) link.send(JSONObject().put("t", "format").put("message", message))
        link?.close()
        if (source !== Placeholder) source.stop()
        _active.update { it - kind }
        if (message != null) {
            getSystemService(NotificationManager::class.java).notify(
                NOTE_ID, Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle(if (kind == KIND_CAMERA) "Camera stopped" else "Screen sharing stopped")
                    .setContentText(message)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        if (live.isEmpty()) stopIfIdle() else { goForeground(); notifyState() }
    }

    private fun stopIfIdle() {
        if (live.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(): Boolean {
        var types = 0
        if (KIND_CAMERA in live) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (KIND_SCREEN in live) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return try {
            startForeground(SERVICE_ID, notification(), types)
            true
        } catch (e: Exception) {
            Log.w(TAG, "foreground", e)
            false
        }
    }

    private fun notifyState() {
        if (live.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(SERVICE_ID, notification())
    }

    private fun notification(): Notification {
        val mac = Node.store.peer?.displayName ?: "Mac"
        val what = listOfNotNull(
            "Camera".takeIf { KIND_CAMERA in live },
            "Screen".takeIf { KIND_SCREEN in live },
        ).joinToString(" and ")
        val stop = PendingIntent.getService(
            this, 0, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$what live on $mac")
            .setContentText("Close the window on your Mac or tap Stop")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onDestroy() {
        live.keys.toList().forEach { end(it, null) }
        super.onDestroy()
    }

    /** Stands in while the foreground type is being set up. */
    private object Placeholder : StreamSource {
        override val kind = ""
        override fun header() = JSONObject()
        override fun start(link: StreamLink) {}
        override fun handle(msg: JSONObject) {}
        override fun requestKeyframe() {}
        override fun stop() {}
    }

    companion object {
        const val KIND_CAMERA = "camera"
        const val KIND_SCREEN = "screen"
        const val CHANNEL = "stream"
        private const val TAG = "NotchDrop"
        private const val SERVICE_ID = 2
        private const val NOTE_ID = 3
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        private val _active = MutableStateFlow<Set<String>>(emptySet())
        /** Kinds streaming right now. */
        val active: StateFlow<Set<String>> = _active.asStateFlow()

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera and screen", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "While your camera or screen is live on the Mac"
                },
            )
        }

        fun startCamera(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, StreamService::class.java).setAction(ACTION_START).putExtra(EXTRA_KIND, KIND_CAMERA))
        }

        fun startScreen(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, StreamService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_KIND, KIND_SCREEN).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(ctx: Context, kind: String) {
            if (kind !in _active.value) return
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_KIND, kind))
        }
    }
}
