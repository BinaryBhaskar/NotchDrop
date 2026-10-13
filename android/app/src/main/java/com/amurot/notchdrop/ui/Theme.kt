package com.amurot.notchdrop.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurot.notchdrop.service.Activity
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeState
import com.amurot.notchdrop.service.Route

/** Same palette as the Mac notch UI. Neutrals follow the light/dark setting; accents stay the same. */
object Palette {
    var dark by mutableStateOf(true)

    val bg get() = if (dark) Color(0xFF0E0F13) else Color(0xFFF4F5F8)
    val surface get() = if (dark) Color(0xFF17191F) else Color.White
    val surface2 get() = if (dark) Color(0xFF1F222A) else Color(0xFFECEEF2)
    val line get() = if (dark) Color(0x1FFFFFFF) else Color(0x14000000)
    val text get() = if (dark) Color.White else Color(0xFF14161B)
    val dim get() = if (dark) Color(0x8CFFFFFF) else Color(0x99000000)
    val accent = Color(0xFF5C94FF)
    val accent2 = Color(0xFF9E73FF)
    val ok = Color(0xFF4DD97A)
    val warn = Color(0xFFFF9E40)
    val bad = Color(0xFFFF6161)
    val gradient = Brush.linearGradient(listOf(accent, accent2))
}

enum class ThemeMode { System, Dark, Light }

@Composable
fun NotchTheme(content: @Composable () -> Unit) {
    val mode by Node.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
    }
    Palette.dark = dark
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = Palette.accent,
            secondary = Palette.accent2,
            background = Palette.bg,
            surface = Palette.surface,
            surfaceVariant = Palette.surface2,
            onPrimary = Color.White,
            onBackground = Palette.text,
            onSurface = Palette.text,
            error = Palette.bad,
        ),
        content = content,
    )
}

/** Status/navigation bar icons that match the current theme. */
@Composable
fun SystemBars(activity: ComponentActivity) {
    val dark = Palette.dark
    LaunchedEffect(dark) {
        val t = android.graphics.Color.TRANSPARENT
        activity.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
            navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
        )
    }
}

/** Dashed rounded outline that marches while [active], echoing the Mac drop box. */
fun Modifier.dashedOutline(color: Color, active: Boolean, radius: Dp = 22.dp, phase: Float = 0f): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        brush = if (active) Palette.gradient else Brush.linearGradient(listOf(color, color)),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx()), phase),
        ),
    )
}

@Composable
fun marchingPhase(): Float {
    val t = rememberInfiniteTransition(label = "march")
    val p by t.animateFloat(0f, -64f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    return p
}

@Composable
fun StatusChip(state: NodeState, modifier: Modifier = Modifier) {
    val (color, label) = when {
        state.peer == null -> Palette.dim to "Not paired"
        state.activity is Activity.Connecting -> Palette.accent to "Connecting…"
        state.reachable && state.route == Route.HOTSPOT -> Palette.ok to "Connected · Hotspot"
        state.reachable -> Palette.ok to "Connected · Wi-Fi"
        else -> Palette.warn to "Not on this network"
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, Modifier.padding(start = 7.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(fraction ?: 0f, label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Palette.line)) {
        if (fraction == null) {
            val t = rememberInfiniteTransition(label = "indeterminate")
            val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
            Box(Modifier.fillMaxWidth().height(6.dp).drawBehind {
                val w = size.width * 0.3f
                val start = (size.width + w) * x - w
                drawRoundRect(Palette.gradient, topLeft = Offset(start, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
            })
        } else {
            Box(Modifier.fillMaxWidth(anim.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Palette.gradient))
        }
    }
}

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%d KB".format(n / (1L shl 10))
    else -> "$n B"
}
