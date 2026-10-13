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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NotchDrop", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Spacer(Modifier.weight(1f))
                if (state.peer != null) {
                    IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Palette.dim) }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = Palette.dim) }
            }
        }

        item { MacCard(state, onScan) }

        item {
            AnimatedVisibility(state.activity != Activity.Idle) {
                ActivityCard(state, onDismissActivity)
            }
        }

        if (state.peer != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile(Icons.Rounded.UploadFile, "Photos", Modifier.weight(1f), enabled = !state.busy) {
                        pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    ActionTile(Icons.Rounded.UploadFile, "Files", Modifier.weight(1f), enabled = !state.busy) {
                        pick.launch(arrayOf("*/*"))
                    }
                    ActionTile(Icons.Rounded.ContentPaste, "Clipboard", Modifier.weight(1f), enabled = !state.busy, onClick = onSendClipboard)
                }
            }
            item {
                Text(
                    "Tip: in any app, tap Share → NotchDrop. On the Mac, drag anything onto the notch to send it here.",
                    color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        if (!batteryOk && state.peer != null) {
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BatteryAlert, null, tint = Palette.warn)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Allow background receiving", color = Palette.text, fontWeight = FontWeight.SemiBold)
                            Text("Battery saving may pause NotchDrop, so the Mac can't reach it.", color = Palette.dim, fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismissBattery) { Text("Not now", color = Palette.dim) }
                        TextButton(onClick = onBattery) { Text("Allow") }
                    }
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                Text("Recent", color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            items(state.history.take(20)) { e -> HistoryRow(e) { onOpenEntry(e) } }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(16.dp),
    ) { content() }
}

@Composable
private fun MacCard(state: NodeState, onScan: () -> Unit) {
    val peer = state.peer
    val phase = marchingPhase()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Palette.surface)
            .dashedOutline(if (peer == null) Palette.accent.copy(alpha = 0.6f) else Palette.line, active = state.busy, radius = 26.dp, phase = phase)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Laptop, null, tint = Palette.accent, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(14.dp))
        if (peer == null) {
            Text("Pair with your Mac", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "On the Mac, choose Pair Phone… from the NotchDrop menu-bar icon, then scan the code. " +
                    "Use the same Wi-Fi, or put the Mac on this phone's hotspot.",
                color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onScan,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null)
                Spacer(Modifier.width(10.dp))
                Text("Scan to pair", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(peer.displayName, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            StatusChip(state)
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
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = color, fontWeight = FontWeight.SemiBold)
                if (sub.isNotEmpty()) Text(sub, color = Palette.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!state.busy) TextButton(onClick = onDismiss) { Text("OK", color = Palette.dim) }
        }
        if (state.busy) {
            Spacer(Modifier.height(12.dp))
            ProgressBar(fraction)
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Float?, val d: Color)

private fun frac(done: Long, total: Long): Float = if (total <= 0) 1f else done.toFloat() / total

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) Palette.accent else Palette.dim)
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) Palette.text else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (e.incoming) Palette.accent2 else Palette.accent
        Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (e.incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, tint = color, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(e.title, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${e.detail} · ${DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                color = Palette.dim, fontSize = 12.sp,
            )
        }
    }
}
