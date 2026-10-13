package com.amurot.notchdrop.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.amurot.notchdrop.core.OutgoingItem
import com.amurot.notchdrop.service.Activity
import com.amurot.notchdrop.service.HistoryEntry
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService
import com.amurot.notchdrop.service.NodeState
import com.amurot.notchdrop.service.Outgoing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var batteryOk by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            NotchTheme {
                SystemBars(this)
                val state by Node.state.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    batteryOk = batteryOk,
                    onScan = { startActivity(Intent(this, ScanActivity::class.java)) },
                    onSendUris = ::sendUris,
                    onSendClipboard = ::sendClipboard,
                    onRefresh = { Node.scheduleRefresh(0) },
                    onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onBattery = {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    },
                    onDismissBattery = {
                        Node.store.batteryPromptDismissed = true
                        batteryOk = true
                    },
                    onOpenEntry = ::openEntry,
                    onDismissActivity = Node::dismissActivity,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Node.uiVisible = true
        NodeService.start(this)
        Node.scheduleRefresh(0)
        batteryOk = Node.store.batteryPromptDismissed ||
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    override fun onPause() {
        super.onPause()
        Node.uiVisible = false
    }

    private fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { Outgoing.fromUris(this@MainActivity, uris) } }
            items.onSuccess { Node.send(it, Outgoing.summary(it)) }
                .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun sendClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(this)?.toString()
        when {
            item?.uri != null && item.text == null -> sendUris(listOf(item.uri))
            !text.isNullOrBlank() -> {
                val items = listOf(OutgoingItem.Text(text))
                Node.send(items, Outgoing.summary(items))
            }
            else -> Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEntry(e: HistoryEntry) {
        when {
            e.uri != null -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show() }
            e.text != null -> {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", e.text))
            }
        }
    }
}

@Composable
private fun MainScreen(
    state: NodeState,
    batteryOk: Boolean,
    onScan: () -> Unit,
    onSendUris: (List<Uri>) -> Unit,
    onSendClipboard: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onBattery: () -> Unit,
    onDismissBattery: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Unit,
    onDismissActivity: () -> Unit,
) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onSendUris(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onSendUris(it) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "top") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NotchDrop", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(state.peer != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    NdIconButton(NdIcons.Refresh, "Refresh", onRefresh)
                }
                Spacer(Modifier.width(8.dp))
                NdIconButton(NdIcons.Settings, "Settings", onSettings)
            }
        }

        item(key = "mac") { MacCard(state, onScan, Modifier.animateItem()) }

        item(key = "activity") {
            AnimatedVisibility(
                state.activity != Activity.Idle,
                enter = expandVertically(NdSprings.smooth()) + fadeIn(),
                exit = shrinkVertically(NdSprings.smooth()) + fadeOut(),
            ) { ActivityCard(state, onDismissActivity) }
        }

        if (state.peer != null) {
            item(key = "actions") {
                Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(NdIcons.Image, "Photos", Palette.accent, Modifier.weight(1f), enabled = !state.busy) {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    ActionTile(NdIcons.Folder, "Files", Palette.accent2, Modifier.weight(1f), enabled = !state.busy) {
                        pick.launch(arrayOf("*/*"))
                    }
                    ActionTile(NdIcons.Paste, "Clipboard", Palette.ok, Modifier.weight(1f), enabled = !state.busy, onClick = onSendClipboard)
                }
            }
            item(key = "tip") {
                Text(
                    "Tip: in any app, tap Share → NotchDrop. On the Mac, drag anything onto the notch to send it here.",
                    color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp),
                )
            }
        }

        if (!batteryOk && state.peer != null) {
            item(key = "battery") {
                NdCard(Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NdIconTile(NdIcons.Battery, Palette.warn, size = 40.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text("Allow background receiving", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Battery saving may pause NotchDrop, so the Mac can't reach it.", color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        NdButton("Not now", onDismissBattery, style = NdButtonStyle.Text)
                        NdButton("Allow", onBattery, style = NdButtonStyle.Tonal)
                    }
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item(key = "history") {
                NdGroup("Recent", Modifier.animateItem().padding(top = 6.dp)) {
                    state.history.take(20).forEachIndexed { i, e ->
                        if (i > 0) NdDivider()
                        HistoryRow(e) { onOpenEntry(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacCard(state: NodeState, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val peer = state.peer
    val phase = marchingPhase()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.surface)
            .dashedOutline(if (peer == null) Palette.accent.copy(alpha = 0.6f) else Palette.line, active = state.busy, radius = 26.dp, phase = phase)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NdIconTile(NdIcons.Laptop, Palette.accent, size = 68.dp)
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            peer,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() using SizeTransform(clip = false) },
            label = "mac",
        ) { p ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (p == null) {
                    Text("Pair with your Mac", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On the Mac, choose Pair Phone… from the NotchDrop menu-bar icon, then scan the code. " +
                            "Use the same Wi-Fi, or put the Mac on this phone's hotspot.",
                        color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    NdButton("Scan to pair", onScan, icon = NdIcons.QrScan)
                } else {
                    Text(p.displayName, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    StatusChip(state)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(state: NodeState, onDismiss: () -> Unit) {
    val a = state.activity
    val mac = state.peer?.displayName ?: "Mac"
    val (title, sub, fraction, color) = when (a) {
        is Activity.Connecting -> Quad("Connecting to $mac…", "", null, Palette.accent)
        is Activity.Sending -> Quad("Sending to $mac", "${a.name} · ${formatBytes(a.done)} of ${formatBytes(a.total)}", frac(a.done, a.total), Palette.accent)
        is Activity.Receiving -> Quad("Receiving from $mac", "${a.name} · ${formatBytes(a.done)} of ${formatBytes(a.total)}", frac(a.done, a.total), Palette.accent2)
        is Activity.Sent -> Quad("Sent to $mac", a.summary, 1f, Palette.ok)
        is Activity.Received -> Quad(
            "Received from $mac",
            (a.batch.files.map { it.name } + a.batch.texts.map { "“${it.take(40)}”" }).joinToString(),
            1f, Palette.ok,
        )
        is Activity.Failed -> Quad("Couldn't complete", a.message, 0f, Palette.bad)
        Activity.Idle -> Quad("", "", 0f, Palette.dim)
    }
    val icon = when (a) {
        is Activity.Sending, is Activity.Connecting -> NdIcons.Up
        is Activity.Receiving -> NdIcons.Down
        is Activity.Failed -> NdIcons.Warning
        else -> NdIcons.Check
    }
    NdCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(icon to color, transitionSpec = { scaleIn(NdSprings.snappy()) togetherWith scaleOut() }, label = "actIcon") { (i, c) ->
                NdIconTile(i, c, size = 40.dp)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (sub.isNotEmpty()) Text(sub, color = Palette.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!state.busy) NdButton("OK", onDismiss, style = NdButtonStyle.Text)
        }
        AnimatedVisibility(state.busy) {
            Column {
                Spacer(Modifier.height(14.dp))
                ProgressBar(fraction)
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Float?, val d: Color)

private fun frac(done: Long, total: Long): Float = if (total <= 0) 1f else done.toFloat() / total

@Composable
private fun ActionTile(icon: ImageVector, label: String, tint: Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier
            .ndClickable(enabled, pressedScale = 0.92f, shape = NdShapes.card, rippleColor = tint, onClick = onClick)
            .background(Palette.surface)
            .border(1.dp, Palette.line, NdShapes.card)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NdIconTile(icon, if (enabled) tint else Palette.dim, size = 44.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, color = if (enabled) Palette.text else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    NdRow(
        title = e.title,
        description = "${e.detail} · ${DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
        icon = if (e.incoming) NdIcons.Down else NdIcons.Up,
        tint = if (e.incoming) Palette.accent2 else Palette.accent,
        onClick = onClick,
        trailing = { NdIcon(if (e.uri != null) NdIcons.Open else NdIcons.Copy, size = 18.dp, tint = Palette.dim) },
    )
}
