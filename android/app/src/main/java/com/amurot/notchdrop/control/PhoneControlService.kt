package com.amurot.notchdrop.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * "Phone control": lets the Mac tap, swipe and type on this phone, from the screen mirror
 * window or by moving the Mac's pointer off the edge of its screen (like Universal Control).
 *
 * Android only lets an accessibility service do this, and only after you turn it on yourself.
 * It reads nothing on screen except the focused text field, to type into it.
 */
class PhoneControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    // A gesture in progress, replayed with its real timing when the button comes up.
    private var path: Path? = null
    private var downAt = 0L
    private var startX = 0f
    private var startY = 0f
    private var moved = false

    // Scrolls arrive many per second; they're summed into one swipe.
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollAt = Pair(0f, 0f)
    private val flushScroll = Runnable { swipeScroll() }

    // Universal Control: our own pointer while the Mac's has crossed over.
    private var cursor: CursorView? = null
    private var cx = 0f
    private var cy = 0f
    private var entryEdge = "left"
    private var reply: ((JSONObject) -> Unit)? = null
    private var heardAt = 0L

    /** The Mac sends `alive` every second while it has the pointer here; if it goes quiet (Wi-Fi dropped), let go. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (cursor == null) return
            if (android.os.SystemClock.uptimeMillis() - heardAt > LOST_MS) {
                if (path != null) up(cx, cy)
                hideCursor()
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        hideCursor()
        if (instance === this) instance = null
        _running.value = false
    }

    private val screen: Pair<Float, Float>
        get() = wm.maximumWindowMetrics.bounds.let { it.width().toFloat() to it.height().toFloat() }

    // MARK: From the screen mirror window (positions 0…1)

    fun remote(msg: JSONObject) = main.post {
        val (w, h) = screen
        val x = (msg.optDouble("x", 0.0) * w).toFloat()
        val y = (msg.optDouble("y", 0.0) * h).toFloat()
        when (msg.optString("a")) {
            "down" -> down(x, y)
            "move" -> move(x, y)
            "up" -> up(x, y)
            "scroll" -> scroll(x, y, msg.optDouble("dx").toFloat(), msg.optDouble("dy").toFloat())
            "key" -> key(msg)
        }
    }

    // MARK: Universal Control (the Mac's pointer crossed onto the phone)

    /** `reply` goes back on the Mac's session, for telling it the pointer left. */
    fun universal(msg: JSONObject, reply: (JSONObject) -> Unit) = main.post {
        this.reply = reply
        heardAt = android.os.SystemClock.uptimeMillis()
        val (w, h) = screen
        when (msg.optString("a")) {
            "alive" -> reply(JSONObject().put("t", "alive"))
            "enter" -> {
                entryEdge = msg.optString("edge", "left")
                val pos = msg.optDouble("pos", 0.5).toFloat().coerceIn(0f, 1f)
                when (entryEdge) {
                    "left" -> { cx = 1f; cy = pos * h }
                    "right" -> { cx = w - 2; cy = pos * h }
                    "top" -> { cx = pos * w; cy = 1f }
                    else -> { cx = pos * w; cy = h - 2 }
                }
                showCursor()
                main.removeCallbacks(watchdog)
                main.postDelayed(watchdog, 1000)
            }
            "rel" -> {
                // Mac points are about half a phone's pixels across; scale so crossing the phone takes a similar hand movement.
                val k = resources.displayMetrics.density * 0.75f
                cx += msg.optDouble("dx").toFloat() * k
                cy += msg.optDouble("dy").toFloat() * k
                val exit = when (entryEdge) {
                    "left" -> cx < 0
                    "right" -> cx > w
                    "top" -> cy < 0
                    else -> cy > h
                }
                if (exit && path == null) {
                    val pos = if (entryEdge == "left" || entryEdge == "right") cy / h else cx / w
                    hideCursor()
                    reply(JSONObject().put("t", "input_exit").put("pos", pos.coerceIn(0f, 1f).toDouble()))
                    return@post
                }
                cx = cx.coerceIn(0f, w - 1)
                cy = cy.coerceIn(0f, h - 1)
                cursor?.moveTo(cx, cy)
                if (path != null) move(cx, cy)
            }
            "press" -> down(cx, cy)
            "release" -> up(cx, cy)
            "scroll" -> scroll(cx, cy, msg.optDouble("dx").toFloat(), msg.optDouble("dy").toFloat())
            "key" -> key(msg)
            "leave" -> {
                if (path != null) up(cx, cy)
                hideCursor()
            }
        }
    }

    private fun showCursor() {
        val v = cursor ?: CursorView(this).also { view ->
            val size = (resources.displayMetrics.density * 22).toInt()
            val lp = WindowManager.LayoutParams(
                size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            view.lp = lp
            runCatching { wm.addView(view, lp) }.onFailure { return }
            cursor = view
        }
        v.moveTo(cx, cy)
    }

    private fun hideCursor() {
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
    }

    private inner class CursorView(ctx: Context) : View(ctx) {
        lateinit var lp: WindowManager.LayoutParams
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.5f
        }

        fun moveTo(x: Float, y: Float) {
            lp.x = (x - width / 2f).toInt()
            lp.y = (y - height / 2f).toInt()
            runCatching { wm.updateViewLayout(this, lp) }
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f - ring.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r * 0.55f, fill)
            canvas.drawCircle(width / 2f, height / 2f, r * 0.55f, ring)
        }
    }

    // MARK: Gestures

    private fun down(x: Float, y: Float) {
        path = Path().apply { moveTo(x, y) }
        downAt = SystemClock.uptimeMillis()
        startX = x
        startY = y
        moved = false
    }

    private fun move(x: Float, y: Float) {
        val p = path ?: return
        p.lineTo(x, y)
        if (hypot(x - startX, y - startY) > resources.displayMetrics.density * 8) moved = true
    }

    private fun up(x: Float, y: Float) {
        val p = path ?: return
        path = null
        if (moved) p.lineTo(x, y)
        val held = SystemClock.uptimeMillis() - downAt
        // A still press is a tap, or a long press when held.
        val duration = if (moved) held.coerceIn(80, MAX_GESTURE) else if (held >= 450) held.coerceAtMost(3000) else 40
        dispatch(GestureDescription.StrokeDescription(p, 0, duration))
    }

    private fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        scrollAt = x to y
        scrollX += dx
        scrollY += dy
        main.removeCallbacks(flushScroll)
        main.postDelayed(flushScroll, 60)
    }

    private fun swipeScroll() {
        val k = resources.displayMetrics.density
        val (w, h) = screen
        val (x, y) = scrollAt
        // Fingers drag content, so the swipe follows the scroll direction (natural scrolling).
        var dx = scrollX * k
        var dy = scrollY * k
        scrollX = 0f
        scrollY = 0f
        if (abs(dx) < 2 && abs(dy) < 2) return
        if (abs(dx) > abs(dy)) dy = 0f else dx = 0f
        val ex = (x + dx).coerceIn(1f, w - 1)
        val ey = (y + dy).coerceIn(1f, h - 1)
        val p = Path().apply { moveTo(x, y); lineTo(ex, ey) }
        dispatch(GestureDescription.StrokeDescription(p, 0, 120))
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        runCatching { dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null) }
    }

    // MARK: Keys and typing

    private fun key(msg: JSONObject) {
        msg.optString("text").takeIf { it.isNotEmpty() }?.let { type(it); return }
        when (msg.optString("code")) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "enter" -> enter()
            "backspace" -> edit { t, s, e -> if (s != e) Triple(t.removeRange(s, e), s, s) else if (s > 0) Triple(t.removeRange(s - 1, s), s - 1, s - 1) else null }
            "delete" -> edit { t, s, e -> if (s != e) Triple(t.removeRange(s, e), s, s) else if (s < t.length) Triple(t.removeRange(s, s + 1), s, s) else null }
            "left" -> edit { t, s, e -> val c = if (s != e) s else (s - 1).coerceAtLeast(0); Triple(t, c, c) }
            "right" -> edit { t, s, e -> val c = if (s != e) e else (e + 1).coerceAtMost(t.length); Triple(t, c, c) }
            "up", "home_line" -> edit { t, _, _ -> Triple(t, 0, 0) }
            "down", "end_line" -> edit { t, _, _ -> Triple(t, t.length, t.length) }
            "selectAll" -> edit { t, _, _ -> Triple(t, 0, t.length) }
            "copy" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "cut" -> focused()?.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "tab" -> focused()?.focusSearch(View.FOCUS_FORWARD)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
    }

    private fun focused(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun enter() {
        val n = focused() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && n.actionList.any { it.id == AccessibilityAction.ACTION_IME_ENTER.id }) {
            n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        } else if (n.isMultiLine) {
            type("\n")
        }
    }

    private fun type(text: String) = edit { t, s, e -> Triple(t.substring(0, s) + text + t.substring(e), s + text.length, s + text.length) }

    /** Rewrites the focused field: `change(text, selStart, selEnd)` returns the new text and selection, or null for no change. */
    private fun edit(change: (String, Int, Int) -> Triple<String, Int, Int>?) {
        val n = focused()?.takeIf { it.isEditable } ?: return
        val current = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        var s = n.textSelectionStart.takeIf { it >= 0 } ?: current.length
        var e = n.textSelectionEnd.takeIf { it >= 0 } ?: s
        if (s > e) s = e.also { e = s }
        s = s.coerceIn(0, current.length)
        e = e.coerceIn(0, current.length)
        val (text, ns, ne) = change(current, s, e) ?: return
        if (text != current) {
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, ns)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, ne)
        })
    }

    companion object {
        private const val MAX_GESTURE = 10_000L
        private const val LOST_MS = 4_000L

        @Volatile var instance: PhoneControlService? = null
            private set

        private val _running = MutableStateFlow(false)
        /** True while Android has the service bound, i.e. Phone control is on. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Whether it's switched on in Accessibility settings (it may take a moment to bind). */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, PhoneControlService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** Opens Accessibility settings, at our entry where the phone supports it. */
        fun openSettings(ctx: Context) {
            val me = ComponentName(ctx, PhoneControlService::class.java).flattenToString()
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(direct) }.isSuccess
            if (!ok) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** App info, where ⋮ › Allow restricted settings lives for sideloaded apps. */
        fun openAppInfo(ctx: Context) {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
