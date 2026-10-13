package com.amurot.notchdrop.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurot.notchdrop.BuildConfig
import com.amurot.notchdrop.service.Node
import com.amurot.notchdrop.service.NodeService

class SettingsActivity : ComponentActivity() {
    private var batteryOk by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotchTheme {
                SystemBars(this)
                SettingsScreen(
                    batteryOk = batteryOk,
                    onBack = ::finish,
                    onBattery = {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    },
                    onReceiveToggle = { on ->
                        Node.store.receiveEnabled = on
                        if (on) NodeService.start(this) else NodeService.stop(this)
                    },
                    onUnpair = {
                        Node.unpair()
                        NodeService.stop(this)
                    },
                    onOpenUrl = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Node.uiVisible = true
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    override fun onPause() {
        super.onPause()
        Node.uiVisible = false
    }
}

@Composable
private fun SettingsScreen(
    batteryOk: Boolean,
    onBack: () -> Unit,
    onBattery: () -> Unit,
    onReceiveToggle: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val state by Node.state.collectAsStateWithLifecycle()
    val theme by Node.themeMode.collectAsStateWithLifecycle()
    val store = Node.store
    var receive by remember { mutableStateOf(store.receiveEnabled) }
    var autoCopy by remember { mutableStateOf(store.autoCopyText) }
    var openLinks by remember { mutableStateOf(store.openLinks) }
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Palette.text) }
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            }
        }

        item { Header("Names") }
        item {
            Section {
                NameRow("This phone", state.deviceName) { renaming = RenameTarget.Device }
                state.peer?.let { NameRow("Paired Mac", it.displayName) { renaming = RenameTarget.Peer } }
            }
        }

        item { Header("Receiving") }
        item {
            Section {
                ToggleRow("Receive in the background", "Keeps a small notification so your Mac can send anytime", receive) {
                    receive = it; onReceiveToggle(it)
                }
                ToggleRow("Copy received text", "Text from the Mac goes straight to the clipboard", autoCopy) {
                    autoCopy = it; store.autoCopyText = it
                }
                ToggleRow("Open received links", "Opens in the browser while NotchDrop is on screen; otherwise tap the notification", openLinks) {
                    openLinks = it; store.openLinks = it
                }
                Text("Files are saved to Downloads/From <Mac name>.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item { Header("Battery") }
        item {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background activity", color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (batteryOk) "Unrestricted — your Mac can always reach this phone"
                            else "Battery saving may pause NotchDrop, so the Mac can't reach it",
                            color = if (batteryOk) Palette.ok else Palette.warn, fontSize = 13.sp,
                        )
                    }
                    if (!batteryOk) TextButton(onClick = onBattery) { Text("Allow") }
                }
            }
        }

        item { Header("Appearance") }
        item {
            Section {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = theme == mode,
                            onClick = { Node.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.name) }
                    }
                }
            }
        }

        item { Header("About") }
        item {
            Section {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                LinkRow("Source code on GitHub") { onOpenUrl("https://github.com/BinaryBhaskar/NotchDrop") }
                Text(
                    "Everything stays on your local network and is end-to-end encrypted. MIT License.",
                    color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        state.peer?.let { peer ->
            item {
                TextButton(onClick = { confirmUnpair = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Unpair ${peer.displayName}", color = Palette.bad)
                }
            }
        }
    }

    when (renaming) {
        RenameTarget.Device -> RenameDialog(
            title = "Name this phone",
            hint = "Shown on your Mac. Leave empty to use “${store.systemName}”.",
            initial = store.customName.orEmpty(),
            onDismiss = { renaming = null },
            onSave = { renaming = null; Node.renameThisDevice(it) },
        )
        RenameTarget.Peer -> RenameDialog(
            title = "Nickname for your Mac",
            hint = "Only used on this phone. Leave empty to use “${state.peer?.name.orEmpty()}”.",
            initial = state.peer?.alias.orEmpty(),
            onDismiss = { renaming = null },
            onSave = { renaming = null; Node.renamePeer(it) },
        )
        null -> {}
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair?") },
            text = { Text("You'll need to scan the QR code on your Mac again to reconnect.") },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; onUnpair() }) { Text("Unpair", color = Palette.bad) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

private enum class RenameTarget { Device, Peer }

@Composable
private fun Header(text: String) {
    Text(text, color = Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.dim, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent))
    }
}

@Composable
private fun NameRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Palette.dim, fontSize = 13.sp)
            Text(value, color = Palette.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.Edit, "Rename", tint = Palette.dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, color = Palette.text, modifier = Modifier.weight(1f))
        Text(value, color = Palette.dim)
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.accent, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.dim)
    }
}

@Composable
private fun RenameDialog(title: String, hint: String, initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true)
                Text(hint, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
