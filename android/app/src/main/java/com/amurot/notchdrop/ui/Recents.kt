package com.amurot.notchdrop.ui

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateUtils
import android.util.Size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurot.notchdrop.service.HistoryEntry
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a recent item can do; the menu and the swipe actions share these. */
enum class RecentAction { Open, ShowInFiles, Share, Copy, Resend, Remove }

private val HistoryEntry.isMedia get() = mime?.startsWith("image/") == true || mime?.startsWith("video/") == true

/**
 * A recent transfer as a card. Swipe right to show it in the Files app (or copy text), swipe left to remove it,
 * tap to open it, long-press for everything else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentRow(e: HistoryEntry, onAction: (RecentAction) -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptic()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val threshold = width * 0.32f
    val canShowInFiles = e.incoming && e.uri != null
    val rightAction = when {
        canShowInFiles -> RecentAction.ShowInFiles
        e.text != null -> RecentAction.Copy
        else -> null
    }
    var armed by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth().clip(NdShapes.card).onSizeChanged { width = it.width }) {
        // What the swipe reveals underneath.
        val x = offset.value
        val revealing = if (x > 0) rightAction else if (x < 0) RecentAction.Remove else null
        if (revealing != null) {
            val color = if (revealing == RecentAction.Remove) Palette.bad else Palette.accent2
            val progress = (abs(x) / threshold).coerceIn(0f, 1f)
            val iconScale by animateFloatAsState(if (progress >= 1f) 1.15f else 0.75f + progress * 0.25f, NdSprings.snappy(), label = "swipeIcon")
            Row(
                Modifier.matchParentSize().background(color.copy(alpha = 0.10f + 0.12f * progress)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (x > 0) Arrangement.Start else Arrangement.End,
            ) {
                val (icon, label) = swipeLook(revealing)
                if (x < 0) Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp))
                NdIcon(icon, Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }, size = 22.dp, tint = color)
                if (x > 0) Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
        }

        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.98f else 1f, NdSprings.snappy(), label = "press")
        Row(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .fillMaxWidth()
                .clip(NdShapes.card)
                .background(Palette.surface)
                .border(1.dp, Palette.line, NdShapes.card)
                .pointerInput(rightAction) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = offset.value
                            armed = false
                            scope.launch {
                                when {
                                    v <= -threshold -> {
                                        offset.animateTo(-width.toFloat() * 1.1f, NdSprings.smooth())
                                        onAction(RecentAction.Remove)
                                    }
                                    v >= threshold && rightAction != null -> {
                                        onAction(rightAction)
                                        offset.animateTo(0f, NdSprings.snappy())
                                    }
                                    else -> offset.animateTo(0f, NdSprings.snappy())
                                }
                            }
                        },
                        onDragCancel = { armed = false; scope.launch { offset.animateTo(0f, NdSprings.snappy()) } },
                    ) { change, drag ->
                        change.consume()
                        // Resist past the threshold, and when there's nothing to reveal on the right.
                        val raw = offset.value + drag
                        val limited = when {
                            raw > 0 && rightAction == null -> raw * 0.15f
                            abs(raw) > threshold -> raw.coerceIn(-threshold * 1.6f, threshold * 1.6f)
                            else -> raw
                        }
                        val nowArmed = abs(limited) >= threshold && (limited < 0 || rightAction != null)
                        if (nowArmed != armed) { armed = nowArmed; if (nowArmed) haptic(true) }
                        scope.launch { offset.snapTo(limited) }
                    }
                }
                .combinedClickable(
                    interactionSource = source,
                    indication = ripple(color = Palette.accent),
                    onLongClick = { haptic(true); onMenu() },
                    onClick = { haptic(false); onAction(RecentAction.Open) },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecentThumb(e)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${e.detail} · ${DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                    color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            NdIcon(if (e.incoming) NdIcons.Down else NdIcons.Up, size = 16.dp, tint = if (e.incoming) Palette.accent2 else Palette.accent)
        }
    }
}

@Composable
private fun swipeLook(a: RecentAction): Pair<ImageVector, String> = when (a) {
    RecentAction.ShowInFiles -> NdIcons.Folder to "Show in Files"
    RecentAction.Copy -> NdIcons.Copy to "Copy"
    else -> NdIcons.Delete to "Remove"
}

/** A photo or video thumbnail where there is one, otherwise a tinted type icon. */
@Composable
private fun RecentThumb(e: HistoryEntry) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, e.uri) {
        val uri = e.uri?.takeIf { e.isMedia } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(144, 144), null) }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), null, Modifier.size(44.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        val (icon, tint) = when {
            e.text != null && e.title.startsWith("http") -> NdIcons.Link to Palette.accent
            e.text != null -> NdIcons.Copy to Palette.ok
            e.isMedia -> NdIcons.Image to Palette.accent
            e.uri != null || !e.incoming -> NdIcons.Folder to Palette.accent2
            else -> NdIcons.Copy to Palette.ok
        }
        NdIconTile(icon, tint, size = 44.dp)
    }
}

/** Long-press menu: the item's actions as a list in a dialog card. */
@Composable
fun RecentMenu(e: HistoryEntry, onDismiss: () -> Unit, onAction: (RecentAction) -> Unit) {
    val actions = buildList {
        if (e.uri != null) add(RecentAction.Open)
        if (e.incoming && e.uri != null) add(RecentAction.ShowInFiles)
        if (e.uri != null || e.text != null) add(RecentAction.Share)
        if (e.text != null) add(RecentAction.Copy)
        if (e.uri != null && e.incoming || e.text != null) add(RecentAction.Resend)
        add(RecentAction.Remove)
    }
    NdDialog(
        onDismiss = onDismiss,
        title = e.title,
        icon = if (e.incoming) NdIcons.Down else NdIcons.Up,
        tint = if (e.incoming) Palette.accent2 else Palette.accent,
        content = {
            Column(Modifier.fillMaxWidth().clip(NdShapes.control).background(Palette.surface2)) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) NdDivider(inset = 56.dp)
                    val (icon, label, tint) = menuLook(a)
                    NdRow(label, icon = icon, tint = tint, titleColor = if (a == RecentAction.Remove) Palette.bad else Palette.text,
                        onClick = { onDismiss(); onAction(a) }, trailing = {})
                }
            }
        },
    ) {
        NdButton("Close", onDismiss, style = NdButtonStyle.Text)
    }
}

@Composable
private fun menuLook(a: RecentAction): Triple<ImageVector, String, Color> = when (a) {
    RecentAction.Open -> Triple(NdIcons.Open, "Open", Palette.accent)
    RecentAction.ShowInFiles -> Triple(NdIcons.Folder, "Show in Files", Palette.accent2)
    RecentAction.Share -> Triple(NdIcons.Share, "Share", Palette.accent)
    RecentAction.Copy -> Triple(NdIcons.Copy, "Copy", Palette.ok)
    RecentAction.Resend -> Triple(NdIcons.Send, "Send to Mac again", Palette.accent)
    RecentAction.Remove -> Triple(NdIcons.Delete, "Remove from Recent", Palette.bad)
}

/** A floating bar with an Undo button, shown after removing items. */
@Composable
fun UndoBar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(NdShapes.control)
            .background(Palette.text)
            .padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Palette.bg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(NdShapes.control).ndClickable(pressedScale = 0.92f, shape = NdShapes.control, onClick = onUndo)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) { Text("Undo", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
    }
}
